package com.pickaudio.data.repository

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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

data class ImportProgress(
    val running: Boolean = false, val label: String = "", val processed: Int = 0,
    val total: Int? = null, val imported: Int = 0, val skipped: Int = 0, val failed: Int = 0,
    val message: String? = null
)

class LibraryRepository(
    private val context: Context,
    private val database: PickAudioDatabase
) {
    private val trackDao = database.trackDao()
    private val localAssetDao = database.localAssetDao()
    private val favoriteDao = database.favoriteDao()
    private val importRootDao = database.importRootDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var importJob: Job? = null
    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress = _importProgress.asStateFlow()
    private var knownAssets: MutableMap<String, LocalAssetEntity> = mutableMapOf()

    private fun identity(uri: Uri): String {
        val media = runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() ?: uri
        if (media.authority == "media" && media.pathSegments.contains("audio")) return "media_audio_${media.lastPathSegment}"
        return media.toString()
    }

    private fun startImport(label: String, operation: suspend () -> Unit) {
        if (_importProgress.value.running) return
        _importProgress.value = ImportProgress(running = true, label = label)
        importJob = scope.launch {
            try {
                knownAssets = localAssetDao.getAllAssets().first().associateBy { identity(Uri.parse(it.uri)) }.toMutableMap()
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

    suspend fun relinkTrack(trackId: String, uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val existingAsset = localAssetDao.getAssetByUri(uri.toString())
        if (existingAsset?.trackId == trackId) { localAssetDao.updateAvailability(existingAsset.id, true); return@withContext trackId }
        if (existingAsset != null) {
            database.withTransaction {
                val target = trackDao.getTrackById(existingAsset.trackId) ?: error("已导入文件缺少歌曲记录")
                val repository = PlaylistRepository(database)
                val targetTrack = target.toTrack(listOf(existingAsset), database.onlineRefDao().getAllRefs().first().filter { it.trackId == target.id })
                database.playlistDao().getAllMembers().first().filter { it.trackId == trackId }.forEach {
                    repository.addTracks(it.playlistId, listOf(targetTrack))
                }
                if (favoriteDao.isFavoriteSync(trackId)) favoriteDao.addFavorite(FavoriteEntity(target.id))
                val lyric = database.lyricDao().getLyricForTrack(trackId)
                if (lyric != null && database.lyricDao().getLyricForTrack(target.id) == null) database.lyricDao().insertOrUpdate(lyric.copy(trackId = target.id))
                trackDao.deleteById(trackId)
            }
            existingAsset.trackId
        } else {
            require(importSafFile(uri)) { "无法读取所选音频文件，请重新选择" }
            val asset = localAssetDao.getAssetByUri(uri.toString()) ?: error("导入失败")
            database.withTransaction {
                localAssetDao.insertOrUpdate(asset.copy(trackId = trackId))
                trackDao.deleteById(asset.trackId)
            }
            trackId
        }
    }

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

    fun getAllTracks(): Flow<List<Track>> {
        return combine(trackDao.getAllTracks(), favoriteDao.getAllFavoriteTrackIds(), localAssetDao.getAllAssets(), database.onlineRefDao().getAllRefs()) { list, favIds, allAssets, allRefs ->
            val favSet = favIds.toSet()
            val assetsByTrack = allAssets.groupBy { it.trackId }
            val refsByTrack = allRefs.groupBy { it.trackId }
            list.map { entity ->
                val assets = assetsByTrack[entity.id].orEmpty()
                entity.toTrack(assets, refsByTrack[entity.id].orEmpty(), favSet.contains(entity.id))
            }
        }.flowOn(Dispatchers.Default)
    }

    fun searchTracks(query: String): Flow<List<Track>> {
        return getAllTracks().map { tracks -> tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) } }
    }

    suspend fun scanMediaStore(filterShortAudio: Boolean): Int = withContext(Dispatchers.IO) {
        knownAssets = localAssetDao.getAllAssets().first().associateBy { identity(Uri.parse(it.uri)) }.toMutableMap()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.RELATIVE_PATH
        )

        val selection = if (filterShortAudio) {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        } else {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        var importedCount = 0
        (context.contentResolver.query(collection, projection, selection, null, null) ?: error("无法读取手机媒体库，请检查音频访问权限")).use { cursor ->
            _importProgress.value = _importProgress.value.copy(total = cursor.count)
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val mediaId = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(collection, mediaId).toString()
                val title = cursor.getString(titleCol) ?: "未知歌曲"
                val artist = cursor.getString(artistCol) ?: "未知歌手"
                val album = cursor.getString(albumCol) ?: "未知专辑"
                val duration = cursor.getLong(durationCol)
                val size = cursor.getLong(sizeCol)
                val mime = cursor.getString(mimeCol)
                val albumId = cursor.getLong(albumIdCol)

                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                ).toString()

                // Check existing asset
                val existingAsset = knownAssets[identity(Uri.parse(contentUri))]
                if (existingAsset == null) {
                    val trackId = UUID.randomUUID().toString()
                    val track = TrackEntity(
                        id = trackId,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = duration,
                        coverUri = albumArtUri
                    )
                    trackDao.insertOrUpdate(track)

                    val asset = LocalAssetEntity(
                        trackId = trackId,
                        uri = contentUri,
                        sourceType = "MEDIA_STORE",
                        fileSize = size,
                        mimeType = mime,
                        format = mime?.substringAfterLast('/') ?: "audio",
                        isAvailable = true,
                        folderName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)).orEmpty().trimEnd('/')
                    )
                    val assetId = localAssetDao.insertOrUpdate(asset)
                    knownAssets[identity(Uri.parse(contentUri))] = asset.copy(id = assetId)
                    importedCount++
                    _importProgress.value = _importProgress.value.copy(imported = importedCount)
                } else {
                    // Update availability
                    localAssetDao.updateAvailability(existingAsset.id, true)
                    _importProgress.value = _importProgress.value.copy(skipped = _importProgress.value.skipped + 1)
                }
                _importProgress.value = _importProgress.value.copy(processed = _importProgress.value.processed + 1)
            }
        }
        importedCount
    }

    suspend fun importSafFile(uri: Uri, folder: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                // Ignore if not persistable
            }

            val uriStr = uri.toString()
            val existing = localAssetDao.getAssetByUri(uriStr) ?: knownAssets[identity(uri)]
            if (existing != null) {
                localAssetDao.updateAvailability(existing.id, true)
                if (existing.uri != uriStr) localAssetDao.insertOrUpdate(existing.copy(id = 0, uri = uriStr, sourceType = "SAF_FILE", folderName = folder.ifBlank { existing.folderName }))
                return@withContext true
            }

            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: DocumentFile.fromSingleUri(context, uri)?.name?.substringBeforeLast('.')
                ?: "未知歌曲"
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "未知歌手"
            val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "未知专辑"
            val durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L
            val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            mmr.release()

            val trackId = UUID.randomUUID().toString()
            val track = TrackEntity(
                id = trackId,
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                coverUri = null
            )
            trackDao.insertOrUpdate(track)

            val asset = LocalAssetEntity(
                trackId = trackId,
                uri = uriStr,
                sourceType = "SAF_FILE",
                fileSize = 0L,
                mimeType = mime,
                format = mime?.substringAfterLast('/') ?: "audio",
                isAvailable = true,
                folderName = folder.ifBlank { DocumentFile.fromSingleUri(context, uri)?.parentFile?.name ?: "手动导入" }
            )
            val assetId = localAssetDao.insertOrUpdate(asset)
            knownAssets[identity(uri)] = asset.copy(id = assetId)
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            false
        }
    }

    suspend fun importSafDirectory(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (e: Exception) {
            // ignore
        }

        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
        importRootDao.insertOrUpdate(
            ImportRootEntity(
                uri = treeUri.toString(),
                displayName = root.name ?: "音乐目录"
            )
        )

        var count = 0
        suspend fun scanDocFile(df: DocumentFile) {
            currentCoroutineContext().ensureActive()
            if (df.isDirectory) {
                df.listFiles().forEach { child -> scanDocFile(child) }
            } else if (df.isFile) {
                val name = df.name?.lowercase() ?: ""
                if (name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".m4a") ||
                    name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".aac")) {
                    val before = _importProgress.value.imported
                    importCountedFile(df.uri, df.parentFile?.name ?: root.name.orEmpty())
                    val lyric = df.parentFile?.findFile(df.name.orEmpty().substringBeforeLast('.') + ".lrc")
                    if (lyric != null) {
                        val asset = localAssetDao.getAssetByUri(df.uri.toString())
                        if (asset != null) runCatching { LyricRepository(context, database).importLrc(asset.trackId, lyric.uri, "SAME_DIR_LRC") }
                    }
                    count += _importProgress.value.imported - before
                }
            }
        }

        scanDocFile(root)
        count
    }

    suspend fun deleteTrack(trackId: String, deleteFile: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            if (deleteFile) {
                val assets = localAssetDao.getAssetsForTrack(trackId)
                for (asset in assets) {
                    try {
                        val uri = Uri.parse(asset.uri)
                        if (uri.scheme == "file" || uri.scheme == null) {
                            val path = uri.path ?: asset.uri
                            val file = File(path)
                            if (file.exists()) {
                                file.delete()
                            }
                        } else if (uri.scheme == "content") {
                            try {
                                context.contentResolver.delete(uri, null, null)
                            } catch (e: Exception) {
                                try {
                                    val doc = DocumentFile.fromSingleUri(context, uri)
                                    doc?.delete()
                                } catch (e2: Exception) {
                                    // ignore
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // ignore file deletion error
                    }
                }
            }

            // Explicitly clean up all related references
            localAssetDao.deleteByTrackId(trackId)
            database.onlineRefDao().deleteByTrackId(trackId)
            database.playlistDao().removeTrackFromAllPlaylists(trackId)
            favoriteDao.removeFavorite(trackId)
            database.queueDao().removeTrackFromQueue(trackId)
            database.lyricDao().deleteLyric(trackId)
            database.downloadDao().deleteTasksByTrackId(trackId)

            // Finally delete track entity
            trackDao.deleteById(trackId)
            true
        } catch (e: Exception) {
            false
        }
    }
}
