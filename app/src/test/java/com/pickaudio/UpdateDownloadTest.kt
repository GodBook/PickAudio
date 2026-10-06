package com.pickaudio

import android.content.ContextWrapper
import com.pickaudio.network.readLimitedBytes
import com.pickaudio.network.withResponse
import com.pickaudio.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateDownloadTest {
    private val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(256 * 1024)
    private fun info(url: String, data: ByteArray = bytes, hash: String? = null) =
        UpdateInfo(10, "../../unsafe-name", "", url, "", data.size.toLong(), sha256 = hash)

    @Test fun cancelReallyStopsOwnedTaskAndCannotRestoreDownloadedState() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("pickaudio-update-test-").toFile()
            try {
                server.enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(1024, 100, TimeUnit.MILLISECONDS))
                val context = object : ContextWrapper(null) { override fun getCacheDir(): File = directory }
                val manager = AppUpdateManager(context, OkHttpClient(), { _, _ -> }, {})
                val waiter = launch { manager.startDownload(info(server.url("/package").toString())) }
                withTimeout(5000) { manager.status.first { it is UpdateStatus.Downloading && it.downloadedBytes > 0 } }
                manager.cancelDownload()
                withTimeout(1500) { waiter.join() }
                delay(200)
                assertEquals(UpdateStatus.Idle, manager.status.value)
                assertTrue(File(directory, "updates").listFiles().orEmpty().isEmpty())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun leavingPageDoesNotCancelManagerOwnedDownload() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("pickaudio-update-test-").toFile()
            try {
                server.enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(65536, 50, TimeUnit.MILLISECONDS))
                val context = object : ContextWrapper(null) { override fun getCacheDir(): File = directory }
                val manager = AppUpdateManager(context, OkHttpClient(), { _, _ -> }, {})
                val waiter = launch { manager.startDownload(info(server.url("/package").toString())) }
                withTimeout(5000) { manager.status.first { it is UpdateStatus.Downloading && it.downloadedBytes > 0 } }
                waiter.cancelAndJoin()
                val completed = withTimeout(5000) { manager.status.first { it is UpdateStatus.Downloaded } } as UpdateStatus.Downloaded
                assertEquals(bytes.size.toLong(), completed.apkFile.length())
                assertEquals(File(directory, "updates").canonicalFile, completed.apkFile.canonicalFile.parentFile)
                assertFalse(completed.apkFile.name.contains("unsafe"))
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun wrongDigestOrPackageIdentityNeverLeavesInstallableFile() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("pickaudio-update-test-").toFile()
            try {
                repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(bytes))) }
                val downloader = UpdatePackageDownloader(OkHttpClient(), directory, { _, _ -> error("wrong package") }, {})
                assertTrue(runCatching { downloader.download(info(server.url("/digest").toString(), hash = "0".repeat(64))) { _, _ -> } }.exceptionOrNull()?.message.orEmpty().contains("校验"))
                assertTrue(runCatching { downloader.download(info(server.url("/identity").toString())) { _, _ -> } }.exceptionOrNull()?.message.orEmpty().contains("wrong package"))
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun correctChecksumIsVerifiedBeforePublishing() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("pickaudio-update-test-").toFile()
            try {
                server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                var validated = false
                val file = UpdatePackageDownloader(OkHttpClient(), directory, { partial, _ ->
                    assertEquals("part", partial.extension)
                    validated = true
                }, {}).download(info(server.url("/package").toString(), hash = hash)) { _, _ -> }
                assertTrue(validated)
                assertEquals("apk", file.extension)
                assertArrayEquals(bytes, file.readBytes())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun chunkedResponsesEnforceLimitWhileReading() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("x".repeat(8192), 64))
            val result = runCatching {
                OkHttpClient().withResponse(Request.Builder().url(server.url("/large")).build()) {
                    it.body!!.readLimitedBytes(1024)
                }
            }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("超过"))
        }
    }

    @Test fun officialUrlsRejectOtherHostsAndEncodedTraversal() {
        requireOfficialUpdateUrl("https://github.com/GodBook/PickAudio/releases/download/v1.3.0/PickAudio.apk")
        listOf("http://github.com/GodBook/PickAudio/releases/download/v1/a.apk",
            "https://github.com.evil.test/GodBook/PickAudio/releases/download/v1/a.apk",
            "https://github.com/other/repo/releases/download/v1/a.apk").forEach {
            assertTrue(runCatching { requireOfficialUpdateUrl(it) }.isFailure)
        }
    }
}
