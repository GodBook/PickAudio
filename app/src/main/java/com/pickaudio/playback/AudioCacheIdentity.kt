package com.pickaudio.playback

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/** A strong validator is required before dropping rotating URL query parameters. */
internal fun audioCacheKey(url: String, platform: String, songId: String, quality: String, source: String,
    etag: String?): String {
    val strong = etag?.takeIf { it.startsWith('"') && it.endsWith('"') && it.length > 2 && !it.startsWith("W/", true) }
    val parsed = url.toHttpUrl()
    val resource = if (strong == null) url else "${parsed.scheme}://${parsed.host}:${parsed.port}${parsed.encodedPath}\u0000$strong"
    val input = listOf(platform, songId, quality, source, resource).joinToString("\u0000")
    return "audio-v2:" + MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
