package com.pickaudio

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.data.model.PlaybackMode
import com.pickaudio.data.model.PlaybackPhase
import com.pickaudio.data.model.Quality
import com.pickaudio.data.model.toTrack
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.data.repository.LibraryRepository
import com.pickaudio.data.repository.ensureTrackIdentity
import com.pickaudio.download.AndroidAudioProbe
import com.pickaudio.download.AudioFormatProbe
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.network.NetworkPolicy
import com.pickaudio.online.AudioCompleteness
import com.pickaudio.playback.AudioCacheManager
import com.pickaudio.playback.PlaybackCoordinator
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class QqSourcePlaybackTest {
    @Test fun qqOriginalMetadataResolvesThroughLxAndMedia3DecodesTheStream() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
        val preferences = UserPreferences(context)
        val previousQuality = preferences.defaultOnlineQuality.first()
        val cache = AudioCacheManager.getCache(context)
        val keysBefore = cache.keys.toSet()
        try {
            preferences.setDefaultOnlineQuality(Quality.Q128K)
            MockWebServer().use { server ->
                val arguments = InstrumentationRegistry.getArguments()
                val fixtureName = arguments.getString("qqAudioFixture")
                val durationMs = if (fixtureName == null) 3000L else
                    requireNotNull(arguments.getString("qqDurationMs")?.toLongOrNull()) { "qqDurationMs is required with a real fixture" }
                require(durationMs in 2500L..3_600_000L)
                val bytes = if (fixtureName == null) ByteBuffer.allocate(44 + 44100 * 2 * 3).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
                    putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
                    put("data".toByteArray()); putInt(capacity() - 44)
                    while (remaining() >= 2) putShort(0)
                }.array() else {
                    require(fixtureName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}"))) { "Fixture must be a direct filesDir filename" }
                    val fixture = File(context.filesDir, fixtureName).canonicalFile
                    require(fixture.parentFile == context.filesDir.canonicalFile && fixture.isFile &&
                        fixture.length() in 1L..100_000_000L) { "Fixture must be a bounded file inside filesDir" }
                    fixture.readBytes()
                }
                val detected = requireNotNull(AudioFormatProbe.detect(bytes.copyOf(minOf(bytes.size, 65536))))
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path != "/original.audio") return MockResponse().setResponseCode(404)
                        val response = MockResponse().setHeader("Content-Type", detected.mimeType)
                            .setHeader("Accept-Ranges", "bytes").setHeader("ETag", "\"original-fixture\"")
                        if (request.method == "HEAD") return response.setHeader("Content-Length", bytes.size)
                        val rangeHeader = request.getHeader("Range")
                        if (rangeHeader == null) return response.setBody(Buffer().write(bytes))
                        val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(rangeHeader)
                            ?: return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
                        val start = range.groupValues[1].toLongOrNull() ?: Long.MAX_VALUE
                        val end = (range.groupValues[2].toLongOrNull() ?: (bytes.size - 1).toLong())
                            .coerceAtMost((bytes.size - 1).toLong())
                        if (start > end || start >= bytes.size) return MockResponse().setResponseCode(416)
                            .setHeader("Content-Range", "bytes */${bytes.size}")
                        return response.setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                            .setBody(Buffer().write(bytes, start.toInt(), (end - start + 1).toInt()))
                    }
                }
                val song = SearchSongItem("tx", "original_mid", "QQ 原曲回归", "测试歌手", "测试专辑", durationMs,
                    metadataJson = """{"songid":123,"strMediaMid":"media_mid","interval":${durationMs / 1000}}""").toTrack()
                db.ensureTrackIdentity(song)
                LxSourceManager(context, db, NetworkPolicy(setOf("localhost", "127.0.0.1"))).use { manager ->
                    val source = manager.importSourceFromCode("""
                        lx.on(lx.EVENT_NAMES.request, function(request) {
                            var m = request.info.musicInfo;
                            if (request.source !== 'tx' || m.songId !== 123 || m.songmid !== 'original_mid' || m.strMediaMid !== 'media_mid')
                                throw new Error('原曲信息丢失');
                            return '${server.url("/original.audio")}';
                        });
                        lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{tx:{name:'QQ音频专项',actions:['musicUrl'],qualitys:['128k']}}});
                    """.trimIndent())
                    manager.selectSourceForPlatform("tx", source.id)
                    val url = manager.resolveMusicUrl("tx", "original_mid", "128k", song.title, song.artist)
                    assertEquals(server.url("/original.audio").toString(), url)
                    var failure: PlaybackException? = null
                    val coordinator = withContext(Dispatchers.Main) { PlaybackCoordinator(context, db, manager, preferences) }
                    val player = withContext(Dispatchers.Main) {
                        coordinator.player.apply {
                            addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failure = error } })
                        }
                    }
                    try {
                        suspend fun awaitPlayback(condition: (ExoPlayer) -> Boolean) = withTimeout(15000) {
                            while (!withContext(Dispatchers.Main) {
                                failure?.let { throw it }
                                assertNotEquals(coordinator.uiState.value.message, PlaybackPhase.ERROR, coordinator.uiState.value.phase)
                                condition(player)
                            }) delay(50)
                        }
                        awaitPlayback { coordinator.restored.value }
                        withContext(Dispatchers.Main) {
                            coordinator.setPlaybackMode(PlaybackMode.SEQUENTIAL)
                            coordinator.clearQueue()
                            coordinator.addTracksToQueue(listOf(song))
                            // Prepare paused so this isolated coordinator does not start the app's service.
                            coordinator.playQueueItem(0, autoPlay = false)
                        }
                        awaitPlayback { it.playbackState == Player.STATE_READY && !it.playWhenReady }
                        withContext(Dispatchers.Main) {
                            assertEquals("http", player.currentMediaItem!!.localConfiguration!!.uri.scheme)
                            player.play()
                        }
                        awaitPlayback { it.isPlaying && it.currentPosition > 100 }
                        val decodedDuration = withContext(Dispatchers.Main) { player.duration }
                        assertTrue("A finite decoded duration is required for tail validation", decodedDuration > 2000)
                        AudioCompleteness.requireComplete(durationMs, decodedDuration)
                        val threeQuarter = durationMs * 3 / 4
                        withContext(Dispatchers.Main) { coordinator.seekTo(threeQuarter); player.play() }
                        awaitPlayback { it.isPlaying && it.currentPosition > threeQuarter + 100 }
                        val tailPosition = decodedDuration - 500
                        withContext(Dispatchers.Main) { coordinator.seekTo(tailPosition); player.play() }
                        awaitPlayback { it.isPlaying && it.currentPosition > tailPosition + 100 }
                        awaitPlayback { it.playbackState == Player.STATE_ENDED }
                        delay(250) // Allow the production PCM sink's asynchronous EOS observation to run.
                        withContext(Dispatchers.Main) {
                            assertTrue("Playback must reach the actual tail", player.currentPosition >= decodedDuration - 100)
                            assertEquals(0, coordinator.currentIndex.value)
                            assertNotEquals(coordinator.uiState.value.message, PlaybackPhase.ERROR, coordinator.uiState.value.phase)
                        }
                        assertEquals("/original.audio", server.takeRequest(1, TimeUnit.SECONDS)?.path)
                        assertTrue(LibraryRepository(context, db).getAllTracks().first().isEmpty())
                    } finally {
                        withContext(Dispatchers.Main) { coordinator.clearQueue(); coordinator.onServiceDestroyed() }
                        coordinator.flushPersistence()
                    }

                    val downloads = DownloadCoordinator(context, db, manager, scheduleOverride = {})
                    downloads.recoverInterrupted()
                    val taskId = downloads.enqueueDownload(song.id, song.title, song.artist, song.album, song.coverUri,
                        "tx", "original_mid", "128k", durationMs, song.platformMetadataJson, scheduleNow = false)
                    withTimeout(30_000) { downloads.runPending() }
                    val task = requireNotNull(db.downloadDao().getTaskById(taskId))
                    assertEquals(task.errorMessage, "COMPLETED", task.status)
                    assertEquals(bytes.size.toLong(), task.downloadedBytes)
                    val downloaded = Uri.parse(requireNotNull(task.targetUri))
                    assertArrayEquals("Downloaded audio must retain every source byte", bytes,
                        context.contentResolver.openInputStream(downloaded)!!.use { it.readBytes() })
                    val inspected = AndroidAudioProbe.inspect(context, downloaded, validatePackets = true)
                    AudioCompleteness.requireComplete(durationMs, inspected.durationMs)
                    assertEquals(detected.extension, inspected.info.extension)
                    assertEquals("PACKETS", inspected.info.confidence)
                    assertTrue(db.localAssetDao().getAssetsForTrack(song.id).single().isAvailable)
                    assertTrue(LibraryRepository(context, db).getAllTracks().first().isEmpty())
                }
            }
        } finally {
            try {
                db.downloadDao().getAllTasksSync().mapNotNull { it.targetUri }.distinct().forEach {
                    context.contentResolver.delete(Uri.parse(it), null, null)
                }
            } finally {
                preferences.setDefaultOnlineQuality(previousQuality)
                (cache.keys - keysBefore).forEach(cache::removeResource)
                db.close()
                scenario.close()
            }
        }
    }
}
