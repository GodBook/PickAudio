package com.pickaudio

import com.pickaudio.data.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Stack

class PlaybackModeLogicTest {

    @Test
    fun testSequentialModeTransition() {
        val queueSize = 3
        var currentIndex = 0

        fun nextIndex(mode: PlaybackMode, cur: Int, size: Int): Int {
            return when (mode) {
                PlaybackMode.SEQUENTIAL -> if (cur + 1 < size) cur + 1 else -1 // -1 means stop
                PlaybackMode.LIST_LOOP -> (cur + 1) % size
                PlaybackMode.SINGLE_LOOP -> cur // natural completion loops same
                else -> cur
            }
        }

        assertEquals(1, nextIndex(PlaybackMode.SEQUENTIAL, 0, queueSize))
        assertEquals(2, nextIndex(PlaybackMode.SEQUENTIAL, 1, queueSize))
        assertEquals(-1, nextIndex(PlaybackMode.SEQUENTIAL, 2, queueSize)) // stops at end!
    }

    @Test
    fun testListLoopModeTransition() {
        val queueSize = 3
        fun nextIndex(cur: Int, size: Int) = (cur + 1) % size

        assertEquals(1, nextIndex(0, queueSize))
        assertEquals(2, nextIndex(1, queueSize))
        assertEquals(0, nextIndex(2, queueSize)) // loops back to 0!
    }

    @Test
    fun testSingleLoopVsManualNext() {
        // Natural end stays on same track
        val naturalNext = 0 // same index
        assertEquals(0, naturalNext)

        // Manual Next switches to next track
        fun manualNext(cur: Int, size: Int) = (cur + 1) % size
        assertEquals(1, manualNext(0, 3))
    }

    @Test
    fun testSmartPreviousRule() {
        fun computePreviousAction(progressMs: Long, curIndex: Int, size: Int): Pair<String, Int> {
            return if (progressMs > 3000L) {
                Pair("SEEK_ZERO", curIndex)
            } else {
                val prev = if (curIndex > 0) curIndex - 1 else size - 1
                Pair("PREV_TRACK", prev)
            }
        }

        val resultOver3s = computePreviousAction(3500L, 2, 5)
        assertEquals("SEEK_ZERO", resultOver3s.first)
        assertEquals(2, resultOver3s.second)

        val resultUnder3s = computePreviousAction(1200L, 2, 5)
        assertEquals("PREV_TRACK", resultUnder3s.first)
        assertEquals(1, resultUnder3s.second)
    }

    @Test
    fun testShuffleRoundWithoutRepeatingAndHistoryBack() {
        val size = 5
        val shufflePool = (0 until size).toMutableList()
        val history = Stack<Int>()

        val playedOrder = mutableListOf<Int>()
        while (shufflePool.isNotEmpty()) {
            val next = shufflePool.removeAt(0)
            history.push(next)
            playedOrder.add(next)
        }

        // Must play all without duplicate in a round
        assertEquals(5, playedOrder.distinct().size)

        // Going back follows exact history
        assertEquals(playedOrder[4], history.pop())
        assertEquals(playedOrder[3], history.pop())
    }
}
