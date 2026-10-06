package com.pickaudio

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.network.NetworkPolicy
import com.pickaudio.playback.*
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class OptimizationCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun rotatingValidatedUrlsReuseBytesAndChangedRepresentationGetsNewAudio() = runBlocking {
        MockWebServer().use { server ->
            val gets = AtomicInteger()
            var version = 1
            val one = ByteArray(65536) { 1 }
            val two = ByteArray(65536) { 2 }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val etag = "\"$version\""
                    if (request.method == "HEAD") return MockResponse().setHeader("ETag", etag)
                    gets.incrementAndGet()
                    if (request.getHeader("If-Match") != etag) return MockResponse().setResponseCode(412)
                    return MockResponse().setHeader("ETag", etag).setBody(Buffer().write(if (version == 1) one else two))
                }
            }
            val client = NetworkPolicy(setOf("localhost", "127.0.0.1")).client()
            val cache = AudioCacheManager.getCache(context)
            val keys = mutableListOf<String>()
            suspend fun read(token: String): ByteArray {
                val url = server.url("/audio?token=$token").toString()
                val key = resolveAudioCacheKey(client, LxSourceManager.MusicResource(url, "fixture"), "wy", "cache_test", "128k")
                keys.add(key)
                val source = AudioCacheManager.createDataSourceFactory(context, client).createDataSource()
                val output = java.io.ByteArrayOutputStream()
                try {
                    source.open(DataSpec.Builder().setUri(url).setKey(key).build())
                    val buffer = ByteArray(8192)
                    while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; output.write(buffer, 0, count) }
                    return output.toByteArray()
                } finally { source.close() }
            }
            try {
                assertArrayEquals(one, read("a")); assertArrayEquals(one, read("b"))
                assertEquals(1, gets.get()); assertEquals(keys[0], keys[1])
                version = 2
                assertArrayEquals(two, read("c")); assertEquals(2, gets.get())
                assertNotEquals(keys[0], keys[2])
                assertTrue(PlaybackDiagnostics.metrics.value.cachedBytes >= one.size)
            } finally { keys.distinct().forEach(cache::removeResource) }
        }
    }
    @Test fun localPlaybackBypassesCacheAndCapacityCanShrinkExistingCacheInPlace() {
        val file = File.createTempFile("optimization-cache-local-", ".wav", context.cacheDir)
        val cache = AudioCacheManager.getCache(context)
        val key = "optimization-capacity-${System.nanoTime()}"
        try {
            file.writeBytes(ByteArray(1024))
            val keysBefore = cache.keys.toSet()
            val source = AudioCacheManager.createDataSourceFactory(context).createDataSource()
            try {
                source.open(DataSpec(Uri.fromFile(file)))
                val buffer = ByteArray(4096)
                while (source.read(buffer, 0, buffer.size) >= 0) { }
            } finally { source.close() }
            assertEquals(keysBefore, cache.keys)
            AudioCacheManager.configureCapacity(context, 256)
            repeat(3) { index ->
                val resource = "$key:$index"
                val lease = cache.startReadWrite(resource, 0, 40 * 1024 * 1024L)
                try {
                    val spanFile = cache.startFile(resource, 0, 40 * 1024 * 1024L)
                    spanFile.outputStream().use { output -> repeat(40) { output.write(ByteArray(1024 * 1024)) } }
                    cache.commitFile(spanFile, spanFile.length())
                } finally { cache.releaseHoleSpan(lease) }
            }
            assertTrue(cache.cacheSpace >= 120 * 1024 * 1024L)
            AudioCacheManager.configureCapacity(context, 64)
            assertSame(cache, AudioCacheManager.getCache(context))
            assertTrue(cache.cacheSpace <= 64 * 1024 * 1024L)
        } finally {
            (0..2).forEach { cache.removeResource("$key:$it") }
            AudioCacheManager.configureCapacity(context, 256); file.delete()
        }
    }
}
