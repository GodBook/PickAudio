package com.pickaudio

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.model.PlaybackMode
import com.pickaudio.data.model.Track
import com.pickaudio.playback.PlaybackCoordinator
import com.pickaudio.playback.QueueSessionPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable device only. Bound traversal before calling Media3's unbounded equals/hashCode. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ShuffleTimelineTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PickAudioApplication

    private suspend fun withPausedQueue(count: Int, block: (PlaybackCoordinator, QueueSessionPlayer) -> Unit) {
        val coordinator = app.playbackCoordinator
        withTimeout(10_000) { coordinator.restored.first { it } }
        val mode = coordinator.playbackMode.value
        val tracks = List(count) { Track("shuffle_timeline_$it", "随机回归 $it", "测试", "", 120_000) }
        try {
            withContext(Dispatchers.Main) {
                // Cancel before the posted playback request can start a service or resolve a URI.
                coordinator.setQueueAndPlay(tracks, count - 1)
                coordinator.pause()
                coordinator.setPlaybackMode(PlaybackMode.SHUFFLE)
                val player = QueueSessionPlayer(coordinator)
                try { block(coordinator, player) } finally { player.close() }
            }
        } finally {
            withContext(Dispatchers.Main) {
                coordinator.clearQueue()
                coordinator.setPlaybackMode(mode)
            }
            coordinator.flushPersistence()
            tracks.forEach { app.database.trackDao().deleteById(it.id) }
        }
    }

    private fun traversal(timeline: Timeline, forward: Boolean): List<Int> {
        var index = if (forward) timeline.getFirstWindowIndex(true) else timeline.getLastWindowIndex(true)
        val visited = mutableListOf<Int>()
        // An invalid shuffle timeline must fail the test instead of hanging the device main thread.
        repeat(timeline.windowCount + 1) {
            if (index == C.INDEX_UNSET) return visited
            assertTrue("Invalid window $index", index in 0 until timeline.windowCount)
            assertFalse("Shuffle timeline cycles at $index: $visited", index in visited)
            visited.add(index)
            index = if (forward) timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
                else timeline.getPreviousWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        fail("Shuffle traversal does not terminate: $visited")
        return visited
    }

    private fun assertPermutation(timeline: Timeline) {
        val forward = traversal(timeline, true)
        assertEquals((0 until timeline.windowCount).toSet(), forward.toSet())
        assertEquals(forward.reversed(), traversal(timeline, false))
    }

    @Test fun lastQueueEntryHasFiniteShuffleTimeline() = runBlocking {
        withPausedQueue(3) { _, player -> assertPermutation(player.currentTimeline) }
    }

    @Test fun singleSongShuffleTerminatesWhenRepeatIsOff() = runBlocking {
        withPausedQueue(1) { _, player ->
            val timeline = player.currentTimeline
            assertPermutation(timeline)
            assertEquals(0, timeline.getNextWindowIndex(0, Player.REPEAT_MODE_ALL, true))
            assertEquals(0, timeline.getPreviousWindowIndex(0, Player.REPEAT_MODE_ONE, true))
        }
    }

    @Test fun shuffleRoundsCanBeComparedHashedAndSentToControllers() = runBlocking {
        withPausedQueue(4) { coordinator, player ->
            repeat(24) {
                val timeline = player.currentTimeline
                assertPermutation(timeline)
                val copy = player.currentTimeline
                assertPermutation(copy)
                assertEquals(timeline, copy)
                assertEquals(timeline.hashCode(), copy.hashCode())
                val restored = Timeline.fromBundle(timeline.toBundle())
                assertPermutation(restored)
                assertEquals(traversal(timeline, true), traversal(restored, true))
                assertEquals(coordinator.nextQueueIndex(), timeline.getNextWindowIndex(
                    coordinator.currentIndex.value, Player.REPEAT_MODE_ALL, true))
                coordinator.next()
                coordinator.pause()
            }
        }
    }

    @Test fun queueEditsAndModeChangesKeepEveryWindowReachable() = runBlocking {
        withPausedQueue(5) { coordinator, player ->
            coordinator.next(); coordinator.pause()
            coordinator.previousQueueItem(); coordinator.pause()
            coordinator.moveQueueItem(0, 3)
            coordinator.removeQueueItem(1)
            coordinator.pause()
            PlaybackMode.entries.forEach { mode ->
                coordinator.setPlaybackMode(mode)
                assertPermutation(player.currentTimeline)
            }
            coordinator.clearQueue()
            assertPermutation(player.currentTimeline)
        }
    }

    @Test fun existingTimelineKeepsItsDurationsAndOrderAfterQueueChanges() = runBlocking {
        withPausedQueue(3) { coordinator, player ->
            val timeline = player.currentTimeline
            assertPermutation(timeline)
            val index = coordinator.currentIndex.value
            val duration = timeline.getWindow(index, Timeline.Window()).durationUs
            val order = traversal(timeline, true)
            val hash = timeline.hashCode()
            coordinator.refreshTrackMetadata(coordinator.currentTrack.value!!.copy(durationMs = 90_000))
            coordinator.next(); coordinator.pause()
            coordinator.clearQueue()
            assertEquals(duration, timeline.getWindow(index, Timeline.Window()).durationUs)
            assertEquals(order, traversal(timeline, true))
            assertEquals(hash, timeline.hashCode())
        }
    }
}
