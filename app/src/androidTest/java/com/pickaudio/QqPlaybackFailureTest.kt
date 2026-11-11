package com.pickaudio

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.LocalAssetEntity
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.model.PlaybackPhase
import com.pickaudio.data.model.Quality
import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.data.model.Track
import com.pickaudio.data.model.toTrack
import com.pickaudio.data.repository.ensureTrackIdentity
import com.pickaudio.playback.PlaybackCoordinator
import com.pickaudio.playback.PlaybackService
import com.pickaudio.playback.QueueSessionPlayer
import com.pickaudio.source.LxSourceManager
import com.pickaudio.ui.screens.PlayerScreen
import com.pickaudio.ui.theme.PickAudioTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Uses only a disposable API 36 emulator; replaces the application's queue. */
@RunWith(AndroidJUnit4::class)
class QqPlaybackFailureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PickAudioApplication

    private suspend fun await(label: String, condition: () -> Boolean) {
        Log.i("QqPlaybackFailureTest", "Awaiting $label")
        try {
            withTimeout(20_000) {
                while (!withContext(Dispatchers.Main) { condition() }) delay(50)
            }
            Log.i("QqPlaybackFailureTest", "Completed $label")
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            val state = withContext(Dispatchers.Main) { diagnosticState() }
            Log.e("QqPlaybackFailureTest", "Timed out awaiting $label: $state", e)
            throw AssertionError("Timed out after 20000 ms awaiting $label: $state", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun diagnosticState(): String {
        val coordinator = app.playbackCoordinator
        val services = app.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .filter { it.service.className == PlaybackService::class.java.name }
            .joinToString { "started=${it.started},foreground=${it.foreground},clients=${it.clientCount}," +
                "pid=${it.pid},activeSince=${it.activeSince},restarting=${it.restarting}" }
        // Avoid coordinator.player: its getter would create a player after service destruction.
        return "ui=${coordinator.uiState.value},playRequested=${coordinator.playRequested.value}," +
            "playing=${coordinator.isPlaying.value},position=${coordinator.currentPositionMs.value}," +
            "duration=${coordinator.durationMs.value},index=${coordinator.currentIndex.value}," +
            "track=${coordinator.currentTrack.value?.id}," +
            "services=[$services]"
    }

    @Suppress("DEPRECATION")
    private fun serviceIsRunning() = app.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .any { it.service.className == PlaybackService::class.java.name }

    @Suppress("DEPRECATION")
    private fun serviceIsForeground() = app.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .any { it.service.className == PlaybackService::class.java.name && it.foreground }

    @Test fun automaticFailedQqTrackKeepsForegroundUntilNextTrackAndPauseCancelsSkip() = runBlocking {
        withFailureFixture { coordinator, local ->
            val failed = coordinator.currentTrack.value!!
            val mode = coordinator.playbackMode.value
            try {
                withContext(Dispatchers.Main) {
                    coordinator.setPlaybackMode(com.pickaudio.data.model.PlaybackMode.SEQUENTIAL)
                    coordinator.setQueueAndPlay(listOf(local, failed, local))
                }
                await("automatic failure: initial local playback") { coordinator.isPlaying.value }
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                withContext(Dispatchers.Main) { coordinator.seekTo(89_700) }
                await("automatic failure: failed QQ skip is pending") { coordinator.currentIndex.value == 1 && coordinator.uiState.value.message == "当前歌曲暂不可用，正在播放下一首" }
                assertTrue("Failed automatic track must retain playback intent", coordinator.playRequested.value)
                assertTrue("Failure gap must not demote the foreground service", serviceIsForeground())
                await("automatic failure: following local track plays") { coordinator.currentIndex.value == 2 && coordinator.isPlaying.value }
                withContext(Dispatchers.Main) { coordinator.playQueueItem(0, 89_700) }
                await("automatic failure: second QQ skip is pending") { coordinator.currentIndex.value == 1 && coordinator.uiState.value.message == "当前歌曲暂不可用，正在播放下一首" }
                withContext(Dispatchers.Main) { coordinator.pause() }
                delay(2000)
                assertEquals(1, coordinator.currentIndex.value)
                assertFalse(coordinator.playRequested.value)
                assertFalse(coordinator.isPlaying.value)
            } finally {
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                withContext(Dispatchers.Main) { coordinator.setPlaybackMode(mode) }
            }
        }
    }

    private suspend fun withFailureFixture(pending: Boolean = false, block: suspend (PlaybackCoordinator, Track) -> Unit) {
        app.sourceManager.ensureBuiltinSources()
        val previous = app.database.sourceDao().getSelectionForPlatform("tx")!!
        val quality = app.userPreferences.defaultOnlineQuality.first()
        val handler = if (pending) "() => new Promise(() => {})" else """() => Promise.reject(new Error(
            '所有后端均失败（共 23 个）\n' + Array(23).fill('测试后端：当前歌曲不可用，请检查网络和平台授权').join('\n')))""".trimIndent()
        val source = app.sourceManager.importSourceFromCode("""
            lx.on(lx.EVENT_NAMES.request, $handler);
            lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{tx:{name:'QQ失败回归',actions:['musicUrl'],qualitys:['128k']}}});
        """.trimIndent())
        val id = "qq_failure_${System.nanoTime()}"
        val qq = SearchSongItem("tx", id, "QQ 不可用歌曲", "测试歌手", "", 90_000,
            metadataJson = """{"songid":123,"strMediaMid":"fixture_media_mid"}""").toTrack()
        val wave = File.createTempFile("qq-recovery-", ".wav", app.filesDir)
        val local = Track("${id}_local", "失败后继续播放", "测试", "", 90_000, localUri = Uri.fromFile(wave).toString())
        val coordinator = app.playbackCoordinator
        try {
            wave.writeBytes(ByteBuffer.allocate(44 + 8000 * 2 * 90).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(capacity() - 44)
            }.array())
            app.database.ensureTrackIdentity(qq)
            app.database.ensureTrackIdentity(local)
            app.database.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = local.id, uri = local.localUri!!,
                sourceType = "SAF_FILE", fileSize = wave.length(), mimeType = "audio/wav", format = "wav"))
            app.sourceManager.selectSourceForPlatform("tx", source.id)
            app.userPreferences.setDefaultOnlineQuality(Quality.Q128K)
            withContext(Dispatchers.Main) { coordinator.setQueueAndPlay(listOf(qq)) }
            await("fixture: initial QQ ${if (pending) "resolution timeout" else "source rejection"} reaches ERROR") { coordinator.uiState.value.phase == PlaybackPhase.ERROR }
            block(coordinator, local)
        } finally {
            withContext(Dispatchers.Main) { coordinator.clearQueue() }
            coordinator.flushPersistence()
            app.database.trackDao().deleteById(qq.id)
            app.database.trackDao().deleteById(local.id)
            app.database.sourceDao().setPlatformSelection(previous)
            app.sourceManager.deleteSource(source.id)
            app.userPreferences.setDefaultOnlineQuality(quality)
            wave.delete()
        }
    }

    @Test fun failedQqResolutionKeepsControlsVisibleAndNextSongCanPlay() = runBlocking {
        withFailureFixture { coordinator, local ->
            compose.runOnIdle {
                compose.activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                        PickAudioTheme {
                            Box(Modifier.width(320.dp).height(600.dp)) { PlayerScreen(coordinator, {}, {}, false) }
                        }
                    }
                }
            }
            compose.onNodeWithText("重试").assertIsDisplayed()
            listOf("上一首", "下一首", "播放", "播放队列").forEach {
                compose.onNodeWithContentDescription(it).assertIsDisplayed()
            }
            withContext(Dispatchers.Main) { coordinator.setQueueAndPlay(listOf(local)) }
            await("visible controls: local playback advances") { coordinator.isPlaying.value && coordinator.currentPositionMs.value > 0 }
        }
    }

    @Test fun serviceShutdownPreservesQqErrorAndRetryCanRestartPlayback() = runBlocking {
        withFailureFixture { coordinator, local ->
            val message = coordinator.uiState.value.message
            assertFalse(coordinator.playRequested.value)
            withContext(Dispatchers.Main) {
                val stopped = app.stopService(Intent(app, PlaybackService::class.java))
                Log.i("QqPlaybackFailureTest", "stopService returned $stopped: ${diagnosticState()}")
            }
            await("service shutdown: service disappears after stopService") { !serviceIsRunning() }
            assertEquals(PlaybackPhase.ERROR, coordinator.uiState.value.phase)
            assertEquals(message, coordinator.uiState.value.message)
            withContext(Dispatchers.Main) { coordinator.retryCurrent() }
            await("service shutdown: retry reaches ERROR with a running service") { coordinator.uiState.value.phase == PlaybackPhase.ERROR && serviceIsRunning() }
            delay(11_000) // Survive Android 16's complete foreground service start deadline.
            assertEquals(PlaybackPhase.ERROR, coordinator.uiState.value.phase)
            assertFalse(coordinator.playRequested.value)
            withContext(Dispatchers.Main) { coordinator.setQueueAndPlay(listOf(local)) }
            await("service shutdown: local playback advances after retry") { coordinator.isPlaying.value && coordinator.currentPositionMs.value > 0 }
        }
    }

    @Test fun lateServiceDestructionCannotCancelANewerRetryOrReleaseItsPlayer() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(app, PickAudioDatabase::class.java).build()
        val quality = app.userPreferences.defaultOnlineQuality.first()
        val oldService = Any()
        val replacementService = Any()
        var coordinator: PlaybackCoordinator? = null
        try {
            app.userPreferences.setDefaultOnlineQuality(Quality.Q128K)
            LxSourceManager(app, database).use { manager ->
                val source = manager.importSourceFromCode("""
                    lx.on(lx.EVENT_NAMES.request, () => Promise.reject(new Error('确定的 QQ 测试拒绝')));
                    lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{tx:{name:'服务生命周期回归',actions:['musicUrl'],qualitys:['128k']}}});
                """.trimIndent())
                manager.selectSourceForPlatform("tx", source.id)
                val copy = withContext(Dispatchers.Main) { PlaybackCoordinator(app, database, manager, app.userPreferences) }
                coordinator = copy
                await("late service: isolated coordinator restores") { copy.restored.value }
                val qq = SearchSongItem("tx", "late_service_qq", "服务销毁竞态回归", "测试", "", 90_000,
                    metadataJson = """{"songid":123,"strMediaMid":"fixture_media_mid"}""").toTrack()
                database.ensureTrackIdentity(qq)
                withContext(Dispatchers.Main) {
                    copy.addTracksToQueue(listOf(qq))
                    copy.playQueueItem(0, autoPlay = false)
                }
                await("late service: initial source rejection") { copy.uiState.value.phase == PlaybackPhase.ERROR }
                withContext(Dispatchers.Main) {
                    copy.onPlaybackServiceCreated(oldService)
                    val retryPlayer = copy.player
                    copy.retryCurrent()
                    // Reproduce the exact window: the new request exists, while the old
                    // instance's delayed onDestroy arrives before the replacement onCreate.
                    copy.onServiceDestroyed(oldService)
                    assertEquals(PlaybackPhase.RESOLVING, copy.uiState.value.phase)
                    assertTrue(copy.playRequested.value)
                    assertSame(retryPlayer, copy.player)
                    // Let the retry consume the pending start before the replacement acknowledges it.
                    yield()
                }
                withContext(Dispatchers.Main) {
                    copy.onPlaybackServiceCreated(replacementService)
                    val retryPlayer = copy.player
                    copy.onServiceDestroyed(oldService)
                    QueueSessionPlayer(copy, onRelease = {}).release()
                    assertEquals(PlaybackPhase.RESOLVING, copy.uiState.value.phase)
                    assertTrue(copy.playRequested.value)
                    assertSame(retryPlayer, copy.player)
                    copy.onPlaybackServiceReady(replacementService)
                }
                await("late service: retry retains its genuine source error") { copy.uiState.value.phase == PlaybackPhase.ERROR }
                assertTrue(copy.uiState.value.message.orEmpty().contains("确定的 QQ 测试拒绝"))
                assertFalse(copy.playRequested.value)
            }
        } finally {
            coordinator?.let { copy ->
                withContext(Dispatchers.Main) {
                    copy.clearQueue()
                    copy.onPlaybackServiceReady()
                    copy.onServiceDestroyed(replacementService)
                    copy.onServiceDestroyed(oldService)
                    copy.onServiceDestroyed()
                }
                copy.flushPersistence()
            }
            app.userPreferences.setDefaultOnlineQuality(quality)
            withContext(Dispatchers.Main) { app.stopService(Intent(app, PlaybackService::class.java)) }
            database.close()
        }
    }

    @Test fun qqResolutionTimeoutLeavesRetryableErrorAndOtherSongsCanPlay() = runBlocking {
        withFailureFixture(pending = true) { coordinator, local ->
            assertTrue(coordinator.uiState.value.message.orEmpty().contains("15 秒"))
            assertFalse(coordinator.playRequested.value)
            withContext(Dispatchers.Main) { coordinator.setQueueAndPlay(listOf(local)) }
            await("source timeout: following local playback advances") { coordinator.isPlaying.value && coordinator.currentPositionMs.value > 0 }
        }
    }
}
