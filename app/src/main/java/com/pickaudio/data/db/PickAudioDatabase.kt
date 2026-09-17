package com.pickaudio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        TrackEntity::class,
        LocalAssetEntity::class,
        OnlineRefEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        FavoriteEntity::class,
        QueueEntryEntity::class,
        PlaybackSnapshotEntity::class,
        SourceScriptEntity::class,
        PlatformSourceSelectionEntity::class,
        DownloadTaskEntity::class,
        LyricRecordEntity::class,
        ImportRootEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PickAudioDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun localAssetDao(): LocalAssetDao
    abstract fun onlineRefDao(): OnlineRefDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun queueDao(): QueueDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun sourceDao(): SourceDao
    abstract fun downloadDao(): DownloadDao
    abstract fun lyricDao(): LyricDao
    abstract fun importRootDao(): ImportRootDao

    companion object {
        const val FAVORITE_PLAYLIST_ID = "favorite"

        @Volatile
        private var INSTANCE: PickAudioDatabase? = null

        fun getInstance(context: Context): PickAudioDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PickAudioDatabase::class.java,
                    "pickaudio.db"
                ).addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Insert system favorite playlist
                        CoroutineScope(Dispatchers.IO).launch {
                            getInstance(context).playlistDao().insertOrUpdate(
                                PlaylistEntity(
                                    id = FAVORITE_PLAYLIST_ID,
                                    name = "我喜欢",
                                    sortOrder = 0,
                                    isSystem = true
                                )
                            )
                        }
                    }
                }).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
