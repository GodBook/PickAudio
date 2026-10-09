package com.pickaudio

import com.pickaudio.data.model.*
import com.pickaudio.playback.PlaybackQueue
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    private fun track(id: String) = Track(id, id, "", "", 120000)
    @Test fun duplicateOccurrenceSelectionAndRestoreAreExact() {
        val queue = PlaybackQueue()
        queue.replace(listOf(track("A"), track("B"), track("A")))
        queue.select(2)
        val snapshot = queue.snapshot()
        assertEquals(3, snapshot.entries.map { it.id }.distinct().size)
        val restored = PlaybackQueue()
        restored.restore(snapshot)
        assertEquals(2, restored.currentIndex)
        assertEquals(snapshot.currentEntryId, restored.current!!.id)
    }

    @Test fun naturalCompletionAndManualNextUseProductionModePolicy() {
        val queue = PlaybackQueue()
        queue.replace(listOf(track("A"), track("B")), 1)
        assertNull(queue.next(PlaybackMode.SEQUENTIAL, naturalEnd = true))
        assertEquals(1, queue.currentIndex)
        assertEquals(0, queue.next(PlaybackMode.SEQUENTIAL))
        assertEquals(0, queue.next(PlaybackMode.SINGLE_LOOP, naturalEnd = true))
        assertEquals(1, queue.next(PlaybackMode.SINGLE_LOOP))
        assertEquals(0, queue.next(PlaybackMode.LIST_LOOP, naturalEnd = true))
    }

    @Test fun moveAndRemoveKeepCurrentOccurrenceAndHistoryValid() {
        val queue = PlaybackQueue()
        queue.replace(listOf(track("A"), track("B"), track("A")), 2)
        val selected = queue.current!!.id
        queue.move(2, 0)
        assertEquals(selected, queue.current!!.id)
        assertEquals(0, queue.currentIndex)
        queue.remove(2)
        assertEquals(selected, queue.current!!.id)
        queue.remove(0)
        assertEquals("A", queue.current!!.track.id)
        queue.remove(0)
        assertEquals(-1, queue.currentIndex)
        assertNull(queue.current)
    }

    @Test fun shuffleDoesNotRepeatOccurrencesWithinRoundAndRestoresHistory() {
        val queue = PlaybackQueue { it }
        queue.replace(List(5) { track("T$it") })
        val visited = mutableListOf(queue.current!!.id)
        repeat(4) {
            queue.next(PlaybackMode.SHUFFLE)
            visited.add(queue.current!!.id)
        }
        assertEquals(5, visited.distinct().size)
        val restored = PlaybackQueue { it }
        restored.restore(queue.snapshot())
        restored.previous(PlaybackMode.SHUFFLE)
        assertEquals(visited[3], restored.current!!.id)
        restored.previous(PlaybackMode.SHUFFLE)
        assertEquals(visited[2], restored.current!!.id)
    }

    @Test fun restoredIdsCannotCollideWithAppendedEntries() {
        val queue = PlaybackQueue()
        queue.replace(listOf(track("A")))
        val original = queue.snapshot()
        val futureId = Long.MAX_VALUE / 2
        queue.restore(original.copy(entries = listOf(original.entries[0].copy(id = futureId)), currentEntryId = futureId))
        queue.insert(track("B"))
        assertTrue(queue.entries[1].id > futureId)
    }

    @Test fun shuffleTimelineIsACompletePermutationAcrossRoundsAndDuplicateTracks() {
        val queue = PlaybackQueue { it }
        queue.replace(listOf(track("A"), track("B"), track("A"), track("C")), 3)
        repeat(30) {
            val order = queue.shuffleTimelineOrder()
            assertEquals(listOf(0, 1, 2, 3), order.sorted())
            val position = order.indexOf(queue.currentIndex)
            assertEquals(queue.peekNext(PlaybackMode.SHUFFLE), order[(position + 1) % order.size])
            val snapshot = queue.snapshot()
            assertEquals(order, queue.shuffleTimelineOrder())
            assertEquals(snapshot, queue.snapshot())
            queue.next(PlaybackMode.SHUFFLE, naturalEnd = true)
        }
    }

    @Test fun selectingAnUpcomingSongRemovesItFromTheShufflePool() {
        val queue = PlaybackQueue { it }
        queue.replace(listOf(track("A"), track("B"), track("C")))
        assertEquals(1, queue.peekNext(PlaybackMode.SHUFFLE))
        queue.select(1)
        assertEquals(2, queue.peekNext(PlaybackMode.SHUFFLE))
        assertEquals(listOf(0, 1, 2), queue.shuffleTimelineOrder().sorted())
    }

    @Test fun shuffleTimelineSurvivesHistoryEditsAndRestore() {
        val queue = PlaybackQueue { it }
        queue.replace(List(5) { track("T$it") })
        repeat(3) { queue.next(PlaybackMode.SHUFFLE) }
        queue.previous(PlaybackMode.SHUFFLE)
        var order = queue.shuffleTimelineOrder()
        assertEquals(queue.peekPrevious(PlaybackMode.SHUFFLE), order[order.indexOf(queue.currentIndex) - 1])
        queue.move(4, 0)
        queue.remove(1)
        val restored = PlaybackQueue { it }
        restored.restore(queue.snapshot())
        order = restored.shuffleTimelineOrder()
        assertEquals(restored.entries.indices.toList(), order.sorted())
        assertEquals(restored.peekNext(PlaybackMode.SHUFFLE), order[(order.indexOf(restored.currentIndex) + 1) % order.size])
    }

    @Test fun emptyAndSingleSongShuffleTimelinesRemainValid() {
        val queue = PlaybackQueue { it }
        assertTrue(queue.shuffleTimelineOrder().isEmpty())
        queue.replace(listOf(track("A")))
        assertEquals(listOf(0), queue.shuffleTimelineOrder())
        val restored = PlaybackQueue { it }
        restored.restore(queue.snapshot().copy(shufflePool = emptyList(), shuffleHistory = listOf(queue.current!!.id)))
        assertEquals(listOf(0), restored.shuffleTimelineOrder())
    }
}
