package com.pickaudio.data.repository

import com.pickaudio.data.db.*
import com.pickaudio.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
                    isFavorite = true,
                    platform = platform,
                    platformSongId = platformSongId
                )
            }
        }
    }

    fun getTracksForPlaylist(playlistId: String): Flow<List<Track>> {
        if (playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID) {
            return getFavoriteTracks()
        }
        return combine(playlistDao.getTracksForPlaylist(playlistId), favoriteDao.getAllFavoriteTrackIds()) { list, favIds ->
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

    suspend fun ensureTrackAndAddToPlaylist(playlistId: String, track: Track) {
        if (trackDao.getTrackById(track.id) == null) {
            trackDao.insertOrUpdate(
                TrackEntity(
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    coverUri = track.coverUri,
                    trackNumber = track.trackNumber
                )
            )
        }
        val platform = track.platform ?: if (track.id.startsWith("online_wy_")) "wy" else if (track.id.startsWith("online_tx_")) "tx" else null
        val songId = track.platformSongId ?: track.id.removePrefix("online_wy_").removePrefix("online_tx_")
        if (platform != null && songId.isNotEmpty()) {
            if (database.onlineRefDao().getByTrackId(track.id) == null) {
                database.onlineRefDao().insertOrUpdate(
                    OnlineRefEntity(
                        trackId = track.id,
                        platform = platform,
                        platformSongId = songId,
                        platformMetadataJson = "{}"
                    )
                )
            }
        }
        addTrackToPlaylist(playlistId, track.id)
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        if (playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID) {
            favoriteDao.addFavorite(FavoriteEntity(trackId = trackId))
            return
        }
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
        if (playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID) {
            favoriteDao.removeFavorite(trackId)
            return
        }
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    suspend fun toggleFavorite(trackId: String): Boolean {
        if (trackDao.getTrackById(trackId) == null) {
            return false
        }
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
