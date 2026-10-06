package com.pickaudio.data.repository

import com.pickaudio.data.db.LocalAssetEntity
import com.pickaudio.data.db.TrackEntity
import java.util.Locale
import kotlin.math.abs

data class RelinkCandidate(val trackId: String, val title: String, val uri: String, val evidence: String)

internal fun matchRelinkCandidates(target: TrackEntity, hints: List<LocalAssetEntity>,
    assets: List<LocalAssetEntity>, tracks: Map<String, TrackEntity>): List<RelinkCandidate> = assets.asSequence()
    .filter { it.isAvailable && it.trackId != target.id }
    .mapNotNull { asset ->
        val track = tracks[asset.trackId] ?: return@mapNotNull null
        val hash = hints.any { !it.fileHash.isNullOrBlank() && it.fileHash == asset.fileHash }
        fun name(value: String) = value.substringBeforeLast('.', value).lowercase(Locale.ROOT)
        val metadata = hints.any { it.fileSize > 0 && it.fileSize == asset.fileSize && it.fileName.isNotBlank() &&
            name(it.fileName) == name(asset.fileName) && target.durationMs > 0 && abs(track.durationMs - target.durationMs) <= 2000 }
        if (!hash && !metadata) null else RelinkCandidate(track.id, track.title, asset.uri,
            if (hash) "文件 SHA-256 相同，仍需确认录音" else "文件名、大小与时长相近，请核对录音")
    }.distinctBy { it.uri }.sortedBy { if (it.evidence.startsWith("文件 SHA")) 0 else 1 }.take(10).toList()
