package com.pickaudio

import android.app.ActivityManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.LocalAssetEntity
import com.pickaudio.data.db.TrackEntity
import com.pickaudio.data.model.PlaybackMode
import com.pickaudio.data.model.PlaybackPhase
import com.pickaudio.data.model.Track
import com.pickaudio.playback.PlaybackCoordinator
import com.pickaudio.playback.PlaybackService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run only on a disposable API 36 emulator: these tests replace the application queue. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackStabilityTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PickAudioApplication

    @Test fun silentRemoteStallRecoversSameEntryWithoutTransportError() = runBlocking {
        withPlayingFixture { coordinator, scenario, audio ->
            val gate = CountDownLatch(1)
            val boundary = 44 + 8000 * 2 * 3
            val entry = coordinator.queueEntries.value.single().id
            val factory = DataSource.Factory {
                val delegate = ByteArrayDataSource(audio)
                object : DataSource by delegate {
                    private var offset = 0L
                    override fun open(dataSpec: DataSpec): Long {
                        offset = dataSpec.position
                        return delegate.open(dataSpec)
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (this.offset >= boundary) {
                            try { gate.await() } catch (_: InterruptedException) { throw java.io.InterruptedIOException() }
                        }
                        return delegate.read(buffer, offset,
                            if (this.offset < boundary) minOf(length, (boundary - this.offset).toInt()) else length).also {
                            if (it != C.RESULT_END_OF_INPUT) this.offset += it
                        }
                    }
                    override fun close() { gate.countDown(); delegate.close() }
                }
            }
            try {
                withContext(Dispatchers.Main) {
                    val media = coordinator.player.currentMediaItem!!.buildUpon().setUri("https://fixture.invalid/stall.wav").build()
                    coordinator.player.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(media))
                    coordinator.player.prepare()
                }
                await { coordinator.isPlaying.value && coordinator.currentPositionMs.value > 500 }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                withTimeout(40_000) {
                    while (!withContext(Dispatchers.Main) {
                        coordinator.isPlaying.value && coordinator.player.currentMediaItem?.localConfiguration?.uri?.scheme == "file"
                    }) delay(100)
                }
                assertEquals(entry, coordinator.queueEntries.value.single().id)
                assertTrue(coordinator.currentPositionMs.value >= 1000)
                assertTrue(serviceIsForeground())
            } finally { gate.countDown() }
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(20_000) {
        while (!withContext(Dispatchers.Main) { condition() }) delay(50)
    }

    @Suppress("DEPRECATION")
    private fun serviceIsForeground(): Boolean = app.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == PlaybackService::class.java.name && it.foreground }

    private suspend fun withPlayingFixture(block: suspend (PlaybackCoordinator, ActivityScenario<MainActivity>, ByteArray) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val coordinator = app.playbackCoordinator
        val file = File.createTempFile("playback-stability-", ".wav", app.filesDir)
        val id = "playback_stability_${System.nanoTime()}"
        val audio = ByteBuffer.allocate(44 + 8000 * 2 * 90).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(capacity() - 44)
        }.array()
        val mode = coordinator.playbackMode.value
        try {
            file.writeBytes(audio)
            val track = Track(id, "播放稳定性回归", "测试", "", 90_000, localUri = Uri.fromFile(file).toString())
            app.database.trackDao().insertOrUpdate(TrackEntity(id, track.title, track.artist, "", track.durationMs, null))
            app.database.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = id, uri = track.localUri!!,
                sourceType = "SAF_FILE", fileSize = file.length(), mimeType = "audio/wav", format = "wav"))
            withContext(Dispatchers.Main) {
                coordinator.clearQueue()
                coordinator.setPlaybackMode(PlaybackMode.SEQUENTIAL)
                coordinator.setQueueAndPlay(listOf(track))
            }
            await { coordinator.isPlaying.value && serviceIsForeground() }
            block(coordinator, scenario, audio)
        } finally {
            withContext(Dispatchers.Main) {
                coordinator.clearQueue()
                coordinator.setPlaybackMode(mode)
            }
            coordinator.flushPersistence()
            app.database.trackDao().deleteById(id)
            file.delete()
            scenario.close()
        }
    }

    @Test fun rebufferingKeepsForegroundServiceAndResumesSameQueueEntry() = runBlocking {
        withPlayingFixture { coordinator, scenario, audio ->
            val gate = CountDownLatch(1)
            val boundary = 44 + 8000 * 2 * 3
            val factory = DataSource.Factory {
                val delegate = ByteArrayDataSource(audio)
                object : DataSource by delegate {
                    private var offset = 0L
                    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                        offset = dataSpec.position
                        return delegate.open(dataSpec)
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (this.offset >= boundary && !gate.await(20, TimeUnit.SECONDS)) throw IOException("Test stream gate timed out")
                        val count = delegate.read(buffer, offset,
                            if (this.offset < boundary) minOf(length, (boundary - this.offset).toInt()) else length)
                        if (count != C.RESULT_END_OF_INPUT) this.offset += count
                        return count
                    }
                    override fun close() { gate.countDown(); delegate.close() }
                }
            }
            val entry = coordinator.queueEntries.value.single().id
            try {
                withContext(Dispatchers.Main) {
                    val media = coordinator.player.currentMediaItem!!
                    coordinator.player.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(media))
                    coordinator.player.prepare()
                }
                await { coordinator.isPlaying.value && coordinator.currentPositionMs.value > 500 }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                await { coordinator.player.playbackState == Player.STATE_BUFFERING && coordinator.currentPositionMs.value >= 1000 }
                delay(500) // Let all Media3 and coordinator listeners process the rebuffer event.
                withContext(Dispatchers.Main) {
                    assertTrue("Rebuffering must retain the user's play request", coordinator.playRequested.value)
                    assertEquals(PlaybackPhase.BUFFERING, coordinator.uiState.value.phase)
                    assertTrue("A stalled background stream must keep its foreground service", serviceIsForeground())
                }
                gate.countDown()
                await { coordinator.isPlaying.value && coordinator.currentPositionMs.value > 3500 }
                assertEquals(entry, coordinator.queueEntries.value.single().id)
            } finally { gate.countDown() }
        }
    }

    @Test fun transientAudioFocusLossKeepsForegroundAndHonorsExplicitPause() = runBlocking {
        withPlayingFixture { coordinator, scenario, _ ->
            val audioManager = app.getSystemService(AudioManager::class.java)
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { }.build()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            try {
                repeat(2) {
                    assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audioManager.requestAudioFocus(request))
                    await { coordinator.player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS }
                    delay(500)
                    withContext(Dispatchers.Main) {
                        assertTrue(coordinator.playRequested.value)
                        assertNotEquals("A temporary audio interruption is not a user pause", PlaybackPhase.PAUSED, coordinator.uiState.value.phase)
                        assertTrue("Temporary focus loss must retain the foreground service", serviceIsForeground())
                    }
                    audioManager.abandonAudioFocusRequest(request)
                    await { coordinator.isPlaying.value && serviceIsForeground() }
                }
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audioManager.requestAudioFocus(request))
                await { !coordinator.isPlaying.value }
                withContext(Dispatchers.Main) { coordinator.pause() }
                audioManager.abandonAudioFocusRequest(request)
                delay(700)
                assertFalse("Focus returning must not undo an explicit pause", coordinator.isPlaying.value)
                assertFalse(coordinator.playRequested.value)
            } finally { audioManager.abandonAudioFocusRequest(request) }
        }
    }

    @Test fun rapidCancelAndRestartSatisfiesForegroundDeadline() = runBlocking {
        withPlayingFixture { coordinator, _, _ ->
            withContext(Dispatchers.Main) {
                repeat(12) {
                    coordinator.playQueueItem(0)
                    coordinator.pause()
                }
                coordinator.resume()
            }
            await { coordinator.isPlaying.value && serviceIsForeground() }
            delay(11_000) // API 36's foreground-start timeout is 10 seconds.
            assertTrue("Queued service starts must not stop the final playback", coordinator.isPlaying.value)
            assertTrue(serviceIsForeground())
        }
    }

    private suspend fun interruptRemoteStream(coordinator: PlaybackCoordinator, audio: ByteArray) {
        val boundary = 44 + 8000 * 2 * 3
        val factory = DataSource.Factory {
            val delegate = ByteArrayDataSource(audio)
            object : DataSource by delegate {
                private lateinit var spec: DataSpec
                private var offset = 0L
                override fun open(dataSpec: DataSpec): Long {
                    spec = dataSpec
                    offset = dataSpec.position
                    return delegate.open(dataSpec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (this.offset >= boundary) throw HttpDataSource.HttpDataSourceException(IOException("Fixture connection lost"),
                        spec, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        HttpDataSource.HttpDataSourceException.TYPE_READ)
                    return delegate.read(buffer, offset, minOf(length, (boundary - this.offset).toInt())).also {
                        if (it != C.RESULT_END_OF_INPUT) this.offset += it
                    }
                }
            }
        }
        withContext(Dispatchers.Main) {
            val media = coordinator.player.currentMediaItem!!.buildUpon().setUri("https://fixture.invalid/audio.wav").build()
            coordinator.player.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(media))
            coordinator.player.prepare()
        }
        await { coordinator.uiState.value.phase == PlaybackPhase.RESOLVING &&
            coordinator.uiState.value.message == "播放连接中断，正在重新连接" }
    }

    @Test fun exhaustedTransportRetriesReResolveAndResumeFromSavedPosition() = runBlocking {
        withPlayingFixture { coordinator, scenario, audio ->
            val entry = coordinator.queueEntries.value.single().id
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            interruptRemoteStream(coordinator, audio)
            val interruptedAt = coordinator.currentPositionMs.value
            assertTrue("The fixture must fail after playback has started", interruptedAt >= 1000)
            assertTrue(coordinator.playRequested.value)
            assertTrue(serviceIsForeground())
            await { coordinator.isPlaying.value && coordinator.currentPositionMs.value >= interruptedAt &&
                coordinator.player.currentMediaItem?.localConfiguration?.uri?.scheme == "file" }
            assertEquals(entry, coordinator.queueEntries.value.single().id)
        }
    }

    @Test fun pauseDuringTransportRecoveryCancelsAutomaticResume() = runBlocking {
        withPlayingFixture { coordinator, _, audio ->
            interruptRemoteStream(coordinator, audio)
            withContext(Dispatchers.Main) { coordinator.pause() }
            delay(2500)
            assertFalse(coordinator.playRequested.value)
            assertFalse(coordinator.isPlaying.value)
            assertEquals(PlaybackPhase.PAUSED, coordinator.uiState.value.phase)
        }
    }

    @Test fun queuedMedia3StartAfterPauseDoesNotCrashOrReplaceMediaControls() = runBlocking {
        withPlayingFixture { coordinator, _, _ ->
            withContext(Dispatchers.Main) { coordinator.pause() }
            await { !serviceIsForeground() }
            val notifications = app.getSystemService(android.app.NotificationManager::class.java)
            await { notifications.activeNotifications.any { it.id == 2041 && it.notification.actions.orEmpty().isNotEmpty() } }
            // This is the no-action intent queued by Media3 when it publishes a foreground notice.
            androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, PlaybackService::class.java))
            delay(11_000)
            assertFalse(coordinator.playRequested.value)
            assertFalse(serviceIsForeground())
            assertTrue("A queued self-start must retain paused media controls",
                notifications.activeNotifications.any { it.id == 2041 && it.notification.actions.orEmpty().isNotEmpty() })
            withContext(Dispatchers.Main) { coordinator.resume() }
            await { coordinator.isPlaying.value && serviceIsForeground() }
        }
    }
}
