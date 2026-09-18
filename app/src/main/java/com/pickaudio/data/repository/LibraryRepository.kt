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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LibraryRepository(
    private val context: Context,
    private val database: PickAudioDatabase
) {
    private val trackDao = database.trackDao()
    private val localAssetDao = database.localAssetDao()
    private val favoriteDao = database.favoriteDao()
    private val importRootDao = database.importRootDao()

    fun getAllTracks(): Flow<List<Track>> {
        return combine(trackDao.getAllTracks(), favoriteDao.getAllFavoriteTrackIds()) { list, favIds ->
            val favSet = favIds.toSet()
            list.map { entity ->
                val assets = localAssetDao.getAssetsForTrack(entity.id)
                val localUri = assets.firstOrNull { it.isAvailable }?.uri
                val platform = if (entity.id.startsWith("online_wy_")) "wy" else if (entity.id.startsWith("online_tx_")) "tx" else null
                val platformSongId = if (entity.id.startsWith("online_wy_") || entity.id.startsWith("online_tx_")) entity.id.removePrefix("online_wy_").removePrefix("online_tx_") else null
                Track(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    album = entity.album,
                    durationMs = entity.durationMs,
                    coverUri = entity.coverUri,
                    trackNumber = entity.trackNumber,
                    localUri = localUri,
                    isAvailable = localUri != null,
                    isFavorite = favSet.contains(entity.id),
                    platform = platform,
                    platformSongId = platformSongId
                )
            }
        }
    }

    fun searchTracks(query: String): Flow<List<Track>> {
        return combine(trackDao.searchTracks(query), favoriteDao.getAllFavoriteTrackIds()) { list, favIds ->
            val favSet = favIds.toSet()
            list.map { entity ->
                val assets = localAssetDao.getAssetsForTrack(entity.id)
                val localUri = assets.firstOrNull { it.isAvailable }?.uri
                val platform = if (entity.id.startsWith("online_wy_")) "wy" else if (entity.id.startsWith("online_tx_")) "tx" else null
                val platformSongId = if (entity.id.startsWith("online_wy_") || entity.id.startsWith("online_tx_")) entity.id.removePrefix("online_wy_").removePrefix("online_tx_") else null
                Track(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    album = entity.album,
                    durationMs = entity.durationMs,
                    coverUri = entity.coverUri,
                    trackNumber = entity.trackNumber,
                    localUri = localUri,
                    isAvailable = localUri != null,
                    isFavorite = favSet.contains(entity.id),
                    platform = platform,
                    platformSongId = platformSongId
                )
            }
        }
    }

    suspend fun scanMediaStore(filterShortAudio: Boolean): Int = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.ALBUM_ID
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
        context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
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
                val existingAsset = localAssetDao.getAssetByUri(contentUri)
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
                        isAvailable = true
                    )
                    localAssetDao.insertOrUpdate(asset)
                    importedCount++
                } else {
                    // Update availability
                    localAssetDao.updateAvailability(existingAsset.id, true)
                }
            }
        }
        importedCount
    }

    suspend fun importSafFile(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                // Ignore if not persistable
            }

            val uriStr = uri.toString()
            val existing = localAssetDao.getAssetByUri(uriStr)
            if (existing != null) {
                localAssetDao.updateAvailability(existing.id, true)
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
                isAvailable = true
            )
            localAssetDao.insertOrUpdate(asset)
            true
        } catch (e: Exception) {
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
            if (df.isDirectory) {
                df.listFiles().forEach { child -> scanDocFile(child) }
            } else if (df.isFile) {
                val name = df.name?.lowercase() ?: ""
                if (name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".m4a") ||
                    name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".aac")) {
                    if (importSafFile(df.uri)) {
                        count++
                    }
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
