package com.pickaudio

import com.pickaudio.online.BuiltinQqMusicAdapter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BuiltinQqMusicTest {
    @Test fun transientQinghuaciBackendFailureRetriesSameRecordingAndQualityOnce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":110000,"message":"HTTP客户端异常：未知错误：500011","data":null}"""))
            val url = server.url("/song.mp3").toString()
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"songMID":"002qU5aY3Qu24y","url":"$url"}}"""))
            server.enqueue(MockResponse().setBody("ID3\u0003\u0000\u0000audio-data"))
            assertEquals(url, BuiltinQqMusicAdapter(OkHttpClient(), server.url("/resolve").toString()).resolve("002qU5aY3Qu24y", "128k"))
            assertEquals(server.takeRequest().path, server.takeRequest().path)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun persistentBackendFailureStopsAfterTwoRequests() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""{"code":110000,"data":null}""")) }
            assertTrue(runCatching { BuiltinQqMusicAdapter(OkHttpClient(), server.url("/resolve").toString()).resolve("002qU5aY3Qu24y", "128k") }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun resolvesExactMidAndChecksRealAudioForEachQuality() = runBlocking {
        for ((quality, level) in listOf("128k" to 4, "320k" to 8, "flac" to 10)) {
            MockWebServer().use { server ->
                val url = server.url("/original.mp3").toString()
                server.enqueue(MockResponse().setBody("""{"code":0,"data":{"songMID":"original","url":"$url"}}"""))
                server.enqueue(MockResponse().setResponseCode(206).setBody("ID3\u0003\u0000\u0000audio-data"))
                assertEquals(url, BuiltinQqMusicAdapter(OkHttpClient(), server.url("/resolve").toString()).resolve("original", quality))
                val request = server.takeRequest()
                assertEquals("original", request.requestUrl!!.queryParameter("mid"))
                assertEquals(level.toString(), request.requestUrl!!.queryParameter("quality"))
                assertEquals("bytes=0-4095", server.takeRequest().getHeader("Range"))
            }
        }
    }

    @Test fun rejectsOtherRecordingsPreviewAndBackendFailures() {
        val adapter = BuiltinQqMusicAdapter(OkHttpClient())
        val invalid = listOf(
            """{"code":0,"data":{"songMID":"other","url":"https://example.com/song.mp3"}}""",
            """{"code":0,"data":{"songMID":"original","url":"https://ws.stream.qqmusic.qq.com/RS02original.mp3"}}""",
            """{"code":0,"data":{"songMID":"original","url":""}}""",
            """{"code":500,"data":{}}"""
        )
        for (body in invalid) assertTrue(runCatching { adapter.parseUrl(body, "original") }.isFailure)
    }

    @Test fun rejectsExpiredAddressesAndJsonMasqueradingAsAudio() = runBlocking {
        for (response in listOf(MockResponse().setResponseCode(403), MockResponse().setBody("{\"error\":\"expired\"}"))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"code":0,"data":{"songMID":"original","url":"${server.url("/expired.mp3")}"}}"""))
                server.enqueue(response)
                val result = runCatching { BuiltinQqMusicAdapter(OkHttpClient(), server.url("/resolve").toString()).resolve("original", "128k") }
                assertTrue(result.isFailure)
            }
        }
    }
}
