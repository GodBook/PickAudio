package com.pickaudio

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.*
import com.pickaudio.data.repository.LibraryRepository
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.source.LxSourceManager
import com.pickaudio.network.NetworkPolicy
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OptimizationDownloadTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PickAudioDatabase
    private val published = mutableListOf<Uri>()
    private val sourceManagers = mutableListOf<LxSourceManager>()
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build() }
    @After fun cleanup() = runBlocking {
        db.downloadDao().getAllTasksSync().mapNotNull { it.targetUri }.forEach { published.add(Uri.parse(it)) }
        published.distinct().forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        sourceManagers.forEach { it.close() }
        db.close()
    }
    private fun audio(seconds: Int = 2): ByteArray = ByteBuffer.allocate(44 + 44100 * 2 * seconds)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(capacity() - 44)
        }.array()
    private suspend fun source(url: String): LxSourceManager {
        val manager = LxSourceManager(context, db, NetworkPolicy(setOf("localhost", "127.0.0.1")))
        sourceManagers.add(manager)
        manager.ensureBuiltinSources()
        val script = """
            lx.on(lx.EVENT_NAMES.request, () => Promise.resolve("$url"));
            lx.send(lx.EVENT_NAMES.inited, {status: true, sources: {wy: {name: '验证', actions: ['musicUrl'], qualitys: ['128k']}}});
        """.trimIndent()
        val imported = manager.importSourceFromCode(script)
        manager.selectSourceForPlatform("wy", imported.id)
        return manager
    }
    private suspend fun seed(id: String = "optimization_download") {
        db.trackDao().insertOrUpdate(TrackEntity(id, "验证音频", "", "", 2000, null))
    }
    private suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(12000) { while (!condition()) delay(30) }

    @Test fun pauseResumeAndNewTasksUseOneWorkerPerLease() = runBlocking {
        MockWebServer().use { server ->
            val bytes = audio()
            repeat(4) { server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("ETag", "\"stable\"")
                .throttleBody(8192, 10, TimeUnit.MILLISECONDS)) }
            val manager = source(server.url("/audio").toString())
            seed()
            val coordinator = DownloadCoordinator(context, db, manager, scheduleOverride = {})
            coordinator.recoverInterrupted()
            val id = coordinator.enqueueDownload("optimization_download", "验证音频", "", "", null, "wy", "1", "128k")
            val runner = async { coordinator.runPending() }
            waitFor { db.downloadDao().getTaskById(id)?.status == "DOWNLOADING" }
            coordinator.pauseAndWait(id)
            val paused = db.downloadDao().getTaskById(id)!!
            assertEquals("PAUSED", paused.status)
            coordinator.resumeAndSchedule(id)
            runner.await()
            if (db.downloadDao().getTaskById(id)?.status == "PENDING") coordinator.runPending()
            val result = db.downloadDao().getTaskById(id)!!
            assertEquals(result.errorMessage, "COMPLETED", result.status)
            assertTrue(result.executionGeneration > paused.executionGeneration)
            assertEquals(1, db.localAssetDao().getAssetsForTrack("optimization_download").size)
            assertFalse(File(coordinator.partialDirectory, "temp_$id.part").exists())
        }
    }

    private class ProcessStopped : Error("simulated process interruption")
    @Test fun interruptionAfterExposureCompletesOnceOnRecovery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(audio())))
            val manager = source(server.url("/audio").toString())
            seed()
            val coordinator = DownloadCoordinator(context, db, manager, scheduleOverride = {}, publicationCheckpoint = {
                if (it == "EXPOSED") throw ProcessStopped()
            })
            coordinator.recoverInterrupted()
            val id = coordinator.enqueueDownload("optimization_download", "恢复音频", "", "", null, "wy", "2", "128k")
            assertTrue(runCatching { coordinator.runPending() }.exceptionOrNull() is ProcessStopped)
            val interrupted = db.downloadDao().getTaskById(id)!!
            assertEquals("REGISTERED", interrupted.publishStage)
            assertNotNull(interrupted.targetUri)
            val restored = DownloadCoordinator(context, db, manager, scheduleOverride = {})
            restored.recoverInterrupted()
            val finished = db.downloadDao().getTaskById(id)!!
            assertEquals("COMPLETED", finished.status)
            assertEquals(interrupted.targetUri, finished.targetUri)
            assertTrue(db.localAssetDao().getAssetsForTrack("optimization_download").single().isAvailable)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun removedCompletedFileCanBeDownloadedAgain() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(audio()))) }
            val manager = source(server.url("/audio").toString())
            seed()
            val coordinator = DownloadCoordinator(context, db, manager, scheduleOverride = {})
            coordinator.recoverInterrupted()
            val id = coordinator.enqueueDownload("optimization_download", "重新下载", "", "", null, "wy", "3", "128k")
            coordinator.runPending()
            val oldUri = db.downloadDao().getTaskById(id)!!.targetUri!!
            assertTrue(context.contentResolver.delete(Uri.parse(oldUri), null, null) > 0)
            assertEquals(id, coordinator.enqueueDownload("optimization_download", "重新下载", "", "", null, "wy", "3", "128k"))
            coordinator.runPending()
            val result = db.downloadDao().getTaskById(id)!!
            assertEquals(result.errorMessage, "COMPLETED", result.status)
            assertNotEquals(oldUri, result.targetUri)
        }
    }

    @Test fun failedFileDeletionRetainsAssociationsAndNeverDeletesDirectory() = runBlocking {
        seed("deletion_song")
        db.favoriteDao().addFavorite(FavoriteEntity("deletion_song", 123))
        val directory = File.createTempFile("optimization-directory-", ".tmp", context.cacheDir).apply { delete(); mkdir() }
        try {
            db.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = "deletion_song", uri = Uri.fromFile(directory).toString(),
                sourceType = "SAF_FILE", fileSize = 0, mimeType = "audio/wav", format = "wav"))
            val repository = LibraryRepository(context, db)
            val result = repository.deleteTrackWithReport("deletion_song", true)
            assertFalse(result.recordRemoved)
            assertEquals(1, result.failedFiles.size)
            assertTrue(directory.isDirectory)
            assertNotNull(db.trackDao().getTrackById("deletion_song"))
            assertTrue(db.favoriteDao().isFavoriteSync("deletion_song"))
            assertTrue(repository.deleteTrack("deletion_song", false))
            assertFalse(db.favoriteDao().isFavoriteSync("deletion_song"))
        } finally { directory.delete() }
    }

    @Test fun deletingTrackBlocksNewDownloadsUntilItsWorkerAndRecordsAreRemoved() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(audio(6))).throttleBody(4096, 10, TimeUnit.MILLISECONDS))
            val manager = source(server.url("/audio").toString())
            seed()
            val coordinator = DownloadCoordinator(context, db, manager, scheduleOverride = {})
            coordinator.recoverInterrupted()
            val id = coordinator.enqueueDownload("optimization_download", "删除期间", "", "", null, "wy", "4", "128k")
            val runner = async { coordinator.runPending() }
            waitFor { db.downloadDao().getTaskById(id)?.status == "DOWNLOADING" }
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val repository = LibraryRepository(context, db,
                beforeRecordDelete = { entered.complete(Unit); finish.await() },
                beforeTrackOperation = coordinator::suspendTrackOperations,
                afterTrackOperation = coordinator::releaseTrackOperations)
            val removal = async { repository.deleteTrackWithReport("optimization_download", true) }
            withTimeout(5000) { entered.await() }
            val error = runCatching { coordinator.enqueueDownload("optimization_download", "删除期间", "", "", null, "wy", "4", "128k") }.exceptionOrNull()
            assertTrue(error?.message.orEmpty(), error?.message?.contains("正在整理") == true)
            finish.complete(Unit)
            assertTrue(removal.await().recordRemoved)
            runner.await()
            assertNull(db.trackDao().getTrackById("optimization_download"))
            assertTrue(db.downloadDao().getAllTasksSync().isEmpty())
            assertFalse(File(coordinator.partialDirectory, "temp_$id.part").exists())
        }
    }
}
