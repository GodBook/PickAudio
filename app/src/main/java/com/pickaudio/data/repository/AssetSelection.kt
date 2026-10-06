package com.pickaudio.data.repository

import com.pickaudio.data.db.LocalAssetEntity
import com.pickaudio.data.model.AudioInfo

internal fun rankAssets(assets: List<LocalAssetEntity>, requested: String? = null): List<LocalAssetEntity> =
    assets.filter { it.isAvailable }.map { it to AudioInfo.decode(it.audioInfoJson) }
        .sortedWith(compareByDescending<Pair<LocalAssetEntity, AudioInfo?>> {
            requested == null || it.second != null && it.second!!.qualityFailure(requested) == null
        }.thenByDescending { it.second?.lossless == true }
            .thenByDescending { it.second?.bitDepth ?: 0 }
            .thenByDescending { it.second?.sampleRate ?: 0 }
            .thenByDescending { it.second?.bitrate ?: 0 }
            .thenBy { it.first.id })
        .map { it.first }
