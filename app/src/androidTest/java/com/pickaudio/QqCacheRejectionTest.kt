package com.pickaudio

import android.content.Context
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.network.NetworkPolicy
import com.pickaudio.playback.AudioCacheManager
import com.pickaudio.playback.QqAudioCacheRevision
import com.pickaudio.playback.audioCacheKey
import com.pickaudio.playback.resolveAudioCacheKey
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class QqCacheRejectionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rejectedQqBytesCannotBeReusedWithTheSameUrlEtagOrHeadProbe() = runBlocking {
        MockWebServer().use { server ->
            val gets = AtomicInteger()
            val partial = ByteArray(4096) { 1 }
            val complete = ByteArray(8192) { 2 }
            var bytes = partial
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val response = MockResponse().setHeader("ETag", "\"unchanged\"")
                    if (request.method == "HEAD") return response.setHeader("Content-Length", bytes.size)
                    gets.incrementAndGet()
                    return response.setBody(Buffer().write(bytes))
                }
            }
            val client = NetworkPolicy(setOf("localhost", "127.0.0.1")).client()
            val resource = LxSourceManager.MusicResource(server.url("/qq-rejected").toString(), "rejection-fixture")
            val keys = mutableSetOf<String>()
            suspend fun key(platform: String) = resolveAudioCacheKey(client, resource, platform, "rejection", "128k", context)
            fun read(key: String): ByteArray {
                keys.add(key)
                val source = AudioCacheManager.createDataSourceFactory(context, client).createDataSource()
                val output = ByteArrayOutputStream()
                try {
                    source.open(DataSpec.Builder().setUri(resource.url).setKey(key).build())
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = source.read(buffer, 0, buffer.size)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    return output.toByteArray()
                } finally { source.close() }
            }
            try {
                val first = key("tx")
                val netEase = key("wy")
                assertArrayEquals(partial, read(first))
                assertArrayEquals(partial, read(first))
                assertEquals(1, gets.get())
                AudioCacheManager.invalidateIncompleteAudioCache(context, first)
                bytes = complete
                val retry = key("tx")
                assertNotEquals(first, retry)
                assertEquals(netEase, key("wy"))
                assertTrue(AudioCacheManager.getCache(context).getCachedSpans(first).isEmpty())
                assertArrayEquals(complete, read(retry))
                assertEquals(2, gets.get())
                assertEquals(retry, key("tx"))
            } finally { keys.forEach { AudioCacheManager.getCache(context).removeResource(it) } }
        }
    }

    @Test fun aFreshRevisionStoreRetainsTheCommittedGenerationAndIgnoresOldCallbacks() {
        val name = "qq-cache-revision-test-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val base = audioCacheKey("https://example.com/audio", "tx", "song", "128k", "fixture", null)
        try {
            val original = QqAudioCacheRevision(preferences).key(base)
            QqAudioCacheRevision(preferences).reject(original)
            val recovered = QqAudioCacheRevision(context.getSharedPreferences(name, Context.MODE_PRIVATE)).key(base)
            assertNotEquals(original, recovered)
            assertEquals(1L, preferences.getLong("revision", 0))
            QqAudioCacheRevision(preferences).reject(original)
            assertEquals(recovered, QqAudioCacheRevision(preferences).key(base))
            QqAudioCacheRevision(preferences).reject(recovered)
            assertNotEquals(recovered, QqAudioCacheRevision(preferences).key(base))
            assertEquals(1, preferences.all.size)
        } finally { context.deleteSharedPreferences(name) }
    }
}
