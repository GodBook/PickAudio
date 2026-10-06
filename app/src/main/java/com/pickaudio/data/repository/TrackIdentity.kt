package com.pickaudio.data.repository

import androidx.room.withTransaction
import com.pickaudio.data.db.*
import com.pickaudio.data.model.Track

/** The platform reference owns online identity; search IDs must never steal an existing reference. */
internal suspend fun PickAudioDatabase.ensureTrackIdentity(track: Track): Track = withTransaction {
    require(track.id.isNotBlank() && track.title.isNotBlank()) { "歌曲信息无效" }
    val platform = track.platform ?: when {
        track.id.startsWith("online_wy_") -> "wy"
        track.id.startsWith("online_tx_") -> "tx"
        else -> null
    }
    val songId = track.platformSongId ?: platform?.let { track.id.removePrefix("online_${it}_") }
    val existing = if (platform != null && !songId.isNullOrBlank()) onlineRefDao().getByPlatformId(platform, songId) else null
    val id = existing?.trackId ?: track.id
    if (trackDao().getTrackById(id) == null) trackDao().insertOrUpdate(TrackEntity(
        id, track.title, track.artist, track.album, track.durationMs, track.coverUri, track.trackNumber))
    if (platform != null && !songId.isNullOrBlank() && existing == null && onlineRefDao().getByTrackId(id) == null)
        onlineRefDao().insertOrUpdate(OnlineRefEntity(trackId = id, platform = platform,
            platformSongId = songId, platformMetadataJson = track.platformMetadataJson))
    track.copy(id = id)
}
