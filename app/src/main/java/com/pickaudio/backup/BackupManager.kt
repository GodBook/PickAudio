package com.pickaudio.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.GsonBuilder
import com.pickaudio.data.db.*
import com.pickaudio.data.preferences.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.zip.*

data class BackupManifest(
    val version: Int = 2, val exportedAt: Long = System.currentTimeMillis(), val appVersion: String = "1.2.0",
    val playlists: List<BackupPlaylist>, val favorites: List<String>, val tracks: List<BackupTrack>,
    val lyrics: List<BackupLyric>, val sourceDescriptors: List<BackupSourceDescriptor>,
    val settings: Map<String, String>? = null
)
data class BackupPlaylist(val id: String, val name: String, val sortOrder: Int, val trackIds: List<String>)
data class BackupTrack(val id: String, val title: String, val artist: String, val album: String, val durationMs: Long, val platform: String? = null, val platformSongId: String? = null)
data class BackupLyric(val trackId: String, val offsetMs: Long, val content: String, val sourceType: String? = null)
data class BackupSourceDescriptor(val name: String, val version: String, val scriptHash: String)
data class ExportSummary(val playlists: Int, val favorites: Int, val tracks: Int, val lyrics: Int)
data class RestoreReport(val addedTracks: Int, val mergedTracks: Int, val newPlaylists: Int, val mergedPlaylists: Int,
    val favorites: Int, val lyrics: Int, val settingsRestored: Boolean, val missingLocalTracks: List<BackupTrack>, val missingSources: List<String>)

class BackupManager(private val context: Context, private val database: PickAudioDatabase) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val preferences = UserPreferences(context)
    private fun checksum(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    suspend fun export(uri: Uri): ExportSummary = withContext(Dispatchers.IO) {
        val settings = preferences.exportSettings()
        val manifest = database.withTransaction {
            val members = database.playlistDao().getAllMembers().first()
            val refs = database.onlineRefDao().getAllRefs().first().associateBy { it.trackId }
            val playlists = database.playlistDao().getAllPlaylists().first().filter { !it.isSystem }.map {
                BackupPlaylist(it.id, it.name, it.sortOrder, members.filter { member -> member.playlistId == it.id }.sortedBy { member -> member.sortOrder }.map { member -> member.trackId })
            }
            val tracks = database.trackDao().getAllTracks().first().map {
                val ref = refs[it.id]
                BackupTrack(it.id, it.title, it.artist, it.album, it.durationMs, ref?.platform, ref?.platformSongId)
            }
            val lyrics = tracks.mapNotNull { database.lyricDao().getLyricForTrack(it.id) }.map { BackupLyric(it.trackId, it.offsetMs, it.content, it.sourceType) }
            val sources = database.sourceDao().getAllSources().first().map { BackupSourceDescriptor(it.name, it.version, it.scriptHash) }
            BackupManifest(playlists = playlists, favorites = database.favoriteDao().getAllFavoriteTrackIds().first(),
                tracks = tracks, lyrics = lyrics, sourceDescriptors = sources, settings = settings)
        }
        val bytes = gson.toJson(manifest).toByteArray(Charsets.UTF_8)
        val output = context.contentResolver.openOutputStream(uri) ?: error("无法创建备份文件")
        output.use { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(bytes); zip.closeEntry()
                zip.putNextEntry(ZipEntry("manifest.sha256")); zip.write(checksum(bytes).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
        ExportSummary(manifest.playlists.size, manifest.favorites.size, manifest.tracks.size, manifest.lyrics.size)
    }

    suspend fun inspect(uri: Uri): BackupManifest = withContext(Dispatchers.IO) {
        var manifest: ByteArray? = null
        var expectedHash: String? = null
        var expanded = 0
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份文件")
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                require(entry.name in setOf("manifest.json", "manifest.sha256") && !entry.isDirectory) { "备份包含不支持的文件" }
                val bytes = zip.readNBytes(16 * 1024 * 1024 + 1)
                expanded += bytes.size
                require(expanded <= 16 * 1024 * 1024) { "备份解压后超过 16MB，请分批整理后备份" }
                if (entry.name == "manifest.json") { require(manifest == null) { "备份清单重复" }; manifest = bytes }
                else { require(expectedHash == null) { "备份校验重复" }; expectedHash = String(bytes, Charsets.UTF_8).trim() }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val bytes = manifest ?: error("备份缺少清单")
        val parsed = gson.fromJson(String(bytes, Charsets.UTF_8), BackupManifest::class.java) ?: error("备份清单无效")
        validate(parsed)
        if (parsed.version >= 2) require(expectedHash != null) { "备份缺少完整性校验" }
        expectedHash?.let { require(it == checksum(bytes)) { "备份校验失败，文件可能已损坏" } }
        parsed
    }

    fun validate(manifest: BackupManifest) {
        require(manifest.version in 1..2) { "暂不支持此备份版本" }
        val tracks = requireNotNull(manifest.tracks) { "备份缺少歌曲" }
        require(tracks.size <= 100000 && tracks.all { it.id.isNotBlank() && it.title.isNotBlank() }) { "备份歌曲清单无效" }
        val ids = tracks.map { it.id }.toSet()
        require(ids.size == tracks.size) { "备份包含重复歌曲编号" }
        requireNotNull(manifest.playlists).forEach { require(it.name.isNotBlank() && it.id.isNotBlank() && it.trackIds.all { id -> id in ids }) { "歌单引用了缺失歌曲" } }
        require(requireNotNull(manifest.favorites).all { it in ids }) { "收藏引用了缺失歌曲" }
        require(requireNotNull(manifest.lyrics).all { it.trackId in ids }) { "歌词引用了缺失歌曲" }
    }

    suspend fun restore(manifest: BackupManifest, restoreSettings: Boolean): RestoreReport = withContext(Dispatchers.IO) {
        validate(manifest)
        val previousSettings = preferences.exportSettings()
        var added = 0
        var merged = 0
        var newPlaylists = 0
        var mergedPlaylists = 0
        val mappedIds = mutableMapOf<String, String>()
        try {
            database.withTransaction {
                for (song in manifest.tracks) {
                    val existingRef = if (song.platform != null && song.platformSongId != null) database.onlineRefDao().getByPlatformId(song.platform, song.platformSongId) else null
                    val id = existingRef?.trackId ?: song.id
                    mappedIds[song.id] = id
                    if (database.trackDao().getTrackById(id) == null) {
                        database.trackDao().insertOrUpdate(TrackEntity(id, song.title, song.artist, song.album, song.durationMs, null))
                        added++
                    } else merged++
                    if (song.platform != null && song.platformSongId != null && existingRef == null)
                        database.onlineRefDao().insertOrUpdate(OnlineRefEntity(trackId = id, platform = song.platform, platformSongId = song.platformSongId, platformMetadataJson = "{}"))
                }
                manifest.favorites.forEach { database.favoriteDao().addFavorite(FavoriteEntity(mappedIds.getValue(it))) }
                for (playlist in manifest.playlists) {
                    val existing = database.playlistDao().getPlaylistById(playlist.id)
                    if (existing == null) {
                        database.playlistDao().insertOrUpdate(PlaylistEntity(playlist.id, playlist.name, sortOrder = playlist.sortOrder))
                        newPlaylists++
                    } else { require(!existing.isSystem) { "备份不能替换系统歌单" }; mergedPlaylists++ }
                    val members = database.playlistDao().getTracksForPlaylist(playlist.id).first().map { it.id }.toMutableSet()
                    var order = database.playlistDao().getMaxSortOrder(playlist.id) ?: -1
                    playlist.trackIds.map { mappedIds.getValue(it) }.forEach { id ->
                        if (members.add(id)) database.playlistDao().addTrackToPlaylist(PlaylistTrackEntity(playlist.id, id, ++order))
                    }
                }
                manifest.lyrics.forEach { lyric ->
                    val id = mappedIds.getValue(lyric.trackId)
                    if (database.lyricDao().getLyricForTrack(id) == null)
                        database.lyricDao().insertOrUpdate(LyricRecordEntity(id, lyric.sourceType ?: "BACKUP_RESTORED", lyric.content, lyric.offsetMs))
                }
                if (restoreSettings && manifest.settings != null) preferences.restoreSettings(manifest.settings)
            }
        } catch (e: Exception) {
            if (restoreSettings) withContext(NonCancellable) { preferences.restoreSettings(previousSettings) }
            throw e
        }
        val missing = manifest.tracks.filter { song ->
            val id = mappedIds.getValue(song.id)
            database.localAssetDao().getAssetsForTrack(id).none { it.isAvailable } && database.onlineRefDao().getByTrackId(id) == null
        }.map { it.copy(id = mappedIds.getValue(it.id)) }
        val sourceHashes = database.sourceDao().getAllSources().first().map { it.scriptHash }.toSet()
        RestoreReport(added, merged, newPlaylists, mergedPlaylists, manifest.favorites.size, manifest.lyrics.size,
            restoreSettings && manifest.settings != null, missing, manifest.sourceDescriptors.orEmpty().filter { it.scriptHash !in sourceHashes }.map { it.name })
    }

    suspend fun exportBackup(uri: Uri): Boolean = runCatching { export(uri) }.isSuccess
    suspend fun importBackup(uri: Uri): Boolean = runCatching { restore(inspect(uri), false) }.isSuccess
}
