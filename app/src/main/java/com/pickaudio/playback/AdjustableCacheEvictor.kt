package com.pickaudio.playback

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AdjustableCacheEvictor(private var capacity: Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position })
    private var bytes = 0L
    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) { evict(cache, length.coerceAtLeast(0)) }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) { if (spans.add(span)) bytes += span.length; evict(cache, 0) }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) { if (spans.remove(span)) bytes -= span.length }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan)
    }
    fun resize(cache: Cache, limit: Long) = synchronized(cache) { capacity = limit; evict(cache, 0) }
    private fun evict(cache: Cache, incoming: Long) {
        while (bytes + incoming > capacity && spans.isNotEmpty()) cache.removeSpan(spans.first())
    }
}
