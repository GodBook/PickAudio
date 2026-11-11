package com.pickaudio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.DownloadTaskEntity
import com.pickaudio.data.db.LocalAssetEntity
import com.pickaudio.data.db.OnlineRefEntity
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.db.TrackEntity
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.download.DownloadRepairReport
import com.pickaudio.network.NetworkPolicy
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QqAutomaticRepairTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PickAudioDatabase
    private val managers = mutableListOf<LxSourceManager>()
    private val outputs = mutableListOf<Uri>()

    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build() }
    @After fun cleanup() = runBlocking {
        db.downloadDao().getAllTasksSync().forEach {
            it.targetUri?.let { value -> outputs.add(Uri.parse(value)) }
            File(context.filesDir, "download_parts/temp_${it.id}.part").delete()
        }
        outputs.distinct().forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        managers.forEach { it.close() }
        db.close()
    }

    private fun audio(seconds: Int): ByteArray = ByteBuffer.allocate(44 + 8000 * 2 * seconds)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(capacity() - 44)
        }.array()

    private suspend fun source(url: String): LxSourceManager {
        val manager = LxSourceManager(context, db, NetworkPolicy(setOf("localhost", "127.0.0.1")))
        managers.add(manager)
        manager.ensureBuiltinSources()
        val imported = manager.importSourceFromCode("""
            lx.on(lx.EVENT_NAMES.request, () => Promise.resolve("$url"));
            lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{tx:{name:'自动修复验证',actions:['musicUrl'],qualitys:['128k']}}});
        """.trimIndent())
        manager.selectSourceForPlatform("tx", imported.id)
        return manager
    }

    private fun publicAudio(bytes: ByteArray): Uri {
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "qq-repair-${UUID.randomUUID()}.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/PickAudio")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            })!!
        outputs.add(uri)
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private suspend fun completed(bytes: ByteArray = audio(2), expected: Int = 20,
        sourceType: String = "DOWNLOADED", quality: String = "128k", status: String = "COMPLETED", uriOverride: Uri? = null): DownloadTaskEntity {
        val id = "qq_auto_${UUID.randomUUID()}"
        val uri = uriOverride ?: publicAudio(bytes)
        val metadata = """{"songid":123,"strMediaMid":"fixture","interval":$expected}"""
        db.trackDao().insertOrUpdate(TrackEntity(id, "自动修复回归", "测试", "", expected * 1000L, null))
        db.onlineRefDao().insertOrUpdate(OnlineRefEntity(trackId = id, platform = "tx", platformSongId = id,
            platformMetadataJson = metadata))
        db.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = id, uri = uri.toString(), sourceType = sourceType,
            fileSize = bytes.size.toLong(), mimeType = "audio/wav", format = "wav"))
        return DownloadTaskEntity(id, id, "自动修复回归", "测试", "", null, "tx", id, quality, status,
            downloadedBytes = bytes.size.toLong(), totalBytes = bytes.size.toLong(), durationMs = if (expected > 0) 2000 else 0,
            targetUri = uri.toString(), publishStage = "COMPLETED").also { db.downloadDao().insertOrUpdate(it) }
    }

    @Test fun detectedPreviewIsDownloadedOnceAndTheOriginalFileIsKept() = runBlocking {
        MockWebServer().use { server ->
            val coordinator = DownloadCoordinator(context, db, source(server.url("/full.wav").toString()), scheduleOverride = {})
            coordinator.recoverInterrupted()
            val old = completed()
            val report = coordinator.repairIncompleteDownloads(scheduleNow = false)
            assertEquals(1, report.checked)
            assertEquals(1, report.requeued)
            assertTrue(report.errors.toString(), report.errors.isEmpty())
            val pending = db.downloadDao().getTaskById(old.id)!!
            assertEquals("PENDING", pending.status)
            assertEquals(old.targetQuality, pending.targetQuality)
            assertEquals(20000L, pending.durationMs)
            assertNull(pending.targetUri)
            assertFalse(db.localAssetDao().getAssetByUri(old.targetUri!!)!!.isAvailable)
            assertArrayEquals(audio(2), context.contentResolver.openInputStream(Uri.parse(old.targetUri))!!.use { it.readBytes() })
            assertEquals(0, coordinator.repairIncompleteDownloads(scheduleNow = false).requeued)
            server.enqueue(MockResponse().setBody(Buffer().write(audio(20))))
            coordinator.runPending()
            val repaired = db.downloadDao().getTaskById(old.id)!!
            assertEquals(repaired.errorMessage, "COMPLETED", repaired.status)
            assertNotEquals(old.targetUri, repaired.targetUri)
            assertArrayEquals(audio(20), context.contentResolver.openInputStream(Uri.parse(repaired.targetUri!!))!!.use { it.readBytes() })
            assertArrayEquals(audio(2), context.contentResolver.openInputStream(Uri.parse(old.targetUri))!!.use { it.readBytes() })
            assertEquals(20000L, db.trackDao().getTrackById(old.trackId)!!.durationMs)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun normalFilesAreMemoizedButExternalTruncationAndForceScanAreRechecked() = runBlocking {
        MockWebServer().use { server ->
            val coordinator = DownloadCoordinator(context, db, source(server.url("/full.wav").toString()), scheduleOverride = {})
            coordinator.recoverInterrupted()
            val old = completed(audio(20))
            val first = coordinator.repairIncompleteDownloads(scheduleNow = false)
            assertEquals(1, first.checked)
            assertEquals(0, first.requeued)
            assertEquals(old, db.downloadDao().getTaskById(old.id))
            val cached = coordinator.repairIncompleteDownloads(scheduleNow = false)
            assertEquals(0, cached.checked)
            assertEquals(1, cached.skipped)
            assertEquals(1, coordinator.repairIncompleteDownloads(scheduleNow = false, forceScan = true).checked)
            // Mutate the actual provider file without updating our task or local asset records.
            context.contentResolver.openOutputStream(Uri.parse(old.targetUri), "wt")!!.use { it.write(audio(2)) }
            val changed = coordinator.repairIncompleteDownloads(scheduleNow = false)
            assertEquals(changed.toString(), 1, changed.checked)
            assertEquals(changed.toString(), 1, changed.requeued)
        }
    }

    @Test fun aFileChangedDuringInspectionIsRecheckedWithinTheSameScan() = runBlocking {
        MockWebServer().use { server ->
            var inspections = 0
            val coordinator = DownloadCoordinator(context, db, source(server.url("/unused.wav").toString()), scheduleOverride = {},
                repairCheckpoint = { stage, taskId ->
                    if (stage == "INSPECTED" && inspections++ == 0) {
                        val target = db.downloadDao().getTaskById(taskId)!!.targetUri!!
                        context.contentResolver.openOutputStream(Uri.parse(target), "wt")!!.use { it.write(audio(2)) }
                    }
                })
            coordinator.recoverInterrupted()
            val old = completed(audio(20))
            val report = coordinator.repairIncompleteDownloads(scheduleNow = false)
            assertEquals(report.toString(), 1, report.checked)
            assertEquals(report.toString(), 1, report.requeued)
            assertEquals(2, inspections)
            assertEquals("PENDING", db.downloadDao().getTaskById(old.id)!!.status)
            assertArrayEquals(audio(2), context.contentResolver.openInputStream(Uri.parse(old.targetUri!!))!!.use { it.readBytes() })
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun unsuccessfulAutomaticReplacementStaysFailedAndIsNotAutomaticallyRetried() = runBlocking {
        MockWebServer().use { server ->
            val coordinator = DownloadCoordinator(context, db, source(server.url("/short.wav").toString()), scheduleOverride = {})
            coordinator.recoverInterrupted()
            val old = completed()
            assertEquals(1, coordinator.repairIncompleteDownloads(scheduleNow = false).requeued)
            server.enqueue(MockResponse().setBody(Buffer().write(audio(2))))
            coordinator.runPending()
            val failed = db.downloadDao().getTaskById(old.id)!!
            assertEquals("FAILED", failed.status)
            assertEquals(0, coordinator.repairIncompleteDownloads(scheduleNow = false, forceScan = true).requeued)
            assertEquals(failed, db.downloadDao().getTaskById(old.id))
            assertEquals(1, server.requestCount)
            assertArrayEquals(audio(2), context.contentResolver.openInputStream(Uri.parse(old.targetUri))!!.use { it.readBytes() })
        }
    }

    @Test fun unknownDurationsImportsUnreadableProvidersAndUserStoppedTasksAreLeftAlone() = runBlocking {
        MockWebServer().use { server ->
            val coordinator = DownloadCoordinator(context, db, source(server.url("/unused.wav").toString()), scheduleOverride = {})
            coordinator.recoverInterrupted()
            val records = listOf(completed(expected = 0), completed(sourceType = "SAF_FILE"),
                completed(uriOverride = Uri.parse("content://not-a-media-owner/private/song")),
                completed(status = "PAUSED"), completed(status = "FAILED"), completed(status = "CANCELLED"))
            val report = coordinator.repairIncompleteDownloads(scheduleNow = false)
            assertEquals(0, report.checked)
            assertEquals(0, report.requeued)
            for (record in records) assertEquals(record, db.downloadDao().getTaskById(record.id))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun repeatedConcurrentScansReuseTheOriginalTaskInsteadOfDuplicatingIt() = runBlocking {
        MockWebServer().use { server ->
            val coordinator = DownloadCoordinator(context, db, source(server.url("/unused.wav").toString()), scheduleOverride = {})
            coordinator.recoverInterrupted()
            val old = completed()
            val reports: List<DownloadRepairReport> = coroutineScope {
                listOf(async { coordinator.repairIncompleteDownloads(scheduleNow = false) },
                    async { coordinator.repairIncompleteDownloads(scheduleNow = false) }).awaitAll()
            }
            assertEquals(1, reports.sumOf { it.requeued })
            assertEquals(1, db.downloadDao().getAllTasksSync().size)
            assertEquals(old.executionGeneration + 1, db.downloadDao().getTaskById(old.id)!!.executionGeneration)
        }
    }

    @Test fun unsupportedOriginalQualityIsReportedWithoutPretendingTheOldPreviewIsComplete() = runBlocking {
        MockWebServer().use { server ->
            val coordinator = DownloadCoordinator(context, db, source(server.url("/unused.wav").toString()), scheduleOverride = {})
            coordinator.recoverInterrupted()
            val old = completed(quality = "flac")
            val report = coordinator.repairIncompleteDownloads(scheduleNow = false)
            assertEquals(0, report.requeued)
            assertEquals(1, report.errors.size)
            val failed = db.downloadDao().getTaskById(old.id)!!
            assertEquals("FAILED", failed.status)
            assertTrue(failed.errorMessage.orEmpty().contains("不支持"))
            assertFalse(db.localAssetDao().getAssetByUri(old.targetUri!!)!!.isAvailable)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun anUnrecognizedDecoderFailureDoesNotInvalidateOrRequeueTheFile() = runBlocking {
        MockWebServer().use { server ->
            val coordinator = DownloadCoordinator(context, db, source(server.url("/unused.wav").toString()), scheduleOverride = {})
            coordinator.recoverInterrupted()
            val old = completed(bytes = "an unavailable or unsupported decoder response".toByteArray())
            val report = coordinator.repairIncompleteDownloads(scheduleNow = false)
            assertEquals(1, report.checked)
            assertEquals(0, report.requeued)
            assertEquals(1, report.errors.size)
            assertEquals(old, db.downloadDao().getTaskById(old.id))
            assertTrue(db.localAssetDao().getAssetByUri(old.targetUri!!)!!.isAvailable)
            assertEquals(1, coordinator.repairIncompleteDownloads(scheduleNow = false).checked)
        }
    }

    @Test fun staleInspectionCannotOverwriteAUserReplacementThatWasPausedOrCancelled() = runBlocking {
        MockWebServer().use { server ->
            val manager = source(server.url("/unused.wav").toString())
            for (cancel in listOf(false, true)) {
                lateinit var coordinator: DownloadCoordinator
                coordinator = DownloadCoordinator(context, db, manager, scheduleOverride = {}, repairCheckpoint = { stage, taskId ->
                    if (stage == "INSPECTED") {
                        val current = db.downloadDao().getTaskById(taskId)!!
                        coordinator.enqueueDownload(current.trackId, current.title, current.artist, current.album, current.coverUri,
                            current.platform, current.platformSongId, current.targetQuality, durationMs = 20000, scheduleNow = false)
                        if (cancel) coordinator.cancelAndWait(taskId) else coordinator.pauseAndWait(taskId)
                    }
                })
                coordinator.recoverInterrupted()
                val old = completed()
                // Playback may already have identified the old downloaded preview before this scan.
                val asset = db.localAssetDao().getAssetByUri(old.targetUri!!)!!
                db.localAssetDao().updateAvailability(asset.id, false, "音频不完整")
                val report = coordinator.repairIncompleteDownloads(scheduleNow = false)
                assertEquals(0, report.requeued)
                assertEquals(if (cancel) null else "PAUSED", db.downloadDao().getTaskById(old.id)?.status)
                assertArrayEquals(audio(2), context.contentResolver.openInputStream(Uri.parse(old.targetUri))!!.use { it.readBytes() })
            }
        }
    }

    @Test fun userPauseAndCancelAfterRepairQueueingAreNotOverwrittenByAnotherScan() = runBlocking {
        MockWebServer().use { server ->
            val manager = source(server.url("/unused.wav").toString())
            for (cancel in listOf(false, true)) {
                lateinit var coordinator: DownloadCoordinator
                coordinator = DownloadCoordinator(context, db, manager, scheduleOverride = {}, repairCheckpoint = { stage, taskId ->
                    if (stage == "REQUEUED") {
                        if (cancel) coordinator.cancelAndWait(taskId) else coordinator.pauseAndWait(taskId)
                    }
                })
                coordinator.recoverInterrupted()
                val old = completed()
                assertEquals(1, coordinator.repairIncompleteDownloads(scheduleNow = false).requeued)
                assertEquals(if (cancel) null else "PAUSED", db.downloadDao().getTaskById(old.id)?.status)
                assertEquals(0, coordinator.repairIncompleteDownloads(scheduleNow = false, forceScan = true).requeued)
                assertEquals(if (cancel) null else "PAUSED", db.downloadDao().getTaskById(old.id)?.status)
                assertArrayEquals(audio(2), context.contentResolver.openInputStream(Uri.parse(old.targetUri))!!.use { it.readBytes() })
            }
            assertEquals(0, server.requestCount)
        }
    }
}
