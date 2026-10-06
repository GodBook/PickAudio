package com.pickaudio.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackMetrics(val plays: Int = 0, val firstAudioMs: Long? = null, val buffers: Int = 0,
    val resolveFailures: Int = 0, val cachedBytes: Long = 0, val servedBytes: Long = 0,
    val lastError: String? = null) {
    val cachePercent get() = if (servedBytes <= 0) 0 else (cachedBytes * 100 / servedBytes).coerceIn(0, 100)
}

/** Process-local counters only: no URLs, song names or network telemetry. */
object PlaybackDiagnostics {
    private val mutable = MutableStateFlow(PlaybackMetrics())
    val metrics = mutable.asStateFlow()
    private var startedAt = 0L
    private var waitingForAudio = false
    @Synchronized fun begin() { startedAt = SystemClock.elapsedRealtime(); waitingForAudio = true; mutable.value = mutable.value.copy(plays = mutable.value.plays + 1) }
    @Synchronized fun playing() { if (waitingForAudio) { waitingForAudio = false; mutable.value = mutable.value.copy(firstAudioMs = SystemClock.elapsedRealtime() - startedAt) } }
    @Synchronized fun buffering() { mutable.value = mutable.value.copy(buffers = mutable.value.buffers + 1) }
    @Synchronized fun failed(reason: String?) { waitingForAudio = false; mutable.value = mutable.value.copy(resolveFailures = mutable.value.resolveFailures + 1, lastError = reason?.take(200)) }
    @Synchronized fun bytes(count: Int) { if (count > 0) mutable.value = mutable.value.copy(servedBytes = mutable.value.servedBytes + count) }
    @Synchronized fun cached(count: Long) { if (count > 0) mutable.value = mutable.value.copy(cachedBytes = mutable.value.cachedBytes + count) }
}
