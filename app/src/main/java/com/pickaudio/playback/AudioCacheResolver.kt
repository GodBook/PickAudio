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

internal suspend fun resolveAudioCacheKey(client: OkHttpClient, resource: LxSourceManager.MusicResource,
    platform: String, songId: String, quality: String): String {
    var url = resource.url
    var etag: String? = null
    try {
        withTimeout(1500) {
            client.withResponse(Request.Builder().url(resource.url).head().header("Accept-Encoding", "identity").build()) { response ->
                if (response.isSuccessful) {
                    etag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') && it.length > 2 }
                    if (etag != null) url = response.request.url.toString()
                }
            }
        }
    } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { /* HEAD is optional; full URL identity remains safe. */ }
    val key = audioCacheKey(url, platform, songId, quality, resource.sourceIdentity, etag)
    AudioCacheManager.registerValidator(key, etag)
    return key
}
