package com.pickaudio.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackMetrics(val plays: Int = 0, val firstAudioMs: Long? = null, val buffers: Int = 0,
    val resolveFailures: Int = 0, val cachedBytes: Long = 0, val servedBytes: Long = 0,
    val lastError: String? = null, val audioOutputMs: Long? = null, val stagesMs: Map<String, Long> = emptyMap(),
    val rebufferCount: Int = 0, val bufferedMs: Long = 0, val bufferReasons: Map<String, Int> = emptyMap()) {
    val cachePercent get() = if (servedBytes <= 0) 0 else (cachedBytes * 100 / servedBytes).coerceIn(0, 100)
}

/** Process-local counters only: no URLs, song names or network telemetry. */
object PlaybackDiagnostics {
    private val mutable = MutableStateFlow(PlaybackMetrics())
    val metrics = mutable.asStateFlow()
    private var startedAt = 0L
    private var waitingForAudio = false
    private var waitingForOutput = false
    private var servedBytes = 0L
    private var cachedBytes = 0L
    private var publishedAt = 0L
    private var bufferingAt: Long? = null
    private var bufferReason = "initial"
    private var lastSeekAt = Long.MIN_VALUE
    @Synchronized fun begin() {
        startedAt = SystemClock.elapsedRealtime(); waitingForAudio = true; waitingForOutput = true
        bufferingAt = null; lastSeekAt = Long.MIN_VALUE
        mutable.value = mutable.value.copy(plays = mutable.value.plays + 1, firstAudioMs = null, audioOutputMs = null, stagesMs = emptyMap())
    }
    @Synchronized fun playing() { if (waitingForAudio) { waitingForAudio = false; mutable.value = mutable.value.copy(firstAudioMs = SystemClock.elapsedRealtime() - startedAt) } }
    @Synchronized fun audioOutput() { if (waitingForOutput) { waitingForOutput = false; mutable.value = mutable.value.copy(audioOutputMs = SystemClock.elapsedRealtime() - startedAt) } }
    @Synchronized fun stage(name: String, milliseconds: Long) { mutable.value = mutable.value.copy(stagesMs = mutable.value.stagesMs + (name to milliseconds.coerceAtLeast(0))) }
    @Synchronized fun seeking() { lastSeekAt = SystemClock.elapsedRealtime() }
    @Synchronized fun buffering() {
        if (bufferingAt != null) return
        val now = SystemClock.elapsedRealtime()
        bufferingAt = now
        bufferReason = when { waitingForAudio -> "initial"; lastSeekAt != Long.MIN_VALUE && now - lastSeekAt in 0..2000 -> "seek"; else -> "rebuffer" }
        mutable.value = mutable.value.copy(buffers = mutable.value.buffers + 1,
            rebufferCount = mutable.value.rebufferCount + if (bufferReason == "rebuffer") 1 else 0,
            bufferReasons = mutable.value.bufferReasons + (bufferReason to ((mutable.value.bufferReasons[bufferReason] ?: 0) + 1)))
    }
    @Synchronized fun bufferEnded() {
        val start = bufferingAt ?: return
        bufferingAt = null
        val duration = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0)
        mutable.value = mutable.value.copy(bufferedMs = mutable.value.bufferedMs + duration,
            stagesMs = mutable.value.stagesMs + ("buffer.$bufferReason" to duration))
    }
    @Synchronized fun failed(reason: String?) { waitingForAudio = false; waitingForOutput = false; mutable.value = mutable.value.copy(resolveFailures = mutable.value.resolveFailures + 1, lastError = reason?.take(200)) }
    @Synchronized fun bytes(count: Int) {
        if (count > 0) { servedBytes += count; publishBytes(false) }
    }
    @Synchronized fun cached(count: Long) { if (count > 0) { cachedBytes += count; publishBytes(false) } }
    private fun publishBytes(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (force || now - publishedAt >= 1000) {
            publishedAt = now
            mutable.value = mutable.value.copy(servedBytes = servedBytes, cachedBytes = cachedBytes)
        }
    }
    @Synchronized fun snapshot(): PlaybackMetrics { publishBytes(true); return mutable.value }
}
