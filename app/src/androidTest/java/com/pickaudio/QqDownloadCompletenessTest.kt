package com.pickaudio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.pickaudio.data.db.DownloadTaskEntity
import com.pickaudio.data.db.LocalAssetEntity
import com.pickaudio.data.db.OnlineRefEntity
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.db.TrackEntity
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.network.NetworkPolicy
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QqDownloadCompletenessTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PickAudioDatabase
    private val managers = mutableListOf<LxSourceManager>()
    private val outputs = mutableListOf<Uri>()
    private val parts = mutableListOf<File>()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
    }

    @After fun cleanup() = runBlocking {
        db.downloadDao().getAllTasksSync().mapNotNull { it.targetUri }.forEach { outputs.add(Uri.parse(it)) }
        outputs.distinct().forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        parts.forEach { it.delete() }
        managers.forEach { it.close() }
        db.close()
    }

    private fun audio(seconds: Int = 2): ByteArray = ByteBuffer.allocate(44 + 8000 * 2 * seconds)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(capacity() - 44)
        }.array()

    private suspend fun source(url: String): LxSourceManager {
        val manager = LxSourceManager(context, db, NetworkPolicy(setOf("localhost", "127.0.0.1")))
        managers.add(manager)
        manager.ensureBuiltinSources()
        val source = manager.importSourceFromCode("""
            lx.on(lx.EVENT_NAMES.request, () => Promise.resolve("$url"));
            lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{tx:{name:'完整性回归',actions:['musicUrl'],qualitys:['128k']}}});
        """.trimIndent())
        manager.selectSourceForPlatform("tx", source.id)
        return manager
    }

    private suspend fun seed(id: String, durationMs: Long, metadata: String) {
        db.trackDao().insertOrUpdate(TrackEntity(id, "QQ 完整性回归", "测试歌手", "测试专辑", durationMs, null))
        val stored = JsonParser.parseString(metadata).asJsonObject.apply {
            addProperty("songid", 123)
            addProperty("strMediaMid", "fixture_media")
        }.toString()
        db.onlineRefDao().insertOrUpdate(OnlineRefEntity(trackId = id, platform = "tx", platformSongId = id,
            platformMetadataJson = stored))
    }

    private fun coordinator(manager: LxSourceManager) = DownloadCoordinator(context, db, manager, scheduleOverride = {})

    @Test fun validShortAudioIsRejectedAgainstQqMetadataWithoutOverwritingTheSong() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(audio())))
            val manager = source(server.url("/short.wav").toString())
            seed("qq_short_metadata", 20000, """{"interval":20}""")
            val coordinator = coordinator(manager)
            val id = coordinator.enqueueDownload("qq_short_metadata", "QQ 完整性回归", "", "", null,
                "tx", "qq_short_metadata", "128k", durationMs = 2000)
            parts.add(File(coordinator.partialDirectory, "temp_$id.part"))
            coordinator.runPending()
            val result = db.downloadDao().getTaskById(id)!!
            assertEquals(result.errorMessage, "FAILED", result.status)
            assertNull(result.targetUri)
            assertNull(result.publishStage)
            assertTrue(db.localAssetDao().getAssetsForTrack("qq_short_metadata").isEmpty())
            assertEquals(20000L, db.trackDao().getTrackById("qq_short_metadata")!!.durationMs)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun taskDurationRejectsShortAudioWhenQqMetadataHasNoDuration() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(audio())))
            val manager = source(server.url("/short.wav").toString())
            seed("qq_short_fallback", 20000, "{}")
            val coordinator = coordinator(manager)
            val id = coordinator.enqueueDownload("qq_short_fallback", "QQ 完整性回归", "", "", null,
                "tx", "qq_short_fallback", "128k", durationMs = 20000)
            parts.add(File(coordinator.partialDirectory, "temp_$id.part"))
            coordinator.runPending()
            assertEquals("FAILED", db.downloadDao().getTaskById(id)!!.status)
            assertTrue(db.localAssetDao().getAssetsForTrack("qq_short_fallback").isEmpty())
            assertEquals(20000L, db.trackDao().getTrackById("qq_short_fallback")!!.durationMs)
        }
    }

    @Test fun fullAudioAndSongsWithUnknownDurationStillPublish() = runBlocking {
        MockWebServer().use { server ->
            val manager = source(server.url("/full.wav").toString())
            val coordinator = coordinator(manager)
            for ((songId, expected, metadata) in listOf(
                Triple("qq_complete", 20000L, """{"interval":20}"""),
                Triple("qq_unknown", 0L, "{}")
            )) {
                val bytes = audio(if (expected > 0) 20 else 2)
                server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                seed(songId, expected, metadata)
                val id = coordinator.enqueueDownload(songId, "QQ 完整性回归", "", "", null,
                    "tx", songId, "128k", durationMs = expected)
                coordinator.runPending()
                val task = db.downloadDao().getTaskById(id)!!
                assertEquals(task.errorMessage, "COMPLETED", task.status)
                val uri = Uri.parse(task.targetUri!!)
                assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                assertTrue(db.localAssetDao().getAssetsForTrack(songId).single().isAvailable)
            }
        }
    }

    @Test fun legacyVerifiedCheckpointMustPassTheNewCompletenessCheck() = runBlocking {
        MockWebServer().use { server ->
            val manager = source(server.url("/unused.wav").toString())
            seed("qq_verified_short", 20000, """{"interval":20}""")
            val bytes = audio()
            val file = File(context.filesDir, "download_parts/temp_qq_verified_short.part").apply {
                parentFile!!.mkdirs(); writeBytes(bytes)
            }
            parts.add(file)
            db.downloadDao().insertOrUpdate(DownloadTaskEntity("qq_verified_short", "qq_verified_short", "QQ 完整性回归",
                "", "", null, "tx", "qq_verified_short", "128k", "PUBLISHING", durationMs = 2000,
                totalBytes = bytes.size.toLong(), downloadedBytes = bytes.size.toLong(), tempFilePath = file.path,
                publishStage = "VERIFIED", publishToken = ticket(bytes)))
            val coordinator = coordinator(manager)
            coordinator.recoverInterrupted()
            coordinator.resumeAndSchedule("qq_verified_short", scheduleNow = false)
            coordinator.runPending()
            val task = db.downloadDao().getTaskById("qq_verified_short")!!
            assertEquals(task.errorMessage, "FAILED", task.status)
            assertNull(task.targetUri)
            assertNull(task.publishStage)
            assertNull(task.publishToken)
            assertTrue(db.localAssetDao().getAssetsForTrack("qq_verified_short").isEmpty())
            assertEquals(20000L, db.trackDao().getTrackById("qq_verified_short")!!.durationMs)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody(Buffer().write(audio(20))))
            coordinator.resumeAndSchedule("qq_verified_short", scheduleNow = false)
            coordinator.runPending()
            val retried = db.downloadDao().getTaskById("qq_verified_short")!!
            assertEquals(retried.errorMessage, "COMPLETED", retried.status)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun registeredShortFilesCannotCompleteDuringRecoveryOrResume() = runBlocking {
        MockWebServer().use { server ->
            val manager = source(server.url("/unused.wav").toString())
            for (status in listOf("PUBLISHING", "PAUSED")) {
                val songId = "qq_registered_${status.lowercase()}"
                seed(songId, 20000, """{"interval":20}""")
                val bytes = audio()
                val uri = publicAudio(bytes)
                db.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = songId, uri = uri.toString(),
                    sourceType = "DOWNLOADED", fileSize = bytes.size.toLong(), mimeType = "audio/wav", format = "wav", isAvailable = false))
                db.downloadDao().insertOrUpdate(DownloadTaskEntity(songId, songId, "QQ 完整性回归",
                    "", "", null, "tx", songId, "128k", status, durationMs = 2000,
                    totalBytes = bytes.size.toLong(), downloadedBytes = bytes.size.toLong(), targetUri = uri.toString(),
                    publishStage = "REGISTERED", publishToken = ticket(bytes)))
                val coordinator = coordinator(manager)
                coordinator.recoverInterrupted()
                if (status == "PAUSED") {
                    coordinator.resumeAndSchedule(songId, scheduleNow = false)
                    coordinator.runPending()
                }
                val result = db.downloadDao().getTaskById(songId)!!
                assertEquals(result.errorMessage, "FAILED", result.status)
                assertNull(result.publishStage)
                assertNull(result.targetUri)
                val asset = db.localAssetDao().getAssetsForTrack(songId).single()
                assertFalse(asset.isAvailable)
                assertFalse(asset.unavailableReason.isNullOrBlank())
                assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                assertEquals(20000L, db.trackDao().getTrackById(songId)!!.durationMs)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun invalidatedCompletedDownloadCanBeReplacedWithoutDeletingItsOldFile() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(audio()))) }
            val manager = source(server.url("/full.wav").toString())
            seed("qq_redownload", 2000, """{"interval":2}""")
            val coordinator = coordinator(manager)
            val id = coordinator.enqueueDownload("qq_redownload", "QQ 完整性回归", "", "", null,
                "tx", "qq_redownload", "128k", durationMs = 2000)
            coordinator.runPending()
            val old = db.downloadDao().getTaskById(id)!!
            assertEquals(old.errorMessage, "COMPLETED", old.status)
            val oldUri = Uri.parse(old.targetUri!!)
            outputs.add(oldUri)
            val oldAsset = db.localAssetDao().getAssetByUri(oldUri.toString())!!
            db.localAssetDao().updateAvailability(oldAsset.id, false, "音频不完整")
            assertEquals(id, coordinator.enqueueDownload("qq_redownload", "QQ 完整性回归", "", "", null,
                "tx", "qq_redownload", "128k", durationMs = 2000))
            assertEquals("PENDING", db.downloadDao().getTaskById(id)!!.status)
            coordinator.runPending()
            val updated = db.downloadDao().getTaskById(id)!!
            assertEquals(updated.errorMessage, "COMPLETED", updated.status)
            assertNotEquals(old.targetUri, updated.targetUri)
            assertArrayEquals(audio(), context.contentResolver.openInputStream(oldUri)!!.use { it.readBytes() })
            assertFalse(db.localAssetDao().getAssetByUri(oldUri.toString())!!.isAvailable)
            assertTrue(db.localAssetDao().getAssetByUri(updated.targetUri!!)!!.isAvailable)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun cancellingAnExposedRegisteredPreviewRetainsTheFileWithoutCompletingIt() = runBlocking {
        MockWebServer().use { server ->
            val coordinator = coordinator(source(server.url("/unused.wav").toString()))
            coordinator.recoverInterrupted()
            val id = "qq_cancel_registered_short"
            val bytes = audio(2)
            val uri = registeredPausedDownload(id, bytes)
            coordinator.cancelAndWait(id)

            assertNull("Cancelling the invalid publication must remove the task", db.downloadDao().getTaskById(id))
            val asset = db.localAssetDao().getAssetByUri(uri.toString())!!
            assertFalse("Cancelling must not make a preview available for playback", asset.isAvailable)
            assertTrue(asset.unavailableReason.orEmpty().contains("完整"))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals(20000L, db.trackDao().getTrackById(id)!!.durationMs)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun cancellingAnExposedCompleteRegisteredDownloadStillFinalizesIt() = runBlocking {
        MockWebServer().use { server ->
            val coordinator = coordinator(source(server.url("/unused.wav").toString()))
            coordinator.recoverInterrupted()
            val id = "qq_cancel_registered_full"
            val bytes = audio(20)
            val uri = registeredPausedDownload(id, bytes)
            coordinator.cancelAndWait(id)

            val completed = db.downloadDao().getTaskById(id)!!
            assertEquals(completed.errorMessage, "COMPLETED", completed.status)
            assertEquals(uri.toString(), completed.targetUri)
            assertEquals(20000L, completed.durationMs)
            assertTrue(db.localAssetDao().getAssetByUri(uri.toString())!!.isAvailable)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals(20000L, db.trackDao().getTrackById(id)!!.durationMs)
            assertEquals(0, server.requestCount)
        }
    }

    private suspend fun registeredPausedDownload(id: String, bytes: ByteArray): Uri {
        seed(id, 20000, """{"interval":20}""")
        val uri = publicAudio(bytes)
        db.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = id, uri = uri.toString(),
            sourceType = "DOWNLOADED", fileSize = bytes.size.toLong(), mimeType = "audio/wav", format = "wav", isAvailable = false))
        db.downloadDao().insertOrUpdate(DownloadTaskEntity(id, id, "QQ 完整性回归", "", "", null,
            "tx", id, "128k", "PAUSED", durationMs = 2000, totalBytes = bytes.size.toLong(),
            downloadedBytes = bytes.size.toLong(), targetUri = uri.toString(),
            publishStage = "REGISTERED", publishToken = ticket(bytes)))
        return uri
    }

    private fun ticket(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return """{"id":"${UUID.randomUUID()}","sha256":"$hash"}"""
    }

    private fun publicAudio(bytes: ByteArray): Uri {
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "qq-completeness-${UUID.randomUUID()}.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/PickAudio")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            })!!
        outputs.add(uri)
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
