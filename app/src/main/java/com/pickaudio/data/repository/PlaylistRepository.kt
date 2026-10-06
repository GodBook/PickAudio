package com.pickaudio.data.repository

import androidx.room.withTransaction
import com.pickaudio.data.db.*
import com.pickaudio.data.model.Track
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import java.util.UUID

class PlaylistRepository(private val database: PickAudioDatabase) {
    private val playlistDao = database.playlistDao()
    private val favoriteDao = database.favoriteDao()
    private val trackDao = database.trackDao()

    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()
    private fun allTracks(): Flow<List<Track>> = combine(
        trackDao.getAllTracks(), database.localAssetDao().getAllAssets(), database.onlineRefDao().getAllRefs(), favoriteDao.getAllFavoriteTrackIds()
    ) { tracks, assets, refs, favorites ->
        val assetMap = assets.groupBy { it.trackId }
        val refMap = refs.groupBy { it.trackId }
        val favSet = favorites.toSet()
        tracks.map { it.toTrack(assetMap[it.id].orEmpty(), refMap[it.id].orEmpty(), it.id in favSet) }
    }.flowOn(Dispatchers.Default)

    fun getFavoriteTracks(): Flow<List<Track>> = combine(allTracks(), favoriteDao.getFavoriteTracks()) { tracks, favorites ->
        val lookup = tracks.associateBy { it.id }
        favorites.mapNotNull { lookup[it.id] }
    }

    fun getTracksForPlaylist(id: String): Flow<List<Track>> {
        if (id == PickAudioDatabase.FAVORITE_PLAYLIST_ID) return getFavoriteTracks()
        return combine(allTracks(), playlistDao.getTracksForPlaylist(id)) { tracks, members ->
            val lookup = tracks.associateBy { it.id }
            members.mapNotNull { lookup[it.id] }
        }
    }

    suspend fun createPlaylist(name: String): String {
        require(name.isNotBlank()) { "请输入歌单名称" }
        val id = UUID.randomUUID().toString()
        val order = (getAllPlaylists().first().maxOfOrNull { it.sortOrder } ?: 0) + 1
        playlistDao.insertOrUpdate(PlaylistEntity(id = id, name = name.trim(), sortOrder = order))
        return id
    }

    suspend fun renamePlaylist(id: String, name: String) {
        require(name.isNotBlank()) { "请输入歌单名称" }
        val playlist = playlistDao.getPlaylistById(id) ?: return
        if (!playlist.isSystem) playlistDao.insertOrUpdate(playlist.copy(name = name.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun createAndAddTracks(name: String, tracks: List<Track>): String = database.withTransaction {
        val id = createPlaylist(name)
        addTracks(id, tracks)
        id
    }

    suspend fun deletePlaylist(id: String) = playlistDao.deletePlaylist(id)

    private suspend fun ensureTrack(track: Track) {
        if (trackDao.getTrackById(track.id) == null) trackDao.insertOrUpdate(TrackEntity(
            track.id, track.title, track.artist, track.album, track.durationMs, track.coverUri, track.trackNumber
        ))
        val platform = track.platform ?: if (track.id.startsWith("online_wy_")) "wy" else if (track.id.startsWith("online_tx_")) "tx" else null
        val songId = track.platformSongId ?: platform?.let { track.id.removePrefix("online_${it}_") }
        if (platform != null && !songId.isNullOrEmpty() && database.onlineRefDao().getByTrackId(track.id) == null)
            database.onlineRefDao().insertOrUpdate(OnlineRefEntity(trackId = track.id, platform = platform, platformSongId = songId, platformMetadataJson = "{}"))
    }

    suspend fun ensureTrackAndAddToPlaylist(playlistId: String, track: Track) = database.withTransaction {
        ensureTrack(track)
        addTrackToPlaylist(playlistId, track.id)
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        if (playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID) {
            favoriteDao.addFavorite(FavoriteEntity(trackId))
            return
        }
        if (playlistDao.getTracksForPlaylist(playlistId).first().any { it.id == trackId }) return
        val order = (playlistDao.getMaxSortOrder(playlistId) ?: -1) + 1
        playlistDao.addTrackToPlaylist(PlaylistTrackEntity(playlistId, trackId, order))
    }

    suspend fun addTracks(playlistId: String, tracks: List<Track>) = database.withTransaction {
        tracks.distinctBy { it.id }.forEach { ensureTrackAndAddToPlaylist(playlistId, it) }
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, id: String) {
        if (playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID) favoriteDao.removeFavorite(id)
        else playlistDao.removeTrackFromPlaylist(playlistId, id)
    }

    suspend fun removeTracks(playlistId: String, ids: List<String>) = database.withTransaction {
        ids.forEach { removeTrackFromPlaylist(playlistId, it) }
    }

    suspend fun reorderTracks(playlistId: String, ids: List<String>) = database.withTransaction {
        ids.forEachIndexed { index, id ->
            if (playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID) favoriteDao.updateOrder(id, System.currentTimeMillis() - index)
            else playlistDao.updateTrackOrder(playlistId, id, index)
        }
    }

    suspend fun reorderPlaylists(ids: List<String>) = database.withTransaction {
        ids.forEachIndexed { index, id -> playlistDao.updatePlaylistOrder(id, index + 1) }
    }

    suspend fun toggleFavorite(id: String): Boolean {
        if (trackDao.getTrackById(id) == null) return false
        val favorite = favoriteDao.isFavoriteSync(id)
        if (favorite) favoriteDao.removeFavorite(id) else favoriteDao.addFavorite(FavoriteEntity(id))
        return !favorite
    }

    fun isFavoriteFlow(id: String): Flow<Boolean> = favoriteDao.isFavorite(id)

    fun getPlaylistSummaries(): Flow<Map<String, Pair<Int, String?>>> = combine(
        playlistDao.getAllPlaylists(), playlistDao.getAllMembers(), allTracks()
    ) { playlists, members, tracks ->
        val lookup = tracks.associateBy { it.id }
        playlists.associate { playlist ->
            val songs = if (playlist.isSystem) tracks.filter { it.isFavorite }
                else members.filter { it.playlistId == playlist.id }.mapNotNull { lookup[it.trackId] }
            playlist.id to (songs.size to songs.firstNotNullOfOrNull { it.coverUri })
        }
    }
}
