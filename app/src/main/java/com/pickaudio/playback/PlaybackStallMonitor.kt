package com.pickaudio.playback

/** Monotonic progress deadlines; focus loss and explicit pauses are not network stalls. */
internal class PlaybackStallMonitor(
    private val idleTimeoutMs: Long = 20_000,
    private val bufferingTimeoutMs: Long = 60_000
) {
    private var lastProgressAt: Long? = null
    private var bufferingSince: Long? = null
    private var lastPosition = Long.MIN_VALUE
    private var lastBuffered = Long.MIN_VALUE

    fun stalled(now: Long, active: Boolean, buffering: Boolean, position: Long, buffered: Long): Boolean {
        if (!active) {
            reset()
            return false
        }
        if (buffering) {
            if (bufferingSince == null) bufferingSince = now
        } else bufferingSince = null
        if (lastProgressAt == null || position != lastPosition || (buffering && buffered > lastBuffered)) {
            lastProgressAt = now
        }
        lastPosition = position
        lastBuffered = buffered
        return now - checkNotNull(lastProgressAt) >= idleTimeoutMs ||
            bufferingSince?.let { now - it >= bufferingTimeoutMs } == true
    }

    private fun reset() {
        lastProgressAt = null
        bufferingSince = null
        lastPosition = Long.MIN_VALUE
        lastBuffered = Long.MIN_VALUE
    }
}
