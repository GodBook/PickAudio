package com.pickaudio

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.*
import com.pickaudio.data.model.Track
import com.pickaudio.data.repository.*
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OptimizationOperationsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PickAudioDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build() }
    @After fun cleanup() { db.close() }
    private fun track(id: String) = Track(id, "歌曲$id", "", "", 2000)

    @Test fun missingCompletedFileMovesToRepairAndCanBeRequeued() = runBlocking {
        db.trackDao().insertOrUpdate(TrackEntity("gone", "失效下载专项", "", "", 1000, null))
        val uri = "file://${context.cacheDir}/optimization-file-never-created.wav"
        db.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = "gone", uri = uri, sourceType = "DOWNLOADED", fileSize = 1, mimeType = "audio/wav", format = "wav"))
        db.downloadDao().insertOrUpdate(DownloadTaskEntity("gone_task", "gone", "失效下载专项", "", "", null,
            "wy", "gone", "128k", "COMPLETED", targetUri = uri))
        val sources = LxSourceManager(context, db)
        try {
            sources.ensureBuiltinSources()
            val coordinator = DownloadCoordinator(context, db, sources, scheduleOverride = {})
            coordinator.recoverInterrupted()
            val task = db.downloadDao().getTaskById("gone_task")!!
            assertEquals("FAILED", task.status); assertNull(task.targetUri)
            assertFalse(db.localAssetDao().getAssetsForTrack("gone").single().isAvailable)
            assertNotNull(LibraryRepository(context, db).getTrack("gone")!!.repairReason)
            val id = coordinator.enqueueDownload("gone", "失效下载专项", "", "", null, "wy", "gone", "128k")
            assertEquals("gone_task", id); assertEquals("PENDING", db.downloadDao().getTaskById(id)!!.status)
        } finally { sources.close() }
    }

    @Test fun diagnosticsExportIncludesCountsWithoutPrivateSongNamesOrUris() = runBlocking {
        db.trackDao().insertOrUpdate(TrackEntity("private", "private-song-title", "private-artist", "", 1, null))
        db.downloadDao().insertOrUpdate(DownloadTaskEntity("private_task", "private", "private-song-title", "", "", null,
            "wy", "private-id", "128k", "PAUSED", targetUri = "content://private/audio?credential=hidden"))
        val file = File.createTempFile("optimization-diagnostics-", ".json", context.cacheDir)
        try {
            com.pickaudio.playback.DiagnosticReport.export(context, db, com.pickaudio.data.preferences.UserPreferences(context), Uri.fromFile(file))
            val content = file.readText()
            val report = org.json.JSONObject(content)
            assertEquals(1, report.getInt("tracks")); assertEquals(1, report.getJSONObject("downloadStates").getInt("PAUSED"))
            assertFalse(content.contains("private-song-title")); assertFalse(content.contains("credential")); assertFalse(content.contains("content://"))
        } finally { file.delete() }
    }

    @Test fun undoRestoresMiddlePositionAndTimeAndStaleReorderIsRejected() = runBlocking {
        val repo = PlaylistRepository(db)
        val id = repo.createPlaylist("撤销专项")
        repo.addTracks(id, listOf(track("A"), track("B"), track("C")))
        val original = db.playlistDao().getMembersSync(id)
        val token = repo.removeWithUndo(id, listOf("B"))
        repo.addTracks(id, listOf(track("D")))
        assertEquals(1, repo.restoreRemoved(token))
        val restored = db.playlistDao().getMembersSync(id)
        assertEquals(listOf("A", "B", "C", "D"), restored.map { it.trackId })
        assertEquals(original[1].addedAt, restored[1].addedAt)
        assertEquals(0, repo.restoreRemoved(token))
        assertTrue(runCatching { repo.reorderTracks(id, listOf("D", "C", "B", "A"), listOf("A", "B", "C")) }.isFailure)
        assertEquals(listOf("A", "B", "C", "D"), db.playlistDao().getMembersSync(id).map { it.trackId })
        db.favoriteDao().addFavorite(FavoriteEntity("A", 123))
        db.favoriteDao().addFavorite(FavoriteEntity("B", 456))
        repo.reorderTracks(PickAudioDatabase.FAVORITE_PLAYLIST_ID, listOf("A", "B"))
        val favorites = db.favoriteDao().getFavoritesSync()
        assertEquals(listOf(123L, 456L), favorites.map { it.addedAt })
    }

    @Test fun searchTrackReusesCanonicalReferenceAndBatchAddReportsDuplicates() = runBlocking {
        db.trackDao().insertOrUpdate(TrackEntity("stable", "原始标题", "", "", 2000, null))
        db.onlineRefDao().insertOrUpdate(OnlineRefEntity(trackId = "stable", platform = "wy", platformSongId = "1", platformMetadataJson = "{}"))
        val temp = track("online_wy_1").copy(platform = "wy", platformSongId = "1")
        val repo = PlaylistRepository(db)
        val id = repo.createPlaylist("身份专项")
        val report = repo.addTracks(id, listOf(temp, temp))
        assertEquals(1, report.added); assertEquals(1, report.duplicates)
        assertEquals("stable", db.playlistDao().getMembersSync(id).single().trackId)
        assertEquals("stable", db.onlineRefDao().getByPlatformId("wy", "1")!!.trackId)
        assertNull(db.trackDao().getTrackById("online_wy_1"))
        assertEquals("stable", db.ensureTrackIdentity(temp).id)
    }

    @Test fun oneHundredDownloadsReportThreeFailuresAndRetryOnlyThose() = runBlocking {
        val sources = LxSourceManager(context, db)
        try {
            sources.ensureBuiltinSources()
            val coordinator = DownloadCoordinator(context, db, sources, scheduleOverride = {})
            val tracks = (0 until 100).map { track("batch_$it").copy(platform = "wy", platformSongId = "batch_$it") }
            val blocked = tracks.take(3).map { it.id }.toSet()
            coordinator.suspendTrackOperations(blocked)
            val report = coordinator.enqueueBatch(tracks, "128k")
            assertEquals(97, report.added); assertEquals(3, report.failures.size)
            assertEquals(97, db.downloadDao().getAllTasksSync().size)
            coordinator.releaseTrackOperations(blocked)
            val retry = coordinator.enqueueBatch(report.failures.map { it.track }, "128k")
            assertEquals(3, retry.added); assertTrue(retry.failures.isEmpty())
            assertEquals(100, db.downloadDao().getAllTasksSync().size)
            assertEquals(100, coordinator.enqueueBatch(tracks, "128k").existing)
        } finally { sources.close() }
    }

    @Test fun interruptedPhysicalDeletionIsReconciledWithoutDeletingOtherFiles() = runBlocking {
        val file = File.createTempFile("optimization-delete-", ".wav", context.cacheDir)
        val untouched = File.createTempFile("optimization-untouched-", ".wav", context.cacheDir)
        try {
            db.trackDao().insertOrUpdate(TrackEntity("delete", "删除专项", "", "", 1, null))
            db.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = "delete", uri = Uri.fromFile(file).toString(), sourceType = "SAF_FILE", fileSize = 1, mimeType = "audio/wav", format = "wav"))
            class ProcessStopped : Error()
            val repository = LibraryRepository(context, db, deletionCheckpoint = { if (it == "FILE_DELETED") throw ProcessStopped() })
            assertTrue(runCatching { repository.deleteTrackWithReport("delete", true) }.exceptionOrNull() is ProcessStopped)
            assertFalse(file.exists())
            assertEquals(1, db.localAssetDao().getPendingDeletions().size)
            assertEquals(1, LibraryRepository(context, db).recoverInterruptedDeletions())
            assertNotNull(db.trackDao().getTrackById("delete"))
            assertFalse(db.localAssetDao().getAssetsForTrack("delete").single().isAvailable)
            assertTrue(untouched.exists())
            assertTrue(LibraryRepository(context, db).deleteTrackWithReport("delete", true).recordRemoved)
        } finally { file.delete(); untouched.delete() }
    }

    @Test fun offlineRefreshKeepsCacheManualEncodingAndCoalescedCalibrationSurvive() = runBlocking {
        db.trackDao().insertOrUpdate(TrackEntity("lyrics", "歌词专项", "", "", 1, null))
        db.lyricDao().insertOrUpdate(LyricRecordEntity("lyrics", "CACHED_ONLINE", "[00:01.0]旧歌词", 100))
        var fetches = 0
        val repo = LyricRepository(context, db) { _, _ -> fetches++; error("离线") }
        val song = track("lyrics").copy(platform = "wy", platformSongId = "1")
        assertEquals("旧歌词", repo.load(song).lines.single().text)
        assertEquals(0, fetches)
        val refreshed = repo.load(song, true)
        assertEquals("旧歌词", refreshed.lines.single().text); assertNotNull(refreshed.warning)
        val file = File.createTempFile("optimization-lyrics-", ".lrc", context.cacheDir)
        try {
            file.writeBytes("[00:02.0]中文歌词".toByteArray(charset("GB18030")))
            repo.importLrc(song.id, Uri.fromFile(file))
            assertEquals("中文歌词", repo.load(song, true).lines.single().text)
            assertEquals(1, fetches)
            file.writeText("\uFEFF[00:03.0]BOM歌词")
            repo.importLrc(song.id, Uri.fromFile(file))
            repo.scheduleOffset(song.id, 300); repo.scheduleOffset(song.id, 700)
            withTimeout(3000) { while (db.lyricDao().getLyricForTrack(song.id)?.offsetMs != 700L) delay(30) }
            val loaded = LyricRepository(context, db).load(song)
            assertEquals(700L, loaded.offsetMs); assertEquals("BOM歌词", loaded.lines.single().text)
        } finally { file.delete() }
    }
}
