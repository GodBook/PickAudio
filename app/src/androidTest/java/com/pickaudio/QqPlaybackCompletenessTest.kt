package com.pickaudio

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.LocalAssetEntity
import com.pickaudio.data.db.OnlineRefEntity
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.db.TrackEntity
import com.pickaudio.data.model.AudioInfo
import com.pickaudio.data.model.PlaybackPhase
import com.pickaudio.data.model.PlaybackMode
import com.pickaudio.data.model.Quality
import com.pickaudio.data.model.Track
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.network.NetworkPolicy
import com.pickaudio.playback.AudioCacheManager
import com.pickaudio.playback.PlaybackCoordinator
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Uses an isolated database/source and real Media3 decoding on a disposable test device. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class QqPlaybackCompletenessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun oldDownloadedPreviewIsKeptButBypassedUsingSavedCatalogDuration() = runBlocking {
        withFixture { fixture ->
            val old = fixture.file(wave(10))
            // Simulate v1.5.0 publishing the preview and replacing the track duration with 10s.
            // The caller has no metadata; the authoritative QQ reference still says 60s.
            val track = fixture.track(old, "DOWNLOADED", storedDurationMs = 10_000)
            fixture.prepare(track)
            awaitPausedReady(fixture.coordinator)
            val asset = fixture.database.localAssetDao().getAssetByUri(Uri.fromFile(old).toString())!!
            assertFalse("An old preview must not remain an available playback asset", asset.isAvailable)
            assertTrue(asset.unavailableReason.orEmpty().contains("重新下载"))
            assertTrue("Never delete an existing user file while repairing playback", old.isFile)
            withContext(Dispatchers.Main) {
                assertEquals("http", fixture.coordinator.player.currentMediaItem!!.localConfiguration!!.uri.scheme)
                assertEquals(60_000L, fixture.coordinator.durationMs.value)
                fixture.coordinator.player.seekTo(45_000)
                fixture.coordinator.player.play()
            }
            await { fixture.coordinator.currentPositionMs.value > 45_100 }
            assertNotEquals(PlaybackPhase.ERROR, fixture.coordinator.uiState.value.phase)
        }
    }

    @Test fun remotePreviewIsReportedAsIncompleteBeforePlayback() = runBlocking {
        withFixture("/short.wav") { fixture ->
            val track = fixture.track(storedDurationMs = 10_000)
            fixture.prepare(track)
            await { fixture.coordinator.uiState.value.phase == PlaybackPhase.ERROR }
            assertTrue(fixture.coordinator.uiState.value.message.orEmpty().contains("完整"))
            assertFalse(fixture.coordinator.playRequested.value)
            assertFalse(fixture.coordinator.isPlaying.value)
            assertEquals(0, fixture.coordinator.currentIndex.value)
        }
    }

    @Test fun earlyRemoteEofDoesNotCompleteTheTrackOrAdvanceTheQueue() = runBlocking {
        withFixture { fixture ->
            for (mode in listOf(PlaybackMode.SEQUENTIAL, PlaybackMode.SINGLE_LOOP)) {
                withContext(Dispatchers.Main) { fixture.coordinator.setPlaybackMode(mode) }
                val track = fixture.track()
                fixture.prepare(track, fixture.track(fixture.file(wave(60)), "SAF_FILE"))
                awaitPausedReady(fixture.coordinator)
                val declaredFullAudio = wave(60)
                val truncated = declaredFullAudio.copyOf(44 + 8000 * 2 * 2)
                val factory = DataSource.Factory {
                    val delegate = ByteArrayDataSource(truncated)
                    object : DataSource by delegate {
                        override fun open(dataSpec: DataSpec): Long {
                            delegate.open(dataSpec)
                            // The transport/container claim 60s, but the samples stop at 2s.
                            return declaredFullAudio.size.toLong() - dataSpec.position
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    val media = fixture.coordinator.player.currentMediaItem!!
                    fixture.coordinator.player.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(media))
                    fixture.coordinator.player.prepare()
                    fixture.coordinator.player.play()
                }
                // Sample EOS may be reported before the first isPlaying callback for tiny files.
                await { fixture.coordinator.uiState.value.phase == PlaybackPhase.ERROR }
                assertEquals("An incomplete recording must not be a natural queue transition ($mode)", 0,
                    fixture.coordinator.currentIndex.value)
                assertEquals(PlaybackPhase.ERROR, fixture.coordinator.uiState.value.phase)
                assertTrue(fixture.coordinator.uiState.value.message.orEmpty().contains("完整"))
                assertTrue("The message must report real samples, not the container's 60s declaration",
                    fixture.coordinator.uiState.value.message.orEmpty().contains("2 秒"))
                assertFalse(fixture.coordinator.playRequested.value)
            }
        }
    }

    @Test fun seekingNearTheEndAndRepeatingACompleteRemoteSongDoNotReportTruncation() = runBlocking {
        withFixture { fixture ->
            fixture.prepare(fixture.track())
            awaitPausedReady(fixture.coordinator)
            withContext(Dispatchers.Main) {
                fixture.coordinator.seekTo(60_000)
                fixture.coordinator.player.play()
            }
            await { fixture.coordinator.player.playbackState == Player.STATE_ENDED }
            assertNotEquals(PlaybackPhase.ERROR, fixture.coordinator.uiState.value.phase)
            withContext(Dispatchers.Main) { fixture.coordinator.setPlaybackMode(PlaybackMode.SINGLE_LOOP) }
            fixture.prepare(fixture.track())
            awaitPausedReady(fixture.coordinator)
            withContext(Dispatchers.Main) {
                // Seeking is a discontinuity rather than an incomplete natural end.
                fixture.coordinator.seekTo(59_000)
                fixture.coordinator.player.play()
            }
            // The coordinator's manual repeat starts its own request and waits for the service.
            // Complete only this isolated coordinator's readiness instead of coupling test state
            // to the production application's separate playback coordinator.
            withTimeout(15_000) {
                while (!withContext(Dispatchers.Main) {
                    assertNotEquals(fixture.coordinator.uiState.value.message, PlaybackPhase.ERROR,
                        fixture.coordinator.uiState.value.phase)
                    fixture.coordinator.onPlaybackServiceReady()
                    fixture.coordinator.player.playbackState == Player.STATE_READY &&
                        fixture.coordinator.player.currentPosition in 100L..5000L
                }) delay(50)
            }
            assertEquals(0, fixture.coordinator.currentIndex.value)
            assertNotEquals(PlaybackPhase.ERROR, fixture.coordinator.uiState.value.phase)
        }
    }

    @Test fun completeDownloadsAndUserImportsRemainPlayable() = runBlocking {
        withFixture { fixture ->
            val complete = fixture.file(wave(60))
            val download = fixture.track(complete, "DOWNLOADED")
            fixture.prepare(download)
            awaitPausedReady(fixture.coordinator)
            withContext(Dispatchers.Main) {
                assertEquals(Uri.fromFile(complete), fixture.coordinator.player.currentMediaItem!!.localConfiguration!!.uri)
                fixture.coordinator.player.seekTo(45_000)
                fixture.coordinator.player.play()
            }
            await { fixture.coordinator.currentPositionMs.value > 45_100 }
            assertTrue(fixture.database.localAssetDao().getAssetByUri(Uri.fromFile(complete).toString())!!.isAvailable)

            // An imported clip is the user's chosen local file, even when it has an online ref.
            val clip = fixture.file(wave(10))
            val imported = fixture.track(clip, "SAF_FILE")
            fixture.prepare(imported)
            awaitPausedReady(fixture.coordinator)
            withContext(Dispatchers.Main) {
                assertEquals(Uri.fromFile(clip), fixture.coordinator.player.currentMediaItem!!.localConfiguration!!.uri)
                assertEquals(10_000L, fixture.coordinator.durationMs.value)
                fixture.coordinator.player.play()
            }
            await { fixture.coordinator.currentPositionMs.value > 100 }
            assertTrue(fixture.database.localAssetDao().getAssetByUri(Uri.fromFile(clip).toString())!!.isAvailable)
            assertNotEquals(PlaybackPhase.ERROR, fixture.coordinator.uiState.value.phase)
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) {
        while (!withContext(Dispatchers.Main) { condition() }) delay(50)
    }

    private suspend fun awaitPausedReady(coordinator: PlaybackCoordinator) = await {
        assertNotEquals(coordinator.uiState.value.message, PlaybackPhase.ERROR, coordinator.uiState.value.phase)
        coordinator.player.playbackState == Player.STATE_READY && !coordinator.player.playWhenReady
    }

    private inner class Fixture(val database: PickAudioDatabase, val coordinator: PlaybackCoordinator) {
        val files = mutableListOf<File>()
        fun file(bytes: ByteArray): File = File.createTempFile("qq-completeness-", ".wav", context.filesDir)
            .also { it.writeBytes(bytes); files.add(it) }

        suspend fun track(file: File? = null, sourceType: String = "DOWNLOADED", storedDurationMs: Long = 60_000): Track {
            val id = "qq_completeness_${System.nanoTime()}"
            database.trackDao().insertOrUpdate(TrackEntity(id, "QQ 完整性回归", "测试", "", storedDurationMs, null))
            database.onlineRefDao().insertOrUpdate(OnlineRefEntity(trackId = id, platform = "tx", platformSongId = id,
                platformMetadataJson = """{"songid":123,"songmid":"$id","strMediaMid":"fixture_media","interval":60}"""))
            if (file != null) database.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = id,
                uri = Uri.fromFile(file).toString(), sourceType = sourceType, fileSize = file.length(),
                mimeType = "audio/wav", format = "wav",
                audioInfoJson = AudioInfo("wav", "audio/wav", true, 16, 8000, 1, 128000, "PCM").encode()))
            // Deliberately omit the metadata and platform from the caller; resolve them from Room.
            return Track(id, "QQ 完整性回归", "测试", "", storedDurationMs)
        }

        suspend fun prepare(vararg tracks: Track) = withContext(Dispatchers.Main) {
            coordinator.clearQueue()
            coordinator.addTracksToQueue(tracks.toList())
            // Paused preparation avoids starting the app's separate production service/coordinator.
            coordinator.playQueueItem(0, autoPlay = false)
        }
    }

    private suspend fun withFixture(path: String = "/full.wav", block: suspend (Fixture) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val database = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
        val preferences = UserPreferences(context)
        val quality = preferences.defaultOnlineQuality.first()
        val keysBefore = AudioCacheManager.getCache(context).keys.toSet()
        try {
            preferences.setDefaultOnlineQuality(Quality.Q128K)
            MockWebServer().use { server ->
                val full = wave(60)
                val short = wave(10)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val bytes = if (request.path?.substringBefore('?') == "/short.wav") short else full
                        val response = MockResponse().setHeader("Content-Type", "audio/wav")
                            .setHeader("Accept-Ranges", "bytes").setHeader("ETag", "\"${bytes.size}\"")
                        if (request.method == "HEAD") return response.setHeader("Content-Length", bytes.size)
                        val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                        val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                        if (start >= bytes.size) return response.setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
                        if (range != null) response.setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                        return response.setBody(Buffer().write(bytes, start, end - start + 1))
                    }
                }
                LxSourceManager(context, database, NetworkPolicy(setOf("localhost", "127.0.0.1"))).use { manager ->
                    val source = manager.importSourceFromCode("""
                        lx.on(lx.EVENT_NAMES.request, function() { return '${server.url(path)}'; });
                        lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{tx:{name:'QQ完整性回归',actions:['musicUrl'],qualitys:['128k']}}});
                    """.trimIndent())
                    manager.selectSourceForPlatform("tx", source.id)
                    val coordinator = withContext(Dispatchers.Main) { PlaybackCoordinator(context, database, manager, preferences) }
                    val fixture = Fixture(database, coordinator)
                    try {
                        await { coordinator.restored.value }
                        block(fixture)
                    } finally {
                        withContext(Dispatchers.Main) { coordinator.clearQueue(); coordinator.onServiceDestroyed() }
                        coordinator.flushPersistence()
                        fixture.files.forEach { it.delete() }
                    }
                }
            }
        } finally {
            preferences.setDefaultOnlineQuality(quality)
            val cache = AudioCacheManager.getCache(context)
            (cache.keys - keysBefore).forEach(cache::removeResource)
            database.close()
            scenario.close()
        }
    }

    private fun wave(seconds: Int): ByteArray = ByteBuffer.allocate(44 + 8000 * 2 * seconds)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(capacity() - 44)
        }.array()
}
