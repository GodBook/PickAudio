package com.pickaudio.data.repository

import com.pickaudio.data.db.*
import com.pickaudio.data.model.Track

fun TrackEntity.toTrack(assets: List<LocalAssetEntity>, refs: List<OnlineRefEntity>, favorite: Boolean = false): Track {
    val asset = assets.firstOrNull { it.isAvailable }
    val ref = refs.firstOrNull()
    val inferredPlatform = if (id.startsWith("online_wy_")) "wy" else if (id.startsWith("online_tx_")) "tx" else null
    return Track(
        id = id, title = title, artist = artist, album = album, durationMs = durationMs,
        coverUri = coverUri, trackNumber = trackNumber, localUri = asset?.uri,
        isAvailable = asset != null, isFavorite = favorite, platform = ref?.platform ?: inferredPlatform,
        platformSongId = ref?.platformSongId ?: inferredPlatform?.let { id.removePrefix("online_${it}_") },
        folderName = asset?.folderName.orEmpty(), sourceType = asset?.sourceType.orEmpty(), createdAt = createdAt
    )
}
