package com.pickaudio.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pickaudio.data.db.*
import com.pickaudio.data.model.Quality
import com.pickaudio.data.model.ThemeMode
import com.pickaudio.data.model.ThemeColor
import com.pickaudio.data.preferences.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

data class BackupManifest(
    val version: Int = 3, val exportedAt: Long = System.currentTimeMillis(), val appVersion: String = com.pickaudio.BuildConfig.VERSION_NAME,
    val playlists: List<BackupPlaylist>, val favorites: List<String>, val tracks: List<BackupTrack>,
    val lyrics: List<BackupLyric>, val sourceDescriptors: List<BackupSourceDescriptor>,
    val settings: Map<String, String>? = null, val favoriteDetails: List<BackupFavorite>? = null
)
data class BackupPlaylist(val id: String, val name: String, val sortOrder: Int, val trackIds: List<String>,
    val createdAt: Long = 0, val addedAtByTrack: Map<String, Long>? = null)
data class BackupTrack(val id: String, val title: String, val artist: String, val album: String, val durationMs: Long,
    val platform: String? = null, val platformSongId: String? = null, val coverUri: String? = null,
    val createdAt: Long = 0, val metadataJson: String? = null, val files: List<BackupFileHint>? = null,
    val isInLibrary: Boolean? = null)
data class BackupFileHint(val fileSize: Long, val mimeType: String?, val format: String?, val sha256: String?, val folder: String,
    val fileName: String? = null, val folderId: String? = null, val audioInfoJson: String? = null)
data class BackupFavorite(val trackId: String, val addedAt: Long, val sortOrder: Long)
data class BackupLyric(val trackId: String, val offsetMs: Long, val content: String, val sourceType: String? = null)
data class BackupSourceDescriptor(val name: String, val version: String, val scriptHash: String)
data class ExportSummary(val playlists: Int, val favorites: Int, val tracks: Int, val lyrics: Int)
data class RestoreReport(val addedTracks: Int, val mergedTracks: Int, val newPlaylists: Int, val mergedPlaylists: Int,
    val favorites: Int, val lyrics: Int, val settingsRestored: Boolean, val missingLocalTracks: List<BackupTrack>, val missingSources: List<String>)

class BackupManager(private val context: Context, private val database: PickAudioDatabase,
    private val checkpoint: (suspend (String) -> Unit)? = null) {
    private val gson = GsonBuilder().create()
    private val preferences = UserPreferences(context)
    private val sessions = database.restoreSessionDao()
    val pendingSessions = sessions.getPending()
    private val _recoveryStatus = MutableStateFlow<String?>(null)
    val recoveryStatus = _recoveryStatus.asStateFlow()
    private val payloadDirectory get() = File(context.filesDir, "restore_payloads").apply { mkdirs() }
    private fun checksum(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun checksum(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private class LimitedOutput(private val target: OutputStream) : OutputStream() {
        private var count = 0L
        override fun write(value: Int) { require(++count <= MAX_MANIFEST_BYTES) { CAPACITY_MESSAGE }; target.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(count + length <= MAX_MANIFEST_BYTES) { CAPACITY_MESSAGE }
            target.write(bytes, offset, length); count += length
        }
        override fun flush() = target.flush()
        override fun close() = target.close()
    }
    private fun writeManifest(manifest: BackupManifest, file: File) {
        FileOutputStream(file).use { output ->
            val writer = OutputStreamWriter(LimitedOutput(output), Charsets.UTF_8)
            gson.toJson(manifest, writer); writer.flush(); output.fd.sync()
        }
    }

    suspend fun export(uri: Uri): ExportSummary = withContext(Dispatchers.IO) {
        val settings = preferences.exportSettings()
        val manifest = database.withTransaction {
            val membersByPlaylist = database.playlistDao().getAllMembers().first().groupBy { it.playlistId }
            val refs = database.onlineRefDao().getAllRefs().first().associateBy { it.trackId }
            val assets = database.localAssetDao().getAllAssets().first().groupBy { it.trackId }
            val playlists = database.playlistDao().getAllPlaylists().first().filter { !it.isSystem }.map { playlist ->
                val members = membersByPlaylist[playlist.id].orEmpty().sortedBy { it.sortOrder }
                BackupPlaylist(playlist.id, playlist.name, playlist.sortOrder, members.map { it.trackId }, playlist.createdAt,
                    members.associate { it.trackId to it.addedAt })
            }
            val tracks = database.trackDao().getAllTracks().first().map {
                val ref = refs[it.id]
                BackupTrack(it.id, it.title, it.artist, it.album, it.durationMs, ref?.platform, ref?.platformSongId,
                    it.coverUri, it.createdAt, ref?.platformMetadataJson,
                    assets[it.id].orEmpty().map { asset -> BackupFileHint(asset.fileSize, asset.mimeType, asset.format, asset.fileHash,
                        asset.folderName, asset.fileName, asset.folderId, asset.audioInfoJson) }, isInLibrary = it.isInLibrary)
            }
            require(database.lyricDao().getLyricCharacterCount() <= MAX_MANIFEST_BYTES) { CAPACITY_MESSAGE }
            val lyrics = database.lyricDao().getAllLyrics().map { BackupLyric(it.trackId, it.offsetMs, it.content, it.sourceType) }
            val sources = database.sourceDao().getAllSources().first().map { BackupSourceDescriptor(it.name, it.version, it.scriptHash) }
            val favorites = database.favoriteDao().getFavoritesSync()
            BackupManifest(playlists = playlists, favorites = favorites.map { it.trackId }, tracks = tracks,
                lyrics = lyrics, sourceDescriptors = sources, settings = settings,
                favoriteDetails = favorites.map { BackupFavorite(it.trackId, it.addedAt, it.sortOrder) })
        }
        validate(manifest)
        val payload = File.createTempFile("backup-manifest-", ".json", context.cacheDir)
        val archive = File.createTempFile("backup-archive-", ".zip", context.cacheDir)
        try {
            // Check the same capacity contract as import before opening the user's destination.
            writeManifest(manifest, payload)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); payload.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                zip.putNextEntry(ZipEntry("manifest.sha256")); zip.write(checksum(payload).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            currentCoroutineContext().ensureActive()
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                archive.inputStream().use { it.copyTo(output) }
            } ?: error("无法创建备份文件")
        } finally { payload.delete(); archive.delete() }
        ExportSummary(manifest.playlists.size, manifest.favorites.size, manifest.tracks.size, manifest.lyrics.size)
    }

    suspend fun inspect(uri: Uri): BackupManifest = withContext(Dispatchers.IO) {
        var manifest: ByteArray? = null
        var expectedHash: String? = null
        var expanded = 0L
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份文件")
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                require(entry.name in setOf("manifest.json", "manifest.sha256") && !entry.isDirectory) { "备份包含不支持的文件" }
                val limit = if (entry.name == "manifest.json") MAX_MANIFEST_BYTES else 64
                val bytes = zip.readNBytes(limit + 1)
                expanded += bytes.size
                require(bytes.size <= limit && expanded <= MAX_EXPANDED_BYTES) { CAPACITY_MESSAGE }
                if (entry.name == "manifest.json") { require(manifest == null) { "备份清单重复" }; manifest = bytes }
                else { require(expectedHash == null) { "备份校验重复" }; expectedHash = String(bytes, Charsets.UTF_8).trim() }
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        val bytes = manifest ?: error("备份缺少清单")
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        validateBackupJson(json.reader())
        val parsed = gson.fromJson(json, BackupManifest::class.java) ?: error("备份清单无效")
        validate(parsed)
        if (parsed.version >= 2) require(expectedHash != null) { "备份缺少完整性校验" }
        expectedHash?.let { require(it.matches(Regex("[a-f0-9]{64}")) && it == checksum(bytes)) { "备份校验失败，文件可能已损坏" } }
        parsed
    }

    fun validate(manifest: BackupManifest) {
        fun text(value: String?, max: Int = 4096): Boolean = value != null && value.length <= max
        fun id(value: String?) = text(value, 512) && !value.isNullOrBlank()
        require(manifest.version in 1..3) { "暂不支持此备份版本" }
        val tracks = requireNotNull(manifest.tracks) { "备份缺少歌曲" }
        require(tracks.size <= 100000 && tracks.all { id(it.id) && text(it.title) && !it.title.isNullOrBlank() && text(it.artist) && text(it.album) && it.durationMs >= 0 }) { "备份歌曲清单无效" }
        val ids = tracks.map { it.id }.toSet()
        require(ids.size == tracks.size) { "备份包含重复歌曲编号" }
        val refs = tracks.filter { it.platform != null || it.platformSongId != null }
        require(refs.all { it.platform in setOf("wy", "tx") && id(it.platformSongId) } &&
            refs.map { it.platform to it.platformSongId }.distinct().size == refs.size) { "备份在线引用无效或重复" }
        tracks.forEach { track ->
            require(track.createdAt >= 0 && text(track.coverUri ?: "", 8192) && track.files.orEmpty().size <= 32) { "备份歌曲元信息无效" }
            track.metadataJson?.let {
                require(it.length <= 65536) { "平台元信息过大" }; validateBackupJson(it.reader())
                require(JsonParser.parseString(it).isJsonObject) { "平台元信息无效" }
            }
            track.files.orEmpty().forEach { require(it.fileSize >= 0 && text(it.folder) &&
                text(it.mimeType ?: "", 256) && text(it.format ?: "", 1024) && text(it.fileName ?: "") && text(it.folderId ?: "", 8192) &&
                (it.audioInfoJson == null || it.audioInfoJson.length <= 4096 && com.pickaudio.data.model.AudioInfo.decode(it.audioInfoJson) != null) &&
                (it.sha256 == null || it.sha256.matches(Regex("[a-fA-F0-9]{64}")))) { "文件线索无效" } }
        }
        val playlists = requireNotNull(manifest.playlists) { "备份缺少歌单" }
        require(playlists.size <= 10000 && playlists.map { it.id }.distinct().size == playlists.size) { "备份歌单编号重复或过多" }
        playlists.forEach { playlist ->
            val members = requireNotNull(playlist.trackIds) { "歌单缺少成员清单" }
            require(id(playlist.name) && id(playlist.id) && playlist.id != PickAudioDatabase.FAVORITE_PLAYLIST_ID &&
                members.distinct().size == members.size && members.all { it in ids } &&
                playlist.addedAtByTrack.orEmpty().all { (value, time) -> value in members && time >= 0 }) { "歌单引用或编号无效" }
        }
        val favorites = requireNotNull(manifest.favorites) { "备份缺少收藏" }
        require(favorites.distinct().size == favorites.size && favorites.all { it in ids }) { "收藏引用无效或重复" }
        manifest.favoriteDetails?.let { details ->
            require(details.map { it.trackId }.toSet() == favorites.toSet() && details.size == favorites.size &&
                details.all { it.addedAt >= 0 }) { "收藏时间或顺序无效" }
        }
        val lyrics = requireNotNull(manifest.lyrics) { "备份缺少歌词" }
        require(lyrics.map { it.trackId }.distinct().size == lyrics.size &&
            lyrics.all { it.trackId in ids && text(it.content, 2 * 1024 * 1024) && it.offsetMs in -60000..60000 }) { "歌词引用或内容无效" }
        require(manifest.sourceDescriptors.orEmpty().size <= 1000 &&
            manifest.sourceDescriptors.orEmpty().all { id(it.name) && text(it.version) && text(it.scriptHash, 512) }) { "音源描述无效" }
        manifest.settings?.forEach { (key, value) ->
            require(text(key, 64) && text(value, 128)) { "备份设置无效" }
            require(when (key) {
                "theme" -> ThemeMode.entries.any { it.name == value }
                "themeColor" -> ThemeColor.entries.any { it.name == value }
                "onlineQuality", "downloadQuality" -> Quality.entries.any { it.value == value }
                "filterShortAudio", "wifiOnly", "translation" -> value.toBooleanStrictOrNull() != null
                "lyricSize" -> value.toIntOrNull()?.let { it in 14..28 } == true
                "audioCacheMb" -> value.toIntOrNull()?.let { it in listOf(64, 256, 1024) } == true
                else -> true
            }) { "备份设置 $key 无效" }
        }
    }

    suspend fun restore(manifest: BackupManifest, restoreSettings: Boolean): RestoreReport = withContext(Dispatchers.IO) {
        operationLock.withLock {
            validate(manifest)
            require(pendingSessions.first().isEmpty()) { "请先继续上次未完成的恢复" }
            val id = UUID.randomUUID().toString()
            val payload = File(payloadDirectory, "$id.json")
            val temporary = File(payloadDirectory, "$id.part")
            var registered = false
            try {
                writeManifest(manifest, temporary)
                check(temporary.renameTo(payload)) { "无法保存恢复内容" }
                val receipt = JsonObject().apply { addProperty("file", payload.name); addProperty("sha256", checksum(payload)) }
                val session = RestoreSessionEntity(id, receipt.toString(), restoreSettings, "PREPARED")
                sessions.save(session)
                registered = true
                checkpoint?.invoke("PREPARED")
                continueSession(session, manifest)
            } finally { temporary.delete(); if (!registered) payload.delete() }
        }
    }

    suspend fun resumeSession(id: String): RestoreReport = withContext(Dispatchers.IO) {
        operationLock.withLock {
            val session = sessions.getById(id) ?: error("恢复任务不存在")
            require(session.state != "COMPLETED") { "恢复已经完成" }
            try { continueSession(session, readPayload(session)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                sessions.getById(id)?.let { sessions.save(it.copy(errorMessage = e.message)) }
                throw e
            }
        }
    }

    suspend fun recoverPending() {
        val pending = pendingSessions.first()
        if (pending.isEmpty()) return
        _recoveryStatus.value = "正在继续上次备份恢复"
        for (session in pending) {
            try {
                val result = resumeSession(session.id)
                _recoveryStatus.value = "上次恢复已完成：新增 ${result.addedTracks} 首，合并 ${result.mergedTracks} 首"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _recoveryStatus.value = "备份恢复待继续：${e.message}" }
        }
    }

    private fun readPayload(session: RestoreSessionEntity): BackupManifest {
        val receipt = JsonParser.parseString(session.manifestJson).asJsonObject
        val name = receipt.get("file").asString
        require(name == "${session.id}.json" && name.matches(Regex("[a-fA-F0-9-]{36}\\.json"))) { "恢复文件标识无效" }
        val file = File(payloadDirectory, name)
        require(file.isFile && file.length() <= MAX_MANIFEST_BYTES && checksum(file) == receipt.get("sha256").asString) { "恢复内容丢失或校验失败，请保留原备份" }
        validateBackupJson(file.reader(Charsets.UTF_8))
        return requireNotNull(file.reader(Charsets.UTF_8).use { gson.fromJson(it, BackupManifest::class.java) })
            .also { validate(it) }
    }

    private suspend fun continueSession(original: RestoreSessionEntity, manifest: BackupManifest): RestoreReport {
        var session = original
        var report = session.reportJson?.let { gson.fromJson(it, RestoreReport::class.java) }
        if (session.state == "PREPARED") {
            database.withTransaction {
                report = applyDatabase(manifest)
                session = session.copy(state = "DATABASE_APPLIED", reportJson = gson.toJson(report), errorMessage = null)
                sessions.save(session)
            }
            checkpoint?.invoke("DATABASE_APPLIED")
        }
        require(session.state == "DATABASE_APPLIED") { "恢复阶段无效" }
        if (session.restoreSettings && manifest.settings != null)
            preferences.restoreSettings(manifest.settings, session.id)
        checkpoint?.invoke("SETTINGS_APPLIED")
        val result = requireNotNull(report).copy(settingsRestored = session.restoreSettings && manifest.settings != null,
            missingLocalTracks = findMissing(manifest))
        sessions.save(session.copy(state = "COMPLETED", reportJson = gson.toJson(result.copy(missingLocalTracks = emptyList())), errorMessage = null))
        File(payloadDirectory, "${session.id}.json").delete()
        return result
    }

    private suspend fun applyDatabase(manifest: BackupManifest): RestoreReport {
        var added = 0; var merged = 0; var newPlaylists = 0; var mergedPlaylists = 0
        val ids = mutableMapOf<String, String>()
        val now = System.currentTimeMillis()
        for (song in manifest.tracks) {
            val ref = if (song.platform != null && song.platformSongId != null) database.onlineRefDao().getByPlatformId(song.platform, song.platformSongId) else null
            val id = ref?.trackId ?: song.id
            ids[song.id] = id
            val inLibrary = song.isInLibrary ?: (!song.files.isNullOrEmpty() || song.platform == null)
            if (database.trackDao().getTrackById(id) == null) {
                database.trackDao().insertOrUpdate(TrackEntity(id, song.title, song.artist, song.album, song.durationMs, song.coverUri,
                    createdAt = song.createdAt.takeIf { it > 0 } ?: now, isInLibrary = inLibrary))
                added++
            } else {
                merged++
                if (inLibrary) database.trackDao().addToLibrary(id, song.createdAt.takeIf { it > 0 } ?: now)
            }
            if (song.platform != null && song.platformSongId != null && ref == null)
                database.onlineRefDao().insertOrUpdate(OnlineRefEntity(trackId = id, platform = song.platform, platformSongId = song.platformSongId,
                    platformMetadataJson = song.metadataJson ?: "{}"))
            if (database.localAssetDao().getAssetsForTrack(id).isEmpty()) song.files.orEmpty().forEachIndexed { index, hint ->
                val missingUri = Uri.Builder().scheme("missing").authority("backup").appendPath(id).appendPath(index.toString()).build().toString()
                database.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = id, uri = missingUri, sourceType = "BACKUP_HINT",
                    fileSize = hint.fileSize, mimeType = hint.mimeType, format = hint.format, fileHash = hint.sha256,
                    folderName = hint.folder, folderId = hint.folderId.orEmpty(), fileName = hint.fileName.orEmpty(),
                    audioInfoJson = hint.audioInfoJson, isAvailable = false, unavailableReason = "备份中的文件需要重新关联"))
            }
        }
        val details = manifest.favoriteDetails.orEmpty().associateBy { it.trackId }
        manifest.favorites.forEach { id ->
            val favorite = details[id]
            database.favoriteDao().addFavorite(FavoriteEntity(ids.getValue(id), favorite?.addedAt ?: now, favorite?.sortOrder ?: now))
        }
        for (playlist in manifest.playlists) {
            val existing = database.playlistDao().getPlaylistById(playlist.id)
            if (existing == null) {
                database.playlistDao().insertOrUpdate(PlaylistEntity(playlist.id, playlist.name, sortOrder = playlist.sortOrder,
                    createdAt = playlist.createdAt.takeIf { it > 0 } ?: now)); newPlaylists++
            } else { require(!existing.isSystem) { "备份不能替换系统歌单" }; mergedPlaylists++ }
            val members = database.playlistDao().getMembersSync(playlist.id)
            val known = members.map { it.trackId }.toMutableSet()
            var order = members.maxOfOrNull { it.sortOrder } ?: -1
            playlist.trackIds.forEach { oldId ->
                val id = ids.getValue(oldId)
                if (known.add(id)) database.playlistDao().addTrackToPlaylist(PlaylistTrackEntity(playlist.id, id, ++order,
                    playlist.addedAtByTrack?.get(oldId) ?: now))
            }
        }
        manifest.lyrics.forEach { lyric ->
            val id = ids.getValue(lyric.trackId)
            if (database.lyricDao().getLyricForTrack(id) == null)
                database.lyricDao().insertOrUpdate(LyricRecordEntity(id, lyric.sourceType ?: "BACKUP_RESTORED", lyric.content, lyric.offsetMs))
        }
        val sourceHashes = database.sourceDao().getAllSources().first().map { it.scriptHash }.toSet()
        return RestoreReport(added, merged, newPlaylists, mergedPlaylists, manifest.favorites.size, manifest.lyrics.size,
            false, emptyList(), manifest.sourceDescriptors.orEmpty().filter { it.scriptHash !in sourceHashes }.map { it.name }.take(100))
    }

    private suspend fun findMissing(manifest: BackupManifest): List<BackupTrack> {
        val refs = database.onlineRefDao().getAllRefs().first()
        val refMap = refs.associateBy { it.platform to it.platformSongId }
        val onlineIds = refs.map { it.trackId }.toSet()
        val available = database.localAssetDao().getAllAssets().first().filter { it.isAvailable }.map { it.trackId }.toSet()
        return manifest.tracks.map { it.copy(id = refMap[it.platform to it.platformSongId]?.trackId ?: it.id) }
            .filter { it.id !in available && it.id !in onlineIds }
    }
    suspend fun exportBackup(uri: Uri): Boolean = try { export(uri); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    suspend fun importBackup(uri: Uri): Boolean = try { restore(inspect(uri), false); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }

    companion object {
        private val operationLock = Mutex()
        private const val MAX_EXPANDED_BYTES = 16 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = MAX_EXPANDED_BYTES - 64
        private const val CAPACITY_MESSAGE = "备份解压后超过 16MB，请分批整理后备份"
    }
}
