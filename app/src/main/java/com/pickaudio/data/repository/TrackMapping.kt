package com.pickaudio.data.repository

import com.pickaudio.data.db.*
import com.pickaudio.data.model.Track
import com.pickaudio.data.model.AudioInfo

fun TrackEntity.toTrack(assets: List<LocalAssetEntity>, refs: List<OnlineRefEntity>, favorite: Boolean = false): Track {
    val asset = rankAssets(assets).firstOrNull()
    val hint = asset ?: assets.firstOrNull()
    val ref = refs.firstOrNull()
    val inferredPlatform = if (id.startsWith("online_wy_")) "wy" else if (id.startsWith("online_tx_")) "tx" else null
    return Track(
        id = id, title = title, artist = artist, album = album, durationMs = durationMs,
        coverUri = coverUri, trackNumber = trackNumber, localUri = asset?.uri,
        isAvailable = asset != null, isFavorite = favorite, platform = ref?.platform ?: inferredPlatform,
        platformSongId = ref?.platformSongId ?: inferredPlatform?.let { id.removePrefix("online_${it}_") },
        folderName = hint?.folderName.orEmpty(), sourceType = hint?.sourceType.orEmpty(), createdAt = createdAt,
        folderId = hint?.folderId.orEmpty(), platformMetadataJson = ref?.platformMetadataJson ?: "{}",
        audioInfo = AudioInfo.decode(asset?.audioInfoJson),
        repairReason = if (asset == null) hint?.unavailableReason ?: if (hint != null || ref == null && inferredPlatform == null) "需要关联音频文件" else null else null
    )
}
