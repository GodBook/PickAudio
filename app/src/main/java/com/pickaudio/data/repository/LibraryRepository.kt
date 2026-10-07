package com.pickaudio.data.repository

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.pickaudio.download.AndroidAudioProbe
import com.pickaudio.data.model.AudioInfo
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import androidx.documentfile.provider.DocumentFile
import com.pickaudio.data.db.*
import com.pickaudio.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import androidx.room.withTransaction

class RelinkConflictException(val otherTrackId: String, val otherTitle: String) : IllegalStateException("此文件已关联到《$otherTitle》，请确认是否转移关联")

data class ImportProgress(
    val running: Boolean = false, val label: String = "", val processed: Int = 0,
    val total: Int? = null, val imported: Int = 0, val skipped: Int = 0, val failed: Int = 0,
    val message: String? = null
)

class LibraryRepository(
    private val context: Context,
    private val database: PickAudioDatabase,
    private val stopRelatedTasks: suspend (String) -> Unit = {},
    private val beforeRecordDelete: suspend (String) -> Unit = {},
    private val afterRelink: suspend (Set<String>) -> Unit = {},
    private val beforeTrackOperation: suspend (Set<String>) -> Unit = {},
    private val afterTrackOperation: suspend (Set<String>) -> Unit = {},
    private val deletionCheckpoint: (String) -> Unit = {}
) {
    private val trackDao = database.trackDao()
    private val localAssetDao = database.localAssetDao()
    private val favoriteDao = database.favoriteDao()
    private val importRootDao = database.importRootDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var importJob: Job? = null
    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress = _importProgress.asStateFlow()
    private var knownAssets: MutableMap<String, LocalAssetEntity> = ConcurrentHashMap()

    private fun identity(uri: Uri): String {
        val media = if (uri.authority == "media") uri else runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() ?: uri
        return media.toString()
    }

    private suspend fun prepareAssetIndex() {
        val assets = localAssetDao.getAllAssets().first()
        val index = ConcurrentHashMap(assets.associateBy { identity(Uri.parse(it.uri)) })
        // Legacy "external" URIs need one bulk volume lookup, rather than an ID-only identity.
        if (assets.any { Uri.parse(it.uri).pathSegments.firstOrNull() == "external" }) {
            try {
                val volumesById = mutableMapOf<Long, String?>()
                context.contentResolver.query(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.MediaColumns.VOLUME_NAME), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0); val volume = cursor.getString(1)
                        volumesById[id] = if (id in volumesById && volumesById[id] != volume) null else volume
                    }
                }
                assets.forEach { asset ->
                    val uri = Uri.parse(asset.uri)
                    if (uri.authority == "media" && uri.pathSegments.firstOrNull() == "external")
                        uri.lastPathSegment?.toLongOrNull()?.let { id ->
                            volumesById[id]?.let { volume -> index[ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), id).toString()] = asset }
                        }
                }
            } catch (_: SecurityException) { /* A denied scan must not mark old files missing. */ }
        }
        knownAssets = index
    }

    suspend fun refreshAvailability(): Int = withContext(Dispatchers.IO) {
        var missing = 0
        localAssetDao.getAllAssets().first().filter { it.sourceType != "BACKUP_HINT" }.forEach { asset ->
            currentCoroutineContext().ensureActive()
            val access = checkAssetAccess(context, Uri.parse(asset.uri))
            localAssetDao.updateAvailability(asset.id, access.available, access.reason)
            if (!access.available) missing++
        }
        missing
    }

    /** Only interrupted deletions are probed at startup; recovery never resumes destructive work. */
    suspend fun recoverInterruptedDeletions(): Int = withContext(Dispatchers.IO) {
        val pending = localAssetDao.getPendingDeletions()
        pending.forEach { asset ->
            val access = checkAssetAccess(context, Uri.parse(asset.uri))
            localAssetDao.finishDeletion(asset.id, access.available,
                if (access.available) null else "删除中断后文件不可用：${access.reason}；可移除记录或重新关联")
        }
        pending.size
    }

    suspend fun findRelinkCandidates(ids: List<String>): Map<String, List<RelinkCandidate>> = withContext(Dispatchers.IO) {
        val assets = localAssetDao.getAllAssets().first()
        val tracks = trackDao.getAllTracks().first().associateBy { it.id }
        ids.distinct().associateWith { id -> tracks[id]?.let { target ->
            matchRelinkCandidates(target, assets.filter { it.trackId == id }, assets, tracks)
        }.orEmpty() }
    }

    private fun startImport(label: String, operation: suspend () -> Unit) {
        if (_importProgress.value.running) return
        _importProgress.value = ImportProgress(running = true, label = label)
        importJob = scope.launch {
            try {
                prepareAssetIndex()
                operation()
                _importProgress.value = _importProgress.value.copy(running = false, message = "导入完成")
            } catch (e: CancellationException) {
                _importProgress.value = _importProgress.value.copy(running = false, message = "已取消，已导入歌曲保留")
                throw e
            } catch (e: Exception) {
                _importProgress.value = _importProgress.value.copy(running = false, message = "${e.message ?: "导入失败，请检查授权"}")
            }
        }
    }

    fun startScan(filterShortAudio: Boolean) = startImport("扫描手机歌曲") { scanMediaStore(filterShortAudio) }
    fun startDirectoryImport(uri: Uri) = startImport("导入文件夹") { importSafDirectory(uri) }
    fun startFileImport(uris: List<Uri>) = startImport("导入音频文件") {
        _importProgress.value = _importProgress.value.copy(total = uris.size)
        uris.forEach { importCountedFile(it) }
    }
    fun cancelImport() { importJob?.cancel() }
    fun dismissImportResult() { if (!_importProgress.value.running) _importProgress.value = ImportProgress() }

    suspend fun relinkTrack(trackId: String, uri: Uri, allowTransfer: Boolean = false): String = withContext(Dispatchers.IO) {
        require(trackDao.getTrackById(trackId) != null) { "歌曲记录不存在" }
        val inspected = AndroidAudioProbe.inspect(context, uri)
        val existing = localAssetDao.getAssetByUri(uri.toString())
        if (existing != null && existing.trackId != trackId && !allowTransfer) {
            val other = trackDao.getTrackById(existing.trackId)
            throw RelinkConflictException(existing.trackId, other?.title ?: "另一首歌曲")
        }
        val involved = setOfNotNull(trackId, existing?.trackId)
        beforeTrackOperation(involved)
        try {
        stopRelatedTasks(trackId)
        if (existing != null && existing.trackId != trackId) stopRelatedTasks(existing.trackId)
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val size = fileSize(uri)
        database.withTransaction {
            val record = trackDao.getTrackById(trackId) ?: error("歌曲已移除")
            val latest = localAssetDao.getAssetByUri(uri.toString())
            if (latest != null && latest.trackId != trackId && !allowTransfer) throw RelinkConflictException(latest.trackId, "另一首歌曲")
            localAssetDao.insertOrUpdate(latest?.copy(trackId = trackId, isAvailable = true, unavailableReason = null,
                audioInfoJson = inspected.info.encode(), fileSize = size, mimeType = inspected.info.mimeType, format = inspected.info.extension)
                ?: LocalAssetEntity(trackId = trackId, uri = uri.toString(), sourceType = "SAF_FILE", fileSize = size,
                    mimeType = inspected.info.mimeType, format = inspected.info.extension, audioInfoJson = inspected.info.encode(),
                    fileName = fileName(uri), folderName = "手动关联"))
            trackDao.insertOrUpdate(record.copy(durationMs = inspected.durationMs))
        }
        afterRelink(setOfNotNull(trackId, existing?.trackId))
        trackId
        } finally { withContext(NonCancellable) { afterTrackOperation(involved) } }
    }

    private fun fileSize(uri: Uri): Long = if (uri.scheme == "file") File(uri.path.orEmpty()).length()
        else context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0) } ?: 0L
    private fun fileName(uri: Uri): String = if (uri.scheme == "file") File(uri.path.orEmpty()).name
        else context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).orEmpty() else ""
        }.orEmpty()
    private fun saveArtwork(bytes: ByteArray?): String? {
        if (bytes == null) return null
        val directory = File(context.cacheDir, "embedded_covers").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$name.img")
        if (!file.exists()) {
            val temporary = File(directory, "$name-${UUID.randomUUID()}.tmp")
            try {
                temporary.outputStream().use { it.write(bytes) }
                check(temporary.renameTo(file) || file.isFile) { "无法保存内嵌封面" }
            } finally { temporary.delete() }
        }
        var size = directory.listFiles().orEmpty().sumOf { it.length() }
        directory.listFiles().orEmpty().sortedBy { it.lastModified() }.forEach {
            val length = it.length()
            if (size > 32 * 1024 * 1024 && it != file && it.delete()) size -= length
        }
        return Uri.fromFile(file).toString()
    }
    private fun volumeLabel(volume: String): String = context.getSystemService(android.os.storage.StorageManager::class.java)
        .storageVolumes.firstOrNull { if (volume in setOf("primary", "external_primary")) it.isPrimary else it.uuid.equals(volume, true) }
        ?.getDescription(context) ?: volume

    private suspend fun importCountedFile(uri: Uri, folder: String = "") {
        currentCoroutineContext().ensureActive()
        val duplicate = localAssetDao.getAssetByUri(uri.toString()) != null || identity(uri) in knownAssets
        val success = importSafFile(uri, folder)
        val old = _importProgress.value
        _importProgress.value = old.copy(processed = old.processed + 1,
            imported = old.imported + if (success && !duplicate) 1 else 0,
            skipped = old.skipped + if (success && duplicate) 1 else 0,
            failed = old.failed + if (!success) 1 else 0)
    }

    val libraryTrackIds = trackDao.getLibraryTrackIds()

    suspend fun removeTracks(ids: List<String>) = withContext(Dispatchers.IO) {
        ids.distinct().chunked(900).forEach { trackDao.removeFromLibrary(it) }
    }

    suspend fun addTracks(tracks: List<Track>): Int = withContext(Dispatchers.IO) {
        database.withTransaction {
            tracks.distinctBy { it.id }.sumOf { track ->
                trackDao.addToLibrary(database.ensureTrackIdentity(track).id, System.currentTimeMillis())
            }
        }
    }

    private val tracksFlow = combine(trackDao.getLibraryTracks(), favoriteDao.getAllFavoriteTrackIds(), localAssetDao.getAllAssets(), database.onlineRefDao().getAllRefs()) { list, favIds, allAssets, allRefs ->
            val favSet = favIds.toSet()
            val assetsByTrack = allAssets.groupBy { it.trackId }
            val refsByTrack = allRefs.groupBy { it.trackId }
            list.map { entity ->
                val assets = assetsByTrack[entity.id].orEmpty()
                entity.toTrack(assets, refsByTrack[entity.id].orEmpty(), favSet.contains(entity.id))
            }
        }.flowOn(Dispatchers.Default)
    fun getAllTracks(): Flow<List<Track>> = tracksFlow
    suspend fun getTrack(id: String): Track? = withContext(Dispatchers.IO) {
        database.withTransaction {
            trackDao.getTrackById(id)?.toTrack(localAssetDao.getAssetsForTrack(id),
                database.onlineRefDao().getRefsForTracks(listOf(id)), favoriteDao.isFavoriteSync(id))
        }
    }

    fun searchTracks(query: String): Flow<List<Track>> {
        return getAllTracks().map { tracks -> tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) } }
    }

    suspend fun scanMediaStore(filterShortAudio: Boolean): Int = withContext(Dispatchers.IO) {
        prepareAssetIndex()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME)
        var imported = 0
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            currentCoroutineContext().ensureActive()
            val collection = MediaStore.Audio.Media.getContentUri(volume)
            val displayVolume = volumeLabel(volume)
            val seen = HashSet<String>()
            val cursor = context.contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)
                ?: error("无法读取手机媒体库，请检查音频访问权限")
            cursor.use {
                _importProgress.value = _importProgress.value.copy(total = (_importProgress.value.total ?: 0) + it.count)
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val uri = ContentUris.withAppendedId(collection, it.getLong(0))
                    val key = identity(uri)
                    seen.add(key)
                    val existing = knownAssets[key]
                    val duration = it.getLong(4)
                    if (filterShortAudio && duration < 30000 && existing == null) continue
                    val title = it.getString(1) ?: "未知歌曲"
                    val artist = it.getString(2) ?: "未知歌手"
                    val album = it.getString(3) ?: "未知专辑"
                    val mime = it.getString(6)
                    val folder = it.getString(8).orEmpty().trimEnd('/')
                    val name = it.getString(9).orEmpty()
                    val trackId = existing?.trackId ?: UUID.randomUUID().toString()
                    val cover = "content://media/$volume/audio/albumart/${it.getLong(7)}"
                    val previous = trackDao.getTrackById(trackId)
                    val record = previous?.copy(title = title, artist = artist, album = album, durationMs = duration, coverUri = cover, isInLibrary = true)
                        ?: TrackEntity(trackId, title, artist, album, duration, cover, isInLibrary = true)
                    val asset = (existing ?: LocalAssetEntity(trackId = trackId, uri = uri.toString(), sourceType = "MEDIA_STORE",
                        fileSize = it.getLong(5), mimeType = mime, format = name.substringAfterLast('.', "audio").lowercase())).copy(
                        uri = uri.toString(), fileSize = it.getLong(5), mimeType = mime, folderName = "$displayVolume / $folder",
                        folderId = "$volume:$folder", fileName = name, isAvailable = true, unavailableReason = null,
                        audioInfoJson = existing?.audioInfoJson?.takeIf { _ -> existing.fileSize == it.getLong(5) && existing.mimeType == mime })
                    val saved = database.withTransaction {
                        val duplicate = localAssetDao.getAssetByUri(uri.toString())
                        val canonical = if (existing == null && duplicate != null) duplicate else asset
                        if (canonical.trackId == record.id) trackDao.insertOrUpdate(record)
                        else trackDao.addToLibrary(canonical.trackId, System.currentTimeMillis())
                        canonical.copy(id = localAssetDao.insertOrUpdate(canonical))
                    }
                    knownAssets[key] = saved
                    if (existing == null) imported++
                    _importProgress.value = _importProgress.value.copy(imported = imported,
                        processed = _importProgress.value.processed + 1,
                        skipped = _importProgress.value.skipped + if (existing != null) 1 else 0)
                }
            }
            // Reconcile only successfully queried volumes; valid files excluded by the music filter stay available.
            knownAssets.values.distinctBy { it.id }.filter {
                it.sourceType == "MEDIA_STORE" && Uri.parse(it.uri).pathSegments.firstOrNull() == volume && identity(Uri.parse(it.uri)) !in seen
            }.forEach {
                val access = checkAssetAccess(context, Uri.parse(it.uri))
                localAssetDao.updateAvailability(it.id, access.available, access.reason)
            }
        }
        imported
    }

    suspend fun importSafFile(uri: Uri, folder: String = "", folderId: String = "", nameHint: String? = null,
        sizeHint: Long? = null, fromDirectory: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val metadata = AndroidAudioProbe.inspect(context, uri, artwork = true)
            val uriString = uri.toString()
            val existing = localAssetDao.getAssetByUri(uriString) ?: knownAssets[identity(uri)]
            val name = nameHint ?: fileName(uri)
            val trackId = existing?.trackId ?: UUID.randomUUID().toString()
            val old = trackDao.getTrackById(trackId)
            val title = metadata.title?.takeIf { it.isNotBlank() } ?: old?.title ?: name.substringBeforeLast('.').ifBlank { "未知歌曲" }
            val record = (old ?: TrackEntity(trackId, title, metadata.artist ?: "未知歌手", metadata.album ?: "未知专辑", metadata.durationMs, null))
                .copy(title = title.take(2000), artist = (metadata.artist ?: old?.artist ?: "未知歌手").take(2000),
                    album = (metadata.album ?: old?.album ?: "未知专辑").take(2000), durationMs = metadata.durationMs,
                    coverUri = saveArtwork(metadata.artwork) ?: old?.coverUri, isInLibrary = true)
            val asset = LocalAssetEntity(id = existing?.takeIf { it.uri == uriString }?.id ?: 0, trackId = trackId, uri = uriString,
                sourceType = if (fromDirectory) "SAF_DIR" else existing?.sourceType ?: "SAF_FILE",
                fileSize = sizeHint ?: fileSize(uri), mimeType = metadata.info.mimeType, format = metadata.info.extension,
                audioInfoJson = metadata.info.encode(), folderName = folder.ifBlank { existing?.folderName ?: "手动导入" },
                folderId = folderId.ifBlank { existing?.folderId.orEmpty() }, fileName = name)
            val saved = database.withTransaction {
                val duplicate = localAssetDao.getAssetByUri(uriString)
                if (duplicate != null && duplicate.trackId != trackId) {
                    trackDao.addToLibrary(duplicate.trackId, System.currentTimeMillis())
                    duplicate
                }
                else {
                    trackDao.insertOrUpdate(record)
                    asset.copy(id = localAssetDao.insertOrUpdate(asset.copy(id = duplicate?.id ?: asset.id)))
                }
            }
            knownAssets[identity(uri)] = saved
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    private data class DirectoryEntry(val uri: Uri, val name: String, val mime: String, val size: Long)
    suspend fun importSafDirectory(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prepareAssetIndex()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val rootName = fileName(rootUri).ifBlank { "音乐目录" }
        importRootDao.insertOrUpdate(ImportRootEntity(treeUri.toString(), rootName))
        val directories = ArrayDeque<Pair<String, String>>()
        val rootLabel = if (treeUri.authority == "com.android.externalstorage.documents" && rootId.contains(':'))
            "${volumeLabel(rootId.substringBefore(':'))} / ${rootId.substringAfter(':')}" else rootName
        directories.add(rootId to rootLabel)
        val visited = HashSet<String>()
        var count = 0
        val lyricRepository = LyricRepository(context, database)
        while (directories.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (documentId, path) = directories.removeFirst()
            if (!visited.add(documentId)) continue
            val folderUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val children = mutableListOf<DirectoryEntry>()
            context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) children.add(DirectoryEntry(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0)),
                    cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getLong(3)))
            } ?: error("无法读取文件夹，请重新授权")
            val lyrics = children.filter { it.name.endsWith(".lrc", true) }.associateBy { it.name.substringBeforeLast('.').lowercase() }
            for (child in children) {
                currentCoroutineContext().ensureActive()
                if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    directories.add(DocumentsContract.getDocumentId(child.uri) to "$path / ${child.name}")
                } else if (child.mime.startsWith("audio/") || child.name.substringAfterLast('.').lowercase() in setOf("mp3", "flac", "m4a", "wav", "ogg", "aac", "opus", "alac")) {
                    val duplicate = identity(child.uri) in knownAssets || localAssetDao.getAssetByUri(child.uri.toString()) != null
                    val success = importSafFile(child.uri, path, folderUri.toString(), child.name, child.size, true)
                    if (success && !duplicate) count++
                    _importProgress.value = _importProgress.value.copy(processed = _importProgress.value.processed + 1,
                        imported = count, skipped = _importProgress.value.skipped + if (success && duplicate) 1 else 0,
                        failed = _importProgress.value.failed + if (!success) 1 else 0)
                    if (success) lyrics[child.name.substringBeforeLast('.').lowercase()]?.let { lyric ->
                        val asset = localAssetDao.getAssetByUri(child.uri.toString())
                        if (asset != null) try { lyricRepository.importLrc(asset.trackId, lyric.uri, "SAME_DIR_LRC") }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* Invalid sibling lyrics do not discard a valid song. */ }
                    }
                }
            }
        }
        count
    }

    suspend fun deleteTrack(trackId: String, deleteFile: Boolean = false): Boolean =
        deleteTrackWithReport(trackId, deleteFile).recordRemoved

    suspend fun deleteTrackWithReport(trackId: String, deleteFile: Boolean = false): TrackDeletionReport =
        withContext(Dispatchers.IO) {
            var deleted = 0
            var missing = 0
            val failures = mutableListOf<AssetDeletionFailure>()
            var operationStarted = false
            try {
                beforeTrackOperation(setOf(trackId))
                operationStarted = true
                stopRelatedTasks(trackId)
                if (deleteFile) {
                    localAssetDao.getAssetsForTrack(trackId).forEach { asset ->
                        currentCoroutineContext().ensureActive()
                        try {
                            localAssetDao.updateAvailability(asset.id, false, DELETION_PENDING)
                            deletionCheckpoint("PREPARED")
                            when (deleteAudioAsset(context, asset.uri)) {
                                AssetDeletionResult.DELETED -> deleted++
                                AssetDeletionResult.MISSING -> missing++
                            }
                            deletionCheckpoint("FILE_DELETED")
                            withContext(NonCancellable) { localAssetDao.finishDeletion(asset.id, false, "音频文件已删除，可移除记录或重新关联") }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            val reason = e.message ?: "需要文件删除权限"
                            failures.add(AssetDeletionFailure(asset.uri, reason))
                            val access = checkAssetAccess(context, Uri.parse(asset.uri))
                            localAssetDao.finishDeletion(asset.id, access.available, access.reason ?: reason)
                        }
                    }
                }
                if (failures.isNotEmpty()) return@withContext TrackDeletionReport(false, deleted, missing, failures)
                beforeRecordDelete(trackId)
                database.withTransaction {
                    // Room foreign keys remove assets, online refs, membership, favorites, queue and lyrics together.
                    database.downloadDao().deleteTasksByTrackId(trackId)
                    if (database.playbackDao().getSnapshot()?.currentTrackId == trackId) database.playbackDao().clearSnapshot()
                    trackDao.deleteById(trackId)
                }
                TrackDeletionReport(true, deleted, missing)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { TrackDeletionReport(false, deleted, missing, failures, e.message ?: "歌曲记录处理失败") }
            finally { if (operationStarted) withContext(NonCancellable) { afterTrackOperation(setOf(trackId)) } }
        }
}
