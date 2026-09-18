package com.pickaudio.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY createdAt DESC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%'")
    fun searchTracks(query: String): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(tracks: List<TrackEntity>)

    @Delete
    suspend fun delete(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteById(trackId: String)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getTrackCount(): Int
}

@Dao
interface LocalAssetDao {
    @Query("SELECT * FROM local_assets WHERE trackId = :trackId")
    suspend fun getAssetsForTrack(trackId: String): List<LocalAssetEntity>

    @Query("SELECT * FROM local_assets WHERE uri = :uri LIMIT 1")
    suspend fun getAssetByUri(uri: String): LocalAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(asset: LocalAssetEntity): Long

    @Query("UPDATE local_assets SET isAvailable = :available WHERE id = :id")
    suspend fun updateAvailability(id: Long, available: Boolean)

    @Query("DELETE FROM local_assets WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("DELETE FROM local_assets WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)
}

@Dao
interface OnlineRefDao {
    @Query("SELECT * FROM online_refs WHERE platform = :platform AND platformSongId = :songId LIMIT 1")
    suspend fun getByPlatformId(platform: String, songId: String): OnlineRefEntity?

    @Query("SELECT * FROM online_refs WHERE trackId = :trackId LIMIT 1")
    suspend fun getByTrackId(trackId: String): OnlineRefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(ref: OnlineRefEntity): Long

    @Query("DELETE FROM online_refs WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY sortOrder ASC, createdAt ASC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id AND isSystem = 0")
    suspend fun deletePlaylist(id: String)

    @Query("SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt ON t.id = pt.trackId WHERE pt.playlistId = :playlistId ORDER BY pt.sortOrder ASC")
    fun getTracksForPlaylist(playlistId: String): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrackToPlaylist(playlistTrack: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String)

    @Query("DELETE FROM playlist_tracks WHERE trackId = :trackId")
    suspend fun removeTrackFromAllPlaylists(trackId: String)

    @Query("SELECT MAX(sortOrder) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getMaxSortOrder(playlistId: String): Int?

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT t.* FROM tracks t INNER JOIN favorites f ON t.id = f.trackId ORDER BY f.addedAt DESC")
    fun getFavoriteTracks(): Flow<List<TrackEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId = :trackId)")
    fun isFavorite(trackId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId = :trackId)")
    suspend fun isFavoriteSync(trackId: String): Boolean

    @Query("SELECT trackId FROM favorites")
    fun getAllFavoriteTrackIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(fav: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE trackId = :trackId")
    suspend fun removeFavorite(trackId: String)
}

@Dao
interface QueueDao {
    @Query("SELECT t.* FROM tracks t INNER JOIN queue_entries q ON t.id = q.trackId ORDER BY q.queueOrder ASC")
    fun getQueueTracks(): Flow<List<TrackEntity>>

    @Query("SELECT t.* FROM tracks t INNER JOIN queue_entries q ON t.id = q.trackId ORDER BY q.queueOrder ASC")
    suspend fun getQueueTracksSync(): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueueEntries(entries: List<QueueEntryEntity>)

    @Query("DELETE FROM queue_entries")
    suspend fun clearQueue()

    @Query("DELETE FROM queue_entries WHERE trackId = :trackId")
    suspend fun removeTrackFromQueue(trackId: String)
}

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback_snapshot WHERE id = 1 LIMIT 1")
    suspend fun getSnapshot(): PlaybackSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSnapshot(snapshot: PlaybackSnapshotEntity)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM source_scripts ORDER BY createdAt DESC")
    fun getAllSources(): Flow<List<SourceScriptEntity>>

    @Query("SELECT * FROM source_scripts WHERE id = :id LIMIT 1")
    suspend fun getSourceById(id: String): SourceScriptEntity?

    @Query("SELECT * FROM source_scripts WHERE scriptHash = :hash LIMIT 1")
    suspend fun getSourceByHash(hash: String): SourceScriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(script: SourceScriptEntity)

    @Delete
    suspend fun delete(script: SourceScriptEntity)

    @Query("SELECT * FROM platform_source_selection")
    fun getPlatformSelections(): Flow<List<PlatformSourceSelectionEntity>>

    @Query("SELECT * FROM platform_source_selection WHERE platform = :platform LIMIT 1")
    suspend fun getSelectionForPlatform(platform: String): PlatformSourceSelectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPlatformSelection(selection: PlatformSourceSelectionEntity)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE platform = :platform AND platformSongId = :songId AND targetQuality = :quality LIMIT 1")
    suspend fun getTaskByPlatformSong(platform: String, songId: String, quality: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("DELETE FROM download_tasks WHERE trackId = :trackId")
    suspend fun deleteTasksByTrackId(trackId: String)

    @Query("UPDATE download_tasks SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String? = null)

    @Query("UPDATE download_tasks SET downloadedBytes = :progress, totalBytes = :total WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Long, total: Long)
}

@Dao
interface LyricDao {
    @Query("SELECT * FROM lyric_records WHERE trackId = :trackId LIMIT 1")
    suspend fun getLyricForTrack(trackId: String): LyricRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(lyric: LyricRecordEntity)

    @Query("UPDATE lyric_records SET offsetMs = :offsetMs, updatedAt = :time WHERE trackId = :trackId")
    suspend fun updateOffset(trackId: String, offsetMs: Long, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM lyric_records WHERE trackId = :trackId")
    suspend fun deleteLyric(trackId: String)
}

@Dao
interface ImportRootDao {
    @Query("SELECT * FROM import_roots")
    suspend fun getAllRoots(): List<ImportRootEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(root: ImportRootEntity)

    @Query("DELETE FROM import_roots WHERE uri = :uri")
    suspend fun delete(uri: String)
}
