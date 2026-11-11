package com.pickaudio.online

import com.google.gson.JsonParser

/** Keep the catalogue duration independent of the duration declared by a downloaded clip. */
object AudioCompleteness {
    fun expectedDurationMs(platform: String, metadataJson: String, fallbackMs: Long): Long {
        val catalogueMs = runCatching {
            val metadata = JsonParser.parseString(metadataJson).asJsonObject
            when (platform) {
                "tx" -> metadata.get("interval")?.asLong
                    ?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
                "wy" -> metadata.get("dt")?.asLong?.takeIf { it > 0 }
                else -> null
            }
        }.getOrNull()
        return catalogueMs ?: fallbackMs.coerceAtLeast(0)
    }

    fun failure(expectedMs: Long, actualMs: Long): String? {
        if (expectedMs <= 0 || actualMs <= 0) return null
        // Search results round to seconds; encoder padding and VBR estimates also vary slightly.
        val toleranceMs = (expectedMs / 50).coerceIn(2000, 10_000)
        if (expectedMs - actualMs <= toleranceMs) return null
        return "音频只有 ${actualMs / 1000} 秒，原曲约 ${expectedMs / 1000} 秒，可能是试听片段或文件不完整，请重试或换源"
    }

    fun requireComplete(expectedMs: Long, actualMs: Long) {
        val reason = failure(expectedMs, actualMs)
        require(reason == null) { reason.orEmpty() }
    }
}
