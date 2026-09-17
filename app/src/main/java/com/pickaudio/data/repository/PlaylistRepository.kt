package com.pickaudio.data.repository

import com.pickaudio.data.db.*
import com.pickaudio.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class PlaylistRepository(private val database: PickAudioDatabase) {
    private val playlistDao = database.playlistDao()
    private val favoriteDao = database.favoriteDao()
    private val localAssetDao = database.localAssetDao()
    private val trackDao = database.trackDao()

    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()

    fun getFavoriteTracks(): Flow<List<Track>> {
        return favoriteDao.getFavoriteTracks().map { list ->
            list.map { entity ->
                val assets = localAssetDao.getAssetsForTrack(entity.id)
                val localUri = assets.firstOrNull { it.isAvailable }?.uri
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
                    isFavorite = true
                )
            }
        }
    }

    fun getTracksForPlaylist(playlistId: String): Flow<List<Track>> {
        if (playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID) {
            return getFavoriteTracks()
        }
        return playlistDao.getTracksForPlaylist(playlistId).map { list ->
            list.map { entity ->
                val isFav = favoriteDao.isFavoriteSync(entity.id)
                val assets = localAssetDao.getAssetsForTrack(entity.id)
                val localUri = assets.firstOrNull { it.isAvailable }?.uri
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
                    isFavorite = isFav
                )
            }
        }
    }

    suspend fun createPlaylist(name: String): String {
        val id = UUID.randomUUID().toString()
        playlistDao.insertOrUpdate(
            PlaylistEntity(
                id = id,
                name = name.trim(),
                isSystem = false
            )
        )
        return id
    }

    suspend fun renamePlaylist(playlistId: String, newName: String) {
        val p = playlistDao.getPlaylistById(playlistId)
        if (p != null && !p.isSystem) {
            playlistDao.insertOrUpdate(p.copy(name = newName.trim(), updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        val maxOrder = playlistDao.getMaxSortOrder(playlistId) ?: -1
        playlistDao.addTrackToPlaylist(
            PlaylistTrackEntity(
                playlistId = playlistId,
                trackId = trackId,
                sortOrder = maxOrder + 1
            )
        )
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    suspend fun toggleFavorite(trackId: String): Boolean {
        val isFav = favoriteDao.isFavoriteSync(trackId)
        return if (isFav) {
            favoriteDao.removeFavorite(trackId)
            false
        } else {
            favoriteDao.addFavorite(FavoriteEntity(trackId = trackId))
            true
        }
    }

    fun isFavoriteFlow(trackId: String): Flow<Boolean> = favoriteDao.isFavorite(trackId)
}
