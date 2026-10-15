package com.pickaudio.playback

import com.pickaudio.network.withResponse
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

private data class CachedProbe(val url: String, val etag: String?, val expiresAt: Long)
private val probes = object : LinkedHashMap<String, CachedProbe>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedProbe>?) = size > 128
}
internal fun invalidateAudioCacheProbes() = synchronized(probes) { probes.clear() }

internal suspend fun resolveAudioCacheKey(client: OkHttpClient, resource: LxSourceManager.MusicResource,
    platform: String, songId: String, quality: String): String {
    var url = resource.url
    var etag: String? = null
    val probeId = listOf(resource.url, resource.sourceIdentity, platform, songId, quality).joinToString("\u0000")
    val cached = synchronized(probes) { probes[probeId]?.takeIf { it.expiresAt > android.os.SystemClock.elapsedRealtime() } }
    if (cached != null) {
        val key = audioCacheKey(cached.url, platform, songId, quality, resource.sourceIdentity, cached.etag)
        AudioCacheManager.registerValidator(key, cached.etag)
        return key
    }
    var reusable = false
    try {
        withTimeout(1500) {
            client.withResponse(Request.Builder().url(resource.url).head().header("Accept-Encoding", "identity").build()) { response ->
                if (response.isSuccessful) {
                    reusable = true
                    etag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') && it.length > 2 }
                    if (etag != null) url = response.request.url.toString()
                }
                if (response.code in listOf(405, 501)) reusable = true
            }
        }
    } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { /* HEAD is optional; full URL identity remains safe. */ }
    if (reusable) synchronized(probes) {
        probes[probeId] = CachedProbe(url, etag, android.os.SystemClock.elapsedRealtime() + 30_000)
    }
    val key = audioCacheKey(url, platform, songId, quality, resource.sourceIdentity, etag)
    AudioCacheManager.registerValidator(key, etag)
    return key
}
