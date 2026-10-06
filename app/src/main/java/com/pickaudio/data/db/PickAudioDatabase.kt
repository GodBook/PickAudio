package com.pickaudio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
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
        ImportRootEntity::class,
        RestoreSessionEntity::class
    ],
    version = 4,
    exportSchema = true
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
    abstract fun restoreSessionDao(): RestoreSessionDao

    companion object {
        const val FAVORITE_PLAYLIST_ID = "favorite"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_assets ADD COLUMN folderName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN actualQuality TEXT")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN bytesPerSecond INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN etaSeconds INTEGER")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN resourceEtag TEXT")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE favorites SET sortOrder = addedAt")
                db.execSQL("ALTER TABLE playback_snapshot ADD COLUMN currentEntryId INTEGER")
                db.execSQL("ALTER TABLE playback_snapshot ADD COLUMN queueRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE playback_snapshot SET currentEntryId = (SELECT id FROM queue_entries WHERE trackId = playback_snapshot.currentTrackId ORDER BY queueOrder LIMIT 1)")
                val entryIds = mutableListOf<Long>()
                db.query("SELECT id FROM queue_entries ORDER BY queueOrder").use { cursor ->
                    while (cursor.moveToNext()) entryIds.add(cursor.getLong(0))
                }
                db.query("SELECT shuffleOrderJson, shuffleHistoryJson FROM playback_snapshot WHERE id = 1").use { cursor ->
                    if (cursor.moveToFirst()) {
                        fun migrateIndices(value: String?): String? = value?.let {
                            runCatching {
                                val values = com.google.gson.JsonParser.parseString(it).asJsonArray
                                com.google.gson.Gson().toJson(values.mapNotNull { index -> entryIds.getOrNull(index.asInt) })
                            }.getOrNull()
                        }
                        db.execSQL("UPDATE playback_snapshot SET shuffleOrderJson = ?, shuffleHistoryJson = ? WHERE id = 1",
                            arrayOf(migrateIndices(cursor.getString(0)), migrateIndices(cursor.getString(1))))
                    }
                }
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN executionGeneration INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN publishToken TEXT")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN publishStage TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS restore_sessions (id TEXT NOT NULL PRIMARY KEY, manifestJson TEXT NOT NULL, restoreSettings INTEGER NOT NULL, state TEXT NOT NULL, reportJson TEXT, errorMessage TEXT, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_restore_sessions_state ON restore_sessions(state)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_assets ADD COLUMN folderId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE local_assets ADD COLUMN fileName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE local_assets ADD COLUMN audioInfoJson TEXT")
                db.execSQL("ALTER TABLE local_assets ADD COLUMN unavailableReason TEXT")
            }
        }

        @Volatile
        private var INSTANCE: PickAudioDatabase? = null

        fun getInstance(context: Context): PickAudioDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PickAudioDatabase::class.java,
                    "pickaudio.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).addCallback(object : Callback() {
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

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON;")
                    }
                }).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
