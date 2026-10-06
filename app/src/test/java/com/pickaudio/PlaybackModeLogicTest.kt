package com.pickaudio

import com.pickaudio.data.model.PlaybackMode
import com.pickaudio.data.model.Track
import com.pickaudio.playback.PlaybackQueue
import com.pickaudio.playback.shouldRestartOnPrevious
import org.junit.Assert.*
import org.junit.Test

/** Exercises the policy used by both app controls and the media session. */
class PlaybackModeLogicTest {
    private fun queue(size: Int = 3) = PlaybackQueue { it }.apply {
        replace((0 until size).map { Track("track$it", "歌曲$it", "", "", 10000) })
    }
    @Test fun testSequentialModeTransition() {
        val q = queue()
        assertEquals(1, q.next(PlaybackMode.SEQUENTIAL, true))
        assertEquals(2, q.next(PlaybackMode.SEQUENTIAL, true))
        assertNull(q.next(PlaybackMode.SEQUENTIAL, true))
    }
    @Test fun testListLoopModeTransition() {
        val q = queue()
        assertEquals(1, q.next(PlaybackMode.LIST_LOOP, true))
        assertEquals(2, q.next(PlaybackMode.LIST_LOOP, true))
        assertEquals(0, q.next(PlaybackMode.LIST_LOOP, true))
    }
    @Test fun testSingleLoopVsManualNext() {
        val q = queue()
        assertEquals(0, q.next(PlaybackMode.SINGLE_LOOP, true))
        assertEquals(1, q.next(PlaybackMode.SINGLE_LOOP, false))
    }
    @Test fun testSmartPreviousRule() {
        assertTrue(shouldRestartOnPrevious(3500))
        assertFalse(shouldRestartOnPrevious(3000))
        assertFalse(shouldRestartOnPrevious(1200))
        val q = queue(5)
        q.select(2)
        assertEquals(1, q.previous(PlaybackMode.SEQUENTIAL))
    }
    @Test fun testShuffleRoundWithoutRepeatingAndHistoryBack() {
        val q = queue(5)
        val played = mutableListOf(q.current!!.id)
        repeat(4) { q.next(PlaybackMode.SHUFFLE); played.add(q.current!!.id) }
        assertEquals(5, played.distinct().size)
        q.previous(PlaybackMode.SHUFFLE)
        assertEquals(played[3], q.current!!.id)
        q.previous(PlaybackMode.SHUFFLE)
        assertEquals(played[2], q.current!!.id)
    }
    @Test fun testQueueItemRemoval() {
        val q = queue()
        q.select(1)
        val occurrence = q.current!!.id
        q.remove(2)
        assertEquals(1, q.currentIndex)
        q.remove(0)
        assertEquals(0, q.currentIndex)
        assertEquals(occurrence, q.current!!.id)
    }
}
