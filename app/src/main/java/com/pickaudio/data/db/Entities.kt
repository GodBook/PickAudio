package com.pickaudio.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["title"]),
        Index(value = ["artist"]),
        Index(value = ["album"])
    ]
)
data class TrackEntity(
    @PrimaryKey val id: String, // UUID or persistent hash
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUri: String?,
    val trackNumber: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "local_assets",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["uri"], unique = true)
    ]
)
data class LocalAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val uri: String,
    val sourceType: String, // MEDIA_STORE, SAF_FILE, SAF_DIR, DOWNLOADED
    val fileSize: Long,
    val mimeType: String?,
    val format: String?, // mp3, flac, m4a, etc.
    val isAvailable: Boolean = true,
    val fileHash: String? = null
)

@Entity(
    tableName = "online_refs",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["platform", "platformSongId"], unique = true)
    ]
)
data class OnlineRefEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val platform: String, // "wy" (NetEase) or "tx" (QQ)
    val platformSongId: String,
    val platformMetadataJson: String
)

@Entity(
    tableName = "playlists",
    indices = [Index(value = ["sortOrder"])]
)
data class PlaylistEntity(
    @PrimaryKey val id: String, // "favorite" for built-in, or UUID
    val name: String,
    val coverUri: String? = null,
    val sortOrder: Int = 0,
    val isSystem: Boolean = false, // "我喜欢" is system playlist
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["trackId"]),
        Index(value = ["playlistId", "sortOrder"])
    ]
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val trackId: String,
    val sortOrder: Int,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["trackId"])]
)
data class FavoriteEntity(
    @PrimaryKey val trackId: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "queue_entries",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["queueOrder"]),
        Index(value = ["trackId"])
    ]
)
data class QueueEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val queueOrder: Int
)

@Entity(tableName = "playback_snapshot")
data class PlaybackSnapshotEntity(
    @PrimaryKey val id: Int = 1, // Single record
    val currentTrackId: String?,
    val progressMs: Long = 0L,
    val playbackMode: String = "SEQUENTIAL", // SEQUENTIAL, LIST_LOOP, SINGLE_LOOP, SHUFFLE
    val shuffleOrderJson: String? = null,
    val shuffleHistoryJson: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "source_scripts",
    indices = [Index(value = ["scriptHash"], unique = true)]
)
data class SourceScriptEntity(
    @PrimaryKey val id: String, // UUID
    val name: String,
    val version: String = "1.0.0",
    val author: String = "",
    val description: String = "",
    val homepage: String = "",
    val scriptHash: String,
    val scriptContent: String,
    val capabilitiesJson: String, // Supported sources (wy/tx) & quality levels
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "platform_source_selection",
    foreignKeys = [
        ForeignKey(
            entity = SourceScriptEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["sourceId"])]
)
data class PlatformSourceSelectionEntity(
    @PrimaryKey val platform: String, // "wy" or "tx"
    val sourceId: String?
)

@Entity(
    tableName = "download_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["platform", "platformSongId", "targetQuality"], unique = true)
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey val id: String, // UUID
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUri: String?,
    val platform: String,
    val platformSongId: String,
    val targetQuality: String, // 128k, 320k, flac
    val status: String, // PENDING, RESOLVING, DOWNLOADING, VERIFYING, PUBLISHING, COMPLETED, PAUSED, FAILED, CANCELLED
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val tempFilePath: String? = null,
    val targetUri: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "lyric_records",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["trackId"])]
)
data class LyricRecordEntity(
    @PrimaryKey val trackId: String,
    val sourceType: String, // MANUAL_LRC, SAME_DIR_LRC, CACHED_ONLINE, PLATFORM_ONLINE
    val content: String,
    val offsetMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "import_roots")
data class ImportRootEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val scanRulesJson: String? = null,
    val lastScanAt: Long = System.currentTimeMillis()
)
