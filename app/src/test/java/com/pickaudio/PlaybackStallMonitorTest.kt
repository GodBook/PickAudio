package com.pickaudio

import com.pickaudio.playback.PlaybackStallMonitor
import org.junit.Assert.*
import org.junit.Test

class PlaybackStallMonitorTest {
    @Test fun silentBufferingHasDeadline() {
        val monitor = PlaybackStallMonitor()
        assertFalse(monitor.stalled(0, true, true, 4000, 4000))
        assertFalse(monitor.stalled(19999, true, true, 4000, 4000))
        assertTrue(monitor.stalled(20000, true, true, 4000, 4000))
    }
    @Test fun slowTrickleCannotExtendBufferingForever() {
        val monitor = PlaybackStallMonitor()
        for (second in 0..59) assertFalse(monitor.stalled(second * 1000L, true, true, 0, second.toLong()))
        assertTrue(monitor.stalled(60000, true, true, 0, 60))
    }
    @Test fun pauseAndFocusSuppressionResetDeadlines() {
        val monitor = PlaybackStallMonitor()
        assertFalse(monitor.stalled(0, true, true, 5000, 5000))
        assertFalse(monitor.stalled(30000, false, true, 5000, 5000))
        assertFalse(monitor.stalled(90000, true, true, 5000, 5000))
        assertFalse(monitor.stalled(100000, true, false, 6000, 8000))
    }
    @Test fun readyPlayerWithFrozenClockIsDetectedButNormalPlaybackIsNot() {
        val monitor = PlaybackStallMonitor()
        for (second in 0..100) assertFalse(monitor.stalled(second * 1000L, true, false, second * 1000L, 200000))
        assertTrue(monitor.stalled(120000, true, false, 100000, 200000))
    }
}
