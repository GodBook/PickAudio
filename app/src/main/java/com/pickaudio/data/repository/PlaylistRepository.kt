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

    private val playlistsFlow = playlistDao.getAllPlaylists()
    val favoriteIds = favoriteDao.getAllFavoriteTrackIds().distinctUntilChanged()
    val onlineIds = database.onlineRefDao().getAllRefs().map { refs -> refs.associate { (it.platform to it.platformSongId) to it.trackId } }.distinctUntilChanged()
    private val favoritesFlow = favoriteDao.getTrackDetails().map { details ->
        details.map { it.track.toTrack(it.assets, it.refs, true) }
    }.flowOn(Dispatchers.Default)
    private val summariesFlow = playlistDao.getSummaries().map { rows ->
        rows.associate { it.id to (it.songCount to it.coverUri) }
    }
    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistsFlow
    fun getFavoriteTracks(): Flow<List<Track>> = favoritesFlow
    fun getTracksForPlaylist(id: String): Flow<List<Track>> =
        if (id == PickAudioDatabase.FAVORITE_PLAYLIST_ID) favoritesFlow else
            playlistDao.getTrackDetails(id).map { details ->
                details.map { it.track.toTrack(it.assets, it.refs, it.favorites.isNotEmpty()) }
            }.flowOn(Dispatchers.Default)

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
    suspend fun createAndAddWithReport(name: String, tracks: List<Track>): Pair<String, AddTracksReport> = database.withTransaction {
        val id = createPlaylist(name)
        id to addTracks(id, tracks)
    }

    suspend fun deletePlaylist(id: String) = playlistDao.deletePlaylist(id)

    private suspend fun ensureTrack(track: Track): String {
        return database.ensureTrackIdentity(track).id
    }

    suspend fun ensureTrackAndAddToPlaylist(playlistId: String, track: Track) = database.withTransaction {
        addTrackToPlaylist(playlistId, ensureTrack(track))
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
        val favorite = playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID
        require(favorite || playlistDao.getPlaylistById(playlistId) != null) { "歌单已移除" }
        val members = if (favorite) emptyList() else playlistDao.getMembersSync(playlistId)
        val known = if (favorite) favoriteDao.getFavoritesSync().map { it.trackId }.toMutableSet() else members.map { it.trackId }.toMutableSet()
        var order = members.maxOfOrNull { it.sortOrder } ?: -1
        var added = 0; var existing = 0
        val unique = tracks.distinctBy { it.id }
        unique.forEach { track ->
            val id = ensureTrack(track)
            if (!known.add(id)) existing++
            else {
                if (favorite) favoriteDao.addFavorite(FavoriteEntity(id))
                else playlistDao.addTrackToPlaylist(PlaylistTrackEntity(playlistId, id, ++order))
                added++
            }
        }
        AddTracksReport(added, existing, tracks.size - unique.size)
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, id: String) {
        if (playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID) favoriteDao.removeFavorite(id)
        else playlistDao.removeTrackFromPlaylist(playlistId, id)
    }

    suspend fun removeTracks(playlistId: String, ids: List<String>) = database.withTransaction {
        ids.forEach { removeTrackFromPlaylist(playlistId, it) }
    }
    suspend fun removeWithUndo(playlistId: String, ids: List<String>): RemovedTracks = database.withTransaction {
        val selected = ids.toSet()
        val token = if (playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID) {
            val rows = favoriteDao.getFavoritesSync()
            RemovedTracks(playlistId, rows.map { it.trackId }, favorites = rows.filter { it.trackId in selected })
        } else {
            val rows = playlistDao.getMembersSync(playlistId)
            RemovedTracks(playlistId, rows.map { it.trackId }, members = rows.filter { it.trackId in selected })
        }
        removeTracks(playlistId, ids)
        token
    }
    suspend fun restoreRemoved(token: RemovedTracks): Int = database.withTransaction {
        val favorite = token.playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID
        require(favorite || playlistDao.getPlaylistById(token.playlistId) != null) { "歌单已移除，无法撤销" }
        val current = if (favorite) favoriteDao.getFavoritesSync().map { it.trackId } else playlistDao.getMembersSync(token.playlistId).map { it.trackId }
        val candidates = (token.members.map { it.trackId } + token.favorites.map { it.trackId }).toSet() - current.toSet()
        val valid = candidates.chunked(900).flatMap { trackDao.getTracksByIds(it) }.map { it.id }.toSet()
        token.members.filter { it.trackId in valid }.forEach { playlistDao.addTrackToPlaylist(it) }
        token.favorites.filter { it.trackId in valid }.forEach { favoriteDao.addFavorite(it) }
        reorderTracks(token.playlistId, restoreMemberOrder(current, token.originalOrder, valid))
        valid.size
    }

    suspend fun reorderTracks(playlistId: String, ids: List<String>, expectedOrder: List<String>? = null) = database.withTransaction {
        val current = if (playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID) favoriteDao.getFavoritesSync().map { it.trackId }
            else playlistDao.getMembersSync(playlistId).map { it.trackId }
        require(expectedOrder == null || current == expectedOrder) { "歌单已发生变化，请重新排序" }
        require(ids.distinct().size == ids.size && ids.toSet() == current.toSet()) { "歌单成员已发生变化，请重新排序" }
        val base = System.currentTimeMillis()
        ids.forEachIndexed { index, id ->
            if (playlistId == PickAudioDatabase.FAVORITE_PLAYLIST_ID) favoriteDao.updateOrder(id, base - index)
            else playlistDao.updateTrackOrder(playlistId, id, index)
        }
    }

    suspend fun reorderPlaylists(ids: List<String>, expectedOrder: List<String>? = null) = database.withTransaction {
        val current = playlistDao.getAllPlaylists().first().filter { !it.isSystem }.map { it.id }
        require(expectedOrder == null || current == expectedOrder) { "歌单已发生变化，请重新排序" }
        require(ids.distinct().size == ids.size && ids.toSet() == current.toSet()) { "歌单已发生变化，请重新排序" }
        ids.forEachIndexed { index, id -> playlistDao.updatePlaylistOrder(id, index + 1) }
    }

    suspend fun toggleFavorite(id: String): Boolean {
        if (trackDao.getTrackById(id) == null) return false
        val favorite = favoriteDao.isFavoriteSync(id)
        if (favorite) favoriteDao.removeFavorite(id) else favoriteDao.addFavorite(FavoriteEntity(id))
        return !favorite
    }

    fun isFavoriteFlow(id: String): Flow<Boolean> = favoriteDao.isFavorite(id)

    fun getPlaylistSummaries(): Flow<Map<String, Pair<Int, String?>>> = summariesFlow
}
