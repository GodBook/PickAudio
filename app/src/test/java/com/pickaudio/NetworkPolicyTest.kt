package com.pickaudio

import com.pickaudio.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class NetworkPolicyTest {
    @Test fun rejectsReservedV4AndV6IncludingCarrierNatAndUla() {
        listOf("0.0.0.0", "10.1.2.3", "127.0.0.1", "100.64.1.2", "169.254.169.254",
            "172.31.1.1", "192.168.1.1", "198.18.0.1", "::1", "fc00::1", "fe80::1",
            "2001:db8::1", "2002:7f00:1::1").forEach {
            assertFalse(it, NetworkPolicy.isPublicAddress(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach {
            assertTrue(it, NetworkPolicy.isPublicAddress(InetAddress.getByName(it)))
        }
    }
    @Test fun rejectsPrivateReturnedUrlsAndUnlistedCleartext() {
        listOf("https://127.0.0.1/audio", "https://[fc00::1]/audio", "http://example.com/audio",
            "https://user:password@example.com/audio", "file:///music/song").forEach {
            assertTrue(it, runCatching { NetworkPolicy.Default.validate(it) }.isFailure)
        }
        assertEquals("music.163.com", NetworkPolicy.Default.validate("http://music.163.com/song").host)
    }
    @Test fun mixedDnsAnswersAreRejectedBeforeConnection() = runBlocking {
        val client = NetworkPolicy.Default.client(OkHttpClient(), object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1"))
        })
        val error = runCatching { client.withResponse(Request.Builder().url("https://public.example/").build()) { it.code } }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message?.contains("保留地址") == true)
    }
    @Test fun redirectedPrivateAddressIsRejectedBeforeSecondRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://127.0.0.2/secret"))
            val client = NetworkPolicy(setOf("localhost", "127.0.0.1")).client()
            val request = Request.Builder().url(server.url("/").newBuilder().host("127.0.0.1").build()).build()
            assertTrue(runCatching { client.withResponse(request) { it.code } }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun chunkedLimitsApplyDuringReadingAndConnectionRemainsUsable() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setChunkedBody("x".repeat(8192), 64))
            server.enqueue(MockResponse().setBody("ok"))
            val client = NetworkPolicy(setOf("localhost", "127.0.0.1")).client()
            val request = Request.Builder().url(server.url("/").newBuilder().host("127.0.0.1").build()).build()
            assertTrue(runCatching { client.withResponse(request) { it.body!!.readLimitedText(128) } }.isFailure)
            assertEquals("ok", client.withResponse(request) { it.body!!.readLimitedText(128) })
        }
    }
}
