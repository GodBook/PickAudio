package com.pickaudio.backup

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.pickaudio.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupManifest(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String = "1.0.0",
    val playlists: List<BackupPlaylist>,
    val favorites: List<String>, // list of trackIds
    val tracks: List<BackupTrack>,
    val lyrics: List<BackupLyric>,
    val sourceDescriptors: List<BackupSourceDescriptor>
)

data class BackupPlaylist(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val trackIds: List<String>
)

data class BackupTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val platform: String? = null,
    val platformSongId: String? = null
)

data class BackupLyric(
    val trackId: String,
    val offsetMs: Long,
    val content: String
)

data class BackupSourceDescriptor(
    val name: String,
    val version: String,
    val scriptHash: String
)

class BackupManager(
    private val context: Context,
    private val database: PickAudioDatabase
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun exportBackup(targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val trackDao = database.trackDao()
            val playlistDao = database.playlistDao()
            val favoriteDao = database.favoriteDao()
            val lyricDao = database.lyricDao()
            val sourceDao = database.sourceDao()
            val onlineRefDao = database.onlineRefDao()

            val allPlaylists = database.playlistDao().getAllPlaylists()
            // Collect snapshot
            val rawPlaylists = database.runInTransaction<List<BackupPlaylist>> {
                // SQLite raw query or sync DAO
                val list = mutableListOf<BackupPlaylist>()
                val cursor = database.query("SELECT * FROM playlists WHERE isSystem = 0", null)
                while (cursor.moveToNext()) {
                    val pId = cursor.getString(cursor.getColumnIndexOrThrow("id"))
                    val pName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    val pOrder = cursor.getInt(cursor.getColumnIndexOrThrow("sortOrder"))

                    val tCursor = database.query("SELECT trackId FROM playlist_tracks WHERE playlistId = '$pId' ORDER BY sortOrder ASC", null)
                    val tIds = mutableListOf<String>()
                    while (tCursor.moveToNext()) {
                        tIds.add(tCursor.getString(0))
                    }
                    tCursor.close()
                    list.add(BackupPlaylist(pId, pName, pOrder, tIds))
                }
                cursor.close()
                list
            }

            val rawFavorites = mutableListOf<String>()
            val favCursor = database.query("SELECT trackId FROM favorites", null)
            while (favCursor.moveToNext()) {
                rawFavorites.add(favCursor.getString(0))
            }
            favCursor.close()

            val rawTracks = mutableListOf<BackupTrack>()
            val trackCursor = database.query("SELECT id, title, artist, album, durationMs FROM tracks", null)
            while (trackCursor.moveToNext()) {
                val tId = trackCursor.getString(0)
                val tTitle = trackCursor.getString(1)
                val tArtist = trackCursor.getString(2)
                val tAlbum = trackCursor.getString(3)
                val tDur = trackCursor.getLong(4)

                val ref = onlineRefDao.getByTrackId(tId)
                rawTracks.add(
                    BackupTrack(
                        id = tId,
                        title = tTitle,
                        artist = tArtist,
                        album = tAlbum,
                        durationMs = tDur,
                        platform = ref?.platform,
                        platformSongId = ref?.platformSongId
                    )
                )
            }
            trackCursor.close()

            val rawLyrics = mutableListOf<BackupLyric>()
            val lrcCursor = database.query("SELECT trackId, offsetMs, content FROM lyric_records", null)
            while (lrcCursor.moveToNext()) {
                rawLyrics.add(
                    BackupLyric(
                        trackId = lrcCursor.getString(0),
                        offsetMs = lrcCursor.getLong(1),
                        content = lrcCursor.getString(2)
                    )
                )
            }
            lrcCursor.close()

            val rawSources = mutableListOf<BackupSourceDescriptor>()
            val srcCursor = database.query("SELECT name, version, scriptHash FROM source_scripts", null)
            while (srcCursor.moveToNext()) {
                rawSources.add(
                    BackupSourceDescriptor(
                        name = srcCursor.getString(0),
                        version = srcCursor.getString(1),
                        scriptHash = srcCursor.getString(2)
                    )
                )
            }
            srcCursor.close()

            val manifest = BackupManifest(
                playlists = rawPlaylists,
                favorites = rawFavorites,
                tracks = rawTracks,
                lyrics = rawLyrics,
                sourceDescriptors = rawSources
            )

            val manifestJson = gson.toJson(manifest)

            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                ZipOutputStream(BufferedOutputStream(out)).use { zos ->
                    val entry = ZipEntry("manifest.json")
                    zos.putNextEntry(entry)
                    zos.write(manifestJson.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun importBackup(sourceUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            var manifestJson: String? = null
            context.contentResolver.openInputStream(sourceUri)?.use { inStream ->
                ZipInputStream(BufferedInputStream(inStream)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "manifest.json") {
                            manifestJson = zis.bufferedReader(Charsets.UTF_8).readText()
                            break
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (manifestJson.isNullOrEmpty()) return@withContext false
            val manifest = gson.fromJson(manifestJson, BackupManifest::class.java) ?: return@withContext false

            val trackDao = database.trackDao()
            val playlistDao = database.playlistDao()
            val favoriteDao = database.favoriteDao()
            val lyricDao = database.lyricDao()
            val onlineRefDao = database.onlineRefDao()

            database.withTransaction {
                // 1. Restore tracks
                for (bt in manifest.tracks) {
                    val existing = trackDao.getTrackById(bt.id)
                    if (existing == null) {
                        trackDao.insertOrUpdate(
                            TrackEntity(
                                id = bt.id,
                                title = bt.title,
                                artist = bt.artist,
                                album = bt.album,
                                durationMs = bt.durationMs,
                                coverUri = null
                            )
                        )
                    }
                    if (bt.platform != null && bt.platformSongId != null) {
                        val ref = onlineRefDao.getByPlatformId(bt.platform, bt.platformSongId)
                        if (ref == null) {
                            onlineRefDao.insertOrUpdate(
                                OnlineRefEntity(
                                    trackId = bt.id,
                                    platform = bt.platform,
                                    platformSongId = bt.platformSongId,
                                    platformMetadataJson = "{}"
                                )
                            )
                        }
                    }
                }

                // 2. Restore favorites
                for (fid in manifest.favorites) {
                    favoriteDao.addFavorite(FavoriteEntity(trackId = fid))
                }

                // 3. Restore Playlists
                for (bp in manifest.playlists) {
                    val existing = playlistDao.getPlaylistById(bp.id)
                    val finalId = if (existing == null) bp.id else UUID.randomUUID().toString()
                    val finalName = if (existing == null) bp.name else "${bp.name} (恢复)"

                    playlistDao.insertOrUpdate(
                        PlaylistEntity(
                            id = finalId,
                            name = finalName,
                            sortOrder = bp.sortOrder,
                            isSystem = false
                        )
                    )
                    bp.trackIds.forEachIndexed { index, tId ->
                        playlistDao.addTrackToPlaylist(
                            PlaylistTrackEntity(
                                playlistId = finalId,
                                trackId = tId,
                                sortOrder = index
                            )
                        )
                    }
                }

                // 4. Restore Lyrics
                for (bl in manifest.lyrics) {
                    lyricDao.insertOrUpdate(
                        LyricRecordEntity(
                            trackId = bl.trackId,
                            sourceType = "BACKUP_RESTORED",
                            content = bl.content,
                            offsetMs = bl.offsetMs
                        )
                    )
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
