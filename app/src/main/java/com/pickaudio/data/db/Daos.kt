package com.pickaudio.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE isInLibrary = 1 ORDER BY createdAt DESC")
    fun getLibraryTracks(): Flow<List<TrackEntity>>

    @Query("SELECT id FROM tracks WHERE isInLibrary = 1")
    fun getLibraryTrackIds(): Flow<List<String>>

    @Query("UPDATE tracks SET isInLibrary = 1, createdAt = :addedAt WHERE id = :id AND isInLibrary = 0")
    suspend fun addToLibrary(id: String, addedAt: Long): Int

    @Query("UPDATE tracks SET isInLibrary = 0 WHERE id IN (:ids)")
    suspend fun removeFromLibrary(ids: List<String>)

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getTracksByIds(ids: List<String>): List<TrackEntity>
    @Query("SELECT * FROM tracks ORDER BY createdAt DESC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%'")
    fun searchTracks(query: String): Flow<List<TrackEntity>>

    @Upsert
    suspend fun insertOrUpdate(track: TrackEntity)

    @Upsert
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
    @Query("SELECT * FROM local_assets WHERE unavailableReason = '删除待确认'")
    suspend fun getPendingDeletions(): List<LocalAssetEntity>
    @Query("UPDATE local_assets SET audioInfoJson = :info WHERE id = :id")
    suspend fun updateAudioInfo(id: Long, info: String)
    @Query("SELECT * FROM local_assets WHERE trackId IN (:ids)")
    suspend fun getAssetsForTracks(ids: List<String>): List<LocalAssetEntity>
    @Query("SELECT * FROM local_assets")
    fun getAllAssets(): Flow<List<LocalAssetEntity>>
    @Query("SELECT * FROM local_assets WHERE trackId = :trackId")
    suspend fun getAssetsForTrack(trackId: String): List<LocalAssetEntity>

    @Query("SELECT * FROM local_assets WHERE uri = :uri LIMIT 1")
    suspend fun getAssetByUri(uri: String): LocalAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(asset: LocalAssetEntity): Long

    @Query("UPDATE local_assets SET isAvailable = :available, unavailableReason = CASE WHEN :available THEN NULL ELSE :reason END WHERE id = :id AND (COALESCE(unavailableReason, '') != '删除待确认' OR :reason = '删除待确认')")
    suspend fun updateAvailability(id: Long, available: Boolean, reason: String? = null)
    @Query("UPDATE local_assets SET isAvailable = :available, unavailableReason = CASE WHEN :available THEN NULL ELSE :reason END WHERE id = :id AND unavailableReason = '删除待确认'")
    suspend fun finishDeletion(id: Long, available: Boolean, reason: String? = null)

    @Query("DELETE FROM local_assets WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("DELETE FROM local_assets WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)
}

@Dao
interface OnlineRefDao {
    @Query("SELECT * FROM online_refs WHERE trackId IN (:ids)")
    suspend fun getRefsForTracks(ids: List<String>): List<OnlineRefEntity>
    @Query("SELECT * FROM online_refs")
    fun getAllRefs(): Flow<List<OnlineRefEntity>>
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
    @Transaction
    @Query("SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt ON t.id = pt.trackId WHERE pt.playlistId = :id ORDER BY pt.sortOrder")
    fun getTrackDetails(id: String): Flow<List<TrackDetails>>
    @Query("""SELECT p.id,
        CASE WHEN p.id = 'favorite' THEN (SELECT COUNT(*) FROM favorites)
             ELSE (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) END AS songCount,
        CASE WHEN p.id = 'favorite' THEN
             (SELECT t.coverUri FROM tracks t INNER JOIN favorites f ON t.id = f.trackId WHERE t.coverUri IS NOT NULL ORDER BY f.sortOrder DESC LIMIT 1)
             ELSE (SELECT t.coverUri FROM tracks t INNER JOIN playlist_tracks pt ON t.id = pt.trackId WHERE pt.playlistId = p.id AND t.coverUri IS NOT NULL ORDER BY pt.sortOrder LIMIT 1)
        END AS coverUri FROM playlists p""")
    fun getSummaries(): Flow<List<PlaylistSummaryRow>>
    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :id ORDER BY sortOrder")
    suspend fun getMembersSync(id: String): List<PlaylistTrackEntity>
    @Query("SELECT * FROM playlist_tracks ORDER BY sortOrder ASC")
    fun getAllMembers(): Flow<List<PlaylistTrackEntity>>
    @Query("UPDATE playlist_tracks SET sortOrder = :position WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun updateTrackOrder(playlistId: String, trackId: String, position: Int)

    @Query("UPDATE playlists SET sortOrder = :position WHERE id = :id")
    suspend fun updatePlaylistOrder(id: String, position: Int)
    @Query("SELECT * FROM playlists ORDER BY sortOrder ASC, createdAt ASC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: String): PlaylistEntity?

    @Upsert
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
    @Transaction
    @Query("SELECT t.* FROM tracks t INNER JOIN favorites f ON t.id = f.trackId ORDER BY f.sortOrder DESC, f.addedAt DESC")
    fun getTrackDetails(): Flow<List<TrackDetails>>
    @Query("SELECT * FROM favorites ORDER BY sortOrder DESC, addedAt DESC")
    suspend fun getFavoritesSync(): List<FavoriteEntity>
    @Query("UPDATE favorites SET sortOrder = :position WHERE trackId = :trackId")
    suspend fun updateOrder(trackId: String, position: Long)
    @Query("SELECT t.* FROM tracks t INNER JOIN favorites f ON t.id = f.trackId ORDER BY f.sortOrder DESC, f.addedAt DESC")
    fun getFavoriteTracks(): Flow<List<TrackEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId = :trackId)")
    fun isFavorite(trackId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId = :trackId)")
    suspend fun isFavoriteSync(trackId: String): Boolean

    @Query("SELECT trackId FROM favorites")
    fun getAllFavoriteTrackIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavorite(fav: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE trackId = :trackId")
    suspend fun removeFavorite(trackId: String)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_entries ORDER BY queueOrder")
    suspend fun getEntriesSync(): List<QueueEntryEntity>
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
    @Query("DELETE FROM playback_snapshot")
    suspend fun clearSnapshot()
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

    @Upsert
    suspend fun insertOrUpdate(script: SourceScriptEntity)

    @Delete
    suspend fun delete(script: SourceScriptEntity)

    @Query("SELECT * FROM platform_source_selection")
    fun getPlatformSelections(): Flow<List<PlatformSourceSelectionEntity>>

    @Query("SELECT * FROM platform_source_selection WHERE platform = :platform LIMIT 1")
    suspend fun getSelectionForPlatform(platform: String): PlatformSourceSelectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPlatformSelection(selection: PlatformSourceSelectionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefaultPlatformSelection(selection: PlatformSourceSelectionEntity)

    @Query("UPDATE platform_source_selection SET sourceId = :replacement WHERE platform = :platform AND sourceId = :previous")
    suspend fun replacePlatformSelection(platform: String, previous: String, replacement: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT COUNT(*) FROM download_tasks WHERE status != 'COMPLETED'")
    fun getPendingCount(): Flow<Int>
    @Query("SELECT * FROM download_tasks ORDER BY createdAt ASC")
    suspend fun getAllTasksSync(): List<DownloadTaskEntity>

    @Query("UPDATE download_tasks SET tempFilePath = :path, resourceEtag = :etag WHERE id = :id")
    suspend fun updateResource(id: String, path: String, etag: String?)

    @Query("UPDATE download_tasks SET downloadedBytes = :progress, totalBytes = :total, bytesPerSecond = :speed, etaSeconds = :eta, updatedAt = :time WHERE id = :id")
    suspend fun updateTransfer(id: String, progress: Long, total: Long, speed: Long, eta: Long?, time: Long = System.currentTimeMillis())
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
    @Query("SELECT * FROM lyric_records")
    suspend fun getAllLyrics(): List<LyricRecordEntity>
    @Query("SELECT COALESCE(SUM(LENGTH(content)), 0) FROM lyric_records")
    suspend fun getLyricCharacterCount(): Long
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

@Dao
interface RestoreSessionDao {
    @Query("SELECT * FROM restore_sessions WHERE state != 'COMPLETED' ORDER BY createdAt")
    fun getPending(): Flow<List<RestoreSessionEntity>>

    @Query("SELECT * FROM restore_sessions WHERE id = :id")
    suspend fun getById(id: String): RestoreSessionEntity?

    @Upsert
    suspend fun save(session: RestoreSessionEntity)

    @Query("DELETE FROM restore_sessions WHERE state = 'COMPLETED' AND createdAt < :before")
    suspend fun pruneCompleted(before: Long)
}
