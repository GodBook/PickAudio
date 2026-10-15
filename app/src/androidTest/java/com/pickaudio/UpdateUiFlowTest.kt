package com.pickaudio

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.semantics.SemanticsActions
import com.pickaudio.data.db.TrackEntity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.model.*
import com.pickaudio.ui.screens.SearchStateManager
import com.pickaudio.ui.theme.PickAudioTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateUiFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PickAudioApplication

    @Test fun selectAllOnlyActsOnDisplayedPlatformAndSummary() = runBlocking<Unit> {
        val requests = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val manager = SearchStateManager(requests) { platform, _, _ ->
            (1..20).map { SearchSongItem(platform, "selection_$it", "范围 $platform $it", "验证", "", 180000) }
        }
        try {
            compose.runOnIdle { compose.activity.setContent { PickAudioTheme { MainApp(app, manager) } } }
            compose.onNodeWithText("搜索").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("范围")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(5000) { manager.searchResults.size == 40 }
            compose.onNodeWithText("多选").performClick()
            compose.onNodeWithText("全选已显示 (0)").performClick()
            compose.onNodeWithText("全选已显示 (10)").assertIsDisplayed()
            compose.onNode(hasText(Platform.QQ.displayName) and hasClickAction()).performClick()
            compose.onNodeWithText("多选").performClick()
            compose.onNodeWithText("全选已加载 (0)").performClick()
            compose.onNodeWithText("全选已加载 (20)").assertIsDisplayed()
            compose.onNodeWithText("加入队列").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(5000) { app.playbackCoordinator.queueEntries.value.size == 20 }
            compose.runOnIdle {
                assertEquals(20, app.playbackCoordinator.queueEntries.value.size)
                assertTrue(app.playbackCoordinator.queue.value.all { it.platform == "tx" })
            }
        } finally {
            requests.cancel()
            withContext(Dispatchers.Main) { app.playbackCoordinator.clearQueue() }
            app.playbackCoordinator.flushPersistence()
            (1..20).forEach { app.database.trackDao().deleteById("online_tx_selection_$it") }
        }
    }

    @Test fun viewingSourcesDoesNotRetryPausedTrack() = runBlocking<Unit> {
        val track = Track("source_return_fixture", "换源返回验证", "验证", "", 1000)
        try {
            compose.runOnIdle { app.playbackCoordinator.setQueueAndPlay(listOf(track)); app.playbackCoordinator.pause() }
            compose.waitUntil(5000) { compose.onAllNodesWithText(track.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(track.title).performClick()
            compose.onNodeWithContentDescription("收起播放器").assertIsDisplayed()
            val before = com.pickaudio.playback.PlaybackDiagnostics.metrics.value.plays
            compose.onNodeWithContentDescription("更多播放操作").performClick()
            compose.onNodeWithText("切换音乐源").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("音乐源管理").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithContentDescription("收起播放器").assertIsDisplayed()
            compose.runOnIdle {
                assertFalse(app.playbackCoordinator.playRequested.value)
                assertEquals(before, com.pickaudio.playback.PlaybackDiagnostics.metrics.value.plays)
            }
        } finally {
            withContext(Dispatchers.Main) { app.playbackCoordinator.clearQueue() }
            app.playbackCoordinator.flushPersistence()
            app.database.trackDao().deleteById(track.id)
        }
    }

    @Test fun largeLibraryScrollReportsActualDeviceFrames() = runBlocking<Unit> {
        val prefix = "frame_fixture_"
        val records = List(10000) { TrackEntity("$prefix$it", "帧夹具 $it", "验证", "", 180000, null,
            createdAt = System.currentTimeMillis() + it, isInLibrary = true) }
        if (InstrumentationRegistry.getArguments().getString("cleanupPerformanceDataset") == "true") {
            app.database.withTransaction { records.forEach { app.database.trackDao().deleteById(it.id) } }
            return@runBlocking
        }
        val durations = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val thread = HandlerThread("frame-validation").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> durations.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)) }
        var registered = false
        try {
            app.database.withTransaction { app.database.trackDao().insertOrUpdateAll(records) }
            compose.waitUntil(15000) { compose.onAllNodesWithText("帧夹具 9999").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle {
                compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                registered = true
            }
            repeat(20) { compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performTouchInput { swipeUp() } }
            compose.waitForIdle()
            compose.runOnIdle { compose.activity.window.removeOnFrameMetricsAvailableListener(listener); registered = false }
            val sorted = synchronized(durations) { durations.toList().sorted() }
            assertTrue("No frame metrics collected", sorted.size >= 20)
            val p95 = sorted[((sorted.size - 1) * 0.95).toInt()] / 1_000_000.0
            android.util.Log.i("PickAudioPerformance", "scrollFrames=${sorted.size} totalDurationP95Ms=$p95 refreshRate=${compose.activity.display?.refreshRate}")
        } finally {
            if (registered) compose.runOnIdle { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            if (InstrumentationRegistry.getArguments().getString("keepPerformanceDataset") != "true")
                app.database.withTransaction { records.forEach { app.database.trackDao().deleteById(it.id) } }
        }
    }
}
