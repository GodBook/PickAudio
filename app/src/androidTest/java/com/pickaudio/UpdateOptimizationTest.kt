package com.pickaudio

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.*
import com.pickaudio.data.repository.LibraryRepository
import com.pickaudio.download.*
import com.pickaudio.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import com.pickaudio.playback.resolveAudioCacheKey
import com.pickaudio.playback.invalidateAudioCacheProbes
import com.pickaudio.network.NetworkPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

@RunWith(AndroidJUnit4::class)
class UpdateOptimizationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()

    @Test fun unchangedAudioFactsAndScopedLibraryAvoidUnrelatedRows() = runBlocking<Unit> {
        val db = database()
        try {
            db.trackDao().insertOrUpdate(TrackEntity("in", "曲库", "", "", 1000, null, isInLibrary = true))
            db.trackDao().insertOrUpdate(TrackEntity("out", "试听", "", "", 1000, null))
            val id = db.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = "in", uri = "file:///fixture-in", sourceType = "SAF_FILE", fileSize = 1, mimeType = null, format = null, audioInfoJson = "same"))
            db.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = "out", uri = "file:///fixture-out", sourceType = "SAF_FILE", fileSize = 1, mimeType = null, format = null))
            assertEquals(listOf("in"), db.localAssetDao().getLibraryAssets().first().map { it.trackId })
            assertEquals(0, db.localAssetDao().updateAudioInfo(id, "same"))
            assertEquals(1, db.localAssetDao().updateAudioInfo(id, "new"))
        } finally { db.close() }
    }

    @Test fun batchCommandsCountSkippedRowsAndScheduleOnce() = runBlocking<Unit> {
        val db = database()
        LxSourceManager(context, db).use { sources ->
            try {
                sources.ensureBuiltinSources()
                val schedules = AtomicInteger()
                val downloads = DownloadCoordinator(context, db, sources, scheduleOverride = { schedules.incrementAndGet(); Unit })
                downloads.recoverInterrupted()
                db.trackDao().insertOrUpdate(TrackEntity("batch", "批量", "", "", 1000, null))
                for ((id, status) in listOf("paused" to "PAUSED", "failed" to "FAILED", "completed" to "COMPLETED"))
                    db.downloadDao().insertOrUpdate(DownloadTaskEntity(id, "batch", "批量", "", "", null, "wy", id, "128k", status))
                val report = downloads.controlBatch(listOf("paused", "failed", "completed", "missing"), DownloadCommand.RESUME)
                assertEquals(2, report.changed); assertEquals(2, report.skipped)
                assertTrue(report.failures.isEmpty()); assertEquals(1, schedules.get())
                assertEquals("PENDING", db.downloadDao().getTaskById("paused")!!.status)
                val paused = downloads.controlBatch(listOf("paused", "failed"), DownloadCommand.PAUSE)
                assertEquals(2, paused.changed)
            } finally { db.close() }
        }
    }

    @Test fun completedFileChecksLeaveMainThreadResponsive() = runBlocking<Unit> {
        val db = database()
        val authority = "com.pickaudio.test.optimization.documents"
        val provider = Uri.parse("content://$authority")
        context.contentResolver.call(provider, "seed", null, Bundle().apply { putInt("songs", 1) })
        val uri = DocumentsContract.buildDocumentUri(authority, "audio_0")
        LxSourceManager(context, db).use { sources ->
            try {
                sources.ensureBuiltinSources()
                val downloads = DownloadCoordinator(context, db, sources, scheduleOverride = {})
                downloads.recoverInterrupted()
                db.trackDao().insertOrUpdate(TrackEntity("slow", "核验", "", "", 1000, null))
                repeat(100) { db.downloadDao().insertOrUpdate(DownloadTaskEntity("check_$it", "slow", "核验", "", "", null, "wy", "$it", "128k", "COMPLETED", targetUri = uri.toString())) }
                context.contentResolver.call(provider, "delay", null, Bundle().apply { putLong("milliseconds", 10) })
                val checking = async(Dispatchers.Main) { downloads.refreshCompletedFiles() }
                delay(150)
                val ping = CompletableDeferred<Unit>()
                Handler(Looper.getMainLooper()).post { ping.complete(Unit) }
                withTimeout(300) { ping.await() }
                checking.await()
                assertEquals(100, db.downloadDao().getCompletedTasks().size)
            } finally {
                context.contentResolver.call(provider, "delay", null, Bundle())
                context.contentResolver.call(provider, "clear", null, null)
                db.close()
            }
        }
    }

    @Test fun repeatedFolderImportDoesNotReopenUnchangedAudio() = runBlocking<Unit> {
        val db = database()
        val provider = Uri.parse("content://com.pickaudio.test.optimization.documents")
        val tree = DocumentsContract.buildTreeDocumentUri(provider.authority!!, "root")
        context.contentResolver.call(provider, "seed", null, Bundle().apply { putInt("songs", 10) })
        try {
            val library = LibraryRepository(context, db)
            assertEquals(10, library.importSafDirectory(tree))
            val before = context.contentResolver.call(provider, "stats", null, null)!!.getInt("audioOpens")
            assertTrue(before > 0)
            assertEquals(0, library.importSafDirectory(tree))
            assertEquals(before, context.contentResolver.call(provider, "stats", null, null)!!.getInt("audioOpens"))
            assertEquals(10, library.getAllTracks().first().size)
        } finally { context.contentResolver.call(provider, "clear", null, null); db.close() }
    }

    @Test fun promiseNotificationsWakeAllConcurrentWaitersAndRemainCancellable() = runBlocking<Unit> {
        QuickJsEngine().use { engine ->
            engine.evaluate("globalThis.finish = []; undefined;")
            val one = async(Dispatchers.Default) { engine.evaluateAsync("new Promise(resolve => finish.push(() => resolve('one')))") }
            val two = async(Dispatchers.Default) { engine.evaluateAsync("new Promise(resolve => finish.push(() => resolve('two')))") }
            withTimeout(3000) { while (engine.evaluate("finish.length") != "2") delay(10) }
            engine.evaluate("finish.forEach(fn => fn()); undefined;")
            assertEquals(setOf("\"one\"", "\"two\""), withTimeout(1000) { setOf(one.await(), two.await()) })
            val pending = async(Dispatchers.Default) { engine.evaluateAsync("new Promise(() => {})") }
            delay(100)
            withTimeout(500) { pending.cancelAndJoin() }
        }
    }

    @Test fun exactResourceProbeIsReusedAndInvalidationGetsNewValidator() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val client = NetworkPolicy(setOf("localhost", "127.0.0.1")).client()
            val resource = LxSourceManager.MusicResource(server.url("/audio").toString(), "probe-fixture")
            server.enqueue(MockResponse().setHeader("ETag", "\"first\""))
            invalidateAudioCacheProbes()
            val first = resolveAudioCacheKey(client, resource, "wy", "fixture", "128k")
            assertEquals(first, resolveAudioCacheKey(client, resource, "wy", "fixture", "128k"))
            assertEquals(1, server.requestCount)
            invalidateAudioCacheProbes()
            server.enqueue(MockResponse().setHeader("ETag", "\"changed\""))
            assertNotEquals(first, resolveAudioCacheKey(client, resource, "wy", "fixture", "128k"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun unrelatedPreferencesDoNotEmitCacheOrRecentValues() = runBlocking<Unit> {
        val preferences = (context.applicationContext as PickAudioApplication).userPreferences
        val original = preferences.exportSettings()
        val cacheEvents = AtomicInteger()
        val recentEvents = AtomicInteger()
        val cacheJob = launch(Dispatchers.IO) { preferences.audioCacheMegabytes.collect { cacheEvents.incrementAndGet() } }
        val recentJob = launch(Dispatchers.IO) { preferences.recentTrackIds.collect { recentEvents.incrementAndGet() } }
        try {
            withTimeout(3000) { while (cacheEvents.get() == 0 || recentEvents.get() == 0) delay(10) }
            val oldSize = preferences.lyricFontSize.first()
            preferences.setLyricFontSize(if (oldSize == 28) 27 else oldSize + 1)
            preferences.setShowLyricTranslation(!preferences.showLyricTranslation.first())
            delay(200)
            assertEquals(1, cacheEvents.get()); assertEquals(1, recentEvents.get())
        } finally {
            cacheJob.cancelAndJoin(); recentJob.cancelAndJoin()
            preferences.restoreSettings(original)
        }
    }
}
