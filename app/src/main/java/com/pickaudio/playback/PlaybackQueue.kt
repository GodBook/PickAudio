package com.pickaudio.playback

import com.pickaudio.data.model.PlaybackMode
import com.pickaudio.data.model.Track
import java.util.concurrent.atomic.AtomicLong

internal fun shouldRestartOnPrevious(progressMs: Long): Boolean = progressMs > 3000L

data class PlaybackQueueEntry(val id: Long, val track: Track)
data class QueueSnapshot(
    val entries: List<PlaybackQueueEntry>,
    val currentEntryId: Long?,
    val shufflePool: List<Long>,
    val shuffleHistory: List<Long>,
    val revision: Long
)

/** Production queue policy. Track identity and queue occurrence identity are separate. */
class PlaybackQueue(private val shuffle: (List<Long>) -> List<Long> = { it.shuffled() }) {
    private var items = emptyList<PlaybackQueueEntry>()
    private var currentId: Long? = null
    private val pool = mutableListOf<Long>()
    private val history = mutableListOf<Long>()
    var revision = 0L
        private set
    val entries: List<PlaybackQueueEntry> get() = items
    val currentIndex: Int get() = items.indexOfFirst { it.id == currentId }
    val current: PlaybackQueueEntry? get() = items.getOrNull(currentIndex)

    fun replace(tracks: List<Track>, startIndex: Int = 0) {
        items = tracks.map { PlaybackQueueEntry(ids.incrementAndGet(), it) }
        currentId = items.getOrNull(startIndex.coerceAtLeast(0).coerceAtMost((items.size - 1).coerceAtLeast(0)))?.id
        revision++
        history.clear()
        resetShuffle()
    }

    fun restore(snapshot: QueueSnapshot) {
        require(snapshot.entries.map { it.id }.distinct().size == snapshot.entries.size) { "队列条目编号重复" }
        items = snapshot.entries.toList()
        currentId = snapshot.currentEntryId?.takeIf { id -> items.any { it.id == id } }
        revision++
        val valid = items.map { it.id }.toSet()
        pool.clear()
        pool.addAll(snapshot.shufflePool.filter { it in valid && it != currentId }.distinct())
        history.clear()
        history.addAll(snapshot.shuffleHistory.filter { it in valid }.takeLast(MAX_HISTORY))
        ids.updateAndGet { maxOf(it, items.maxOfOrNull { entry -> entry.id } ?: 0) }
    }

    fun snapshot() = QueueSnapshot(items.toList(), currentId, pool.toList(), history.toList(), revision)

    fun select(index: Int): Track? {
        val entry = items.getOrNull(index) ?: return null
        currentId = entry.id
        return entry.track
    }

    fun insert(track: Track, index: Int = items.size): Int {
        val position = index.coerceIn(0, items.size)
        items = items.toMutableList().apply { add(position, PlaybackQueueEntry(ids.incrementAndGet(), track)) }
        revision++
        resetShuffle()
        return position
    }

    fun replaceCurrent(track: Track) {
        val index = currentIndex
        if (index < 0) select(insert(track))
        else {
            items = items.toMutableList().apply { this[index] = this[index].copy(track = track) }
            revision++
        }
    }
    fun refreshTrack(track: Track) {
        if (items.any { it.track.id == track.id && it.track != track }) {
            items = items.map { if (it.track.id == track.id) it.copy(track = track) else it }
            revision++
        }
    }
    fun canonicalize(mapping: Map<String, Track>): Boolean {
        val updated = items.map { entry -> mapping[entry.track.id]?.id?.takeIf { it != entry.track.id }
            ?.let { entry.copy(track = entry.track.copy(id = it)) } ?: entry }
        if (updated == items) return false
        items = updated
        revision++
        return true
    }

    fun remove(index: Int) {
        val removed = items.getOrNull(index) ?: return
        items = items.toMutableList().apply { removeAt(index) }
        if (currentId == removed.id) currentId = items.getOrNull(if (index < items.size) index else 0)?.id
        history.removeAll { it == removed.id }
        revision++
        resetShuffle()
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        items = items.toMutableList().apply { add(to, removeAt(from)) }
        revision++
        // History and current occurrence remain valid when order changes.
    }

    fun clear() {
        items = emptyList()
        currentId = null
        pool.clear()
        history.clear()
        revision++
    }

    fun resetShuffle(clearHistory: Boolean = false) {
        pool.clear()
        pool.addAll(shuffle(items.map { it.id }.filter { it != currentId }))
        if (clearHistory) history.clear()
    }

    fun peekNext(mode: PlaybackMode): Int? {
        if (items.isEmpty()) return null
        if (currentIndex < 0) return 0
        if (mode != PlaybackMode.SHUFFLE || items.size == 1) return (currentIndex + 1) % items.size
        if (pool.isEmpty()) resetShuffle()
        return items.indexOfFirst { it.id == pool.first() }
    }

    fun peekPrevious(mode: PlaybackMode): Int? {
        if (items.isEmpty()) return null
        if (mode == PlaybackMode.SHUFFLE && history.isNotEmpty()) return items.indexOfFirst { it.id == history.last() }
        return if (currentIndex > 0) currentIndex - 1 else items.lastIndex
    }

    fun next(mode: PlaybackMode, naturalEnd: Boolean = false): Int? {
        if (items.isEmpty()) return null
        val index = currentIndex
        val next = when {
            index < 0 -> 0
            naturalEnd && mode == PlaybackMode.SINGLE_LOOP -> index
            naturalEnd && mode == PlaybackMode.SEQUENTIAL && index == items.lastIndex -> return null
            mode == PlaybackMode.SHUFFLE -> {
                if (items.size == 1) index else {
                    if (pool.isEmpty()) resetShuffle()
                    val id = pool.removeAt(0)
                    items.indexOfFirst { it.id == id }
                }
            }
            else -> (index + 1) % items.size
        }
        if (mode == PlaybackMode.SHUFFLE && currentId != null && next != index) {
            history.add(currentId!!)
            if (history.size > MAX_HISTORY) history.removeAt(0)
        }
        select(next)
        return next
    }

    fun previous(mode: PlaybackMode): Int? {
        if (items.isEmpty()) return null
        val index = if (mode == PlaybackMode.SHUFFLE && history.isNotEmpty()) {
            val id = history.removeAt(history.lastIndex)
            items.indexOfFirst { it.id == id }
        } else (currentIndex - 1).let { if (it >= 0) it else items.lastIndex }
        select(index)
        return index
    }

    companion object {
        private val ids = AtomicLong(System.currentTimeMillis())
        private const val MAX_HISTORY = 1000
    }
}
