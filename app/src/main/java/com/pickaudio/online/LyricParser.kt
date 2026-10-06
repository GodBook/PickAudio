package com.pickaudio.online

data class LyricLine(
    val timeMs: Long,
    val text: String,
    var translation: String? = null
)

object LyricParser {
    private val timeRegex = Regex("""\[(\d{1,6}):(\d{1,2})(?:\.(\d{1,3}))?]""")
    private val offsetRegex = Regex("""\[offset:([+-]?\d{1,9})]""", RegexOption.IGNORE_CASE)

    fun parse(lrcContent: String, transContent: String? = null): List<LyricLine> {
        if (lrcContent.isBlank()) return emptyList()

        val lines = mutableListOf<LyricLine>()
        // LRC positive offset advances timestamps; app calibration remains a separate delay.
        val embeddedOffset = offsetRegex.findAll(lrcContent).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        for (rawLine in lrcContent.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val matches = timeRegex.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            val lastMatch = matches.last()
            val text = trimmed.substring(lastMatch.range.last + 1).trim()
            if (text.isEmpty() && trimmed.startsWith("[ti:") || trimmed.startsWith("[ar:") || trimmed.startsWith("[al:")) {
                continue
            }

            for (match in matches) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                if (sec !in 0..59) continue
                val fracStr = match.groupValues[3]
                val millis = if (fracStr.isNotEmpty()) {
                    fracStr.padEnd(3, '0').toLong()
                } else 0L
                val totalMs = (min * 60000L + sec * 1000L + millis - embeddedOffset).coerceAtLeast(0)
                lines.add(LyricLine(timeMs = totalMs, text = text))
            }
        }

        val sorted = lines.sortedBy { it.timeMs }

        // Merge translation if available
        if (!transContent.isNullOrBlank()) {
            val transLines = parse(transContent, null)
            var index = 0
            for (t in transLines) {
                if (t.text.isBlank()) continue
                while (index + 1 < sorted.size && sorted[index + 1].timeMs <= t.timeMs) index++
                val match = listOfNotNull(sorted.getOrNull(index), sorted.getOrNull(index + 1))
                    .filter { kotlin.math.abs(it.timeMs - t.timeMs) < 200 }
                    .minByOrNull { kotlin.math.abs(it.timeMs - t.timeMs) }
                if (match != null && match.translation == null) {
                    match.translation = t.text
                }
            }
        }

        return sorted
    }

    fun findActiveIndex(lyrics: List<LyricLine>, currentPositionMs: Long, offsetMs: Long = 0L): Int {
        if (lyrics.isEmpty()) return -1
        val pos = currentPositionMs - offsetMs
        if (pos < lyrics.first().timeMs) return 0

        var low = 0
        var high = lyrics.size - 1
        var ans = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lyrics[mid].timeMs <= pos) {
                ans = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return ans
    }
}
