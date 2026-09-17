package com.pickaudio.online

data class LyricLine(
    val timeMs: Long,
    val text: String,
    var translation: String? = null
)

object LyricParser {
    private val timeRegex = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?]""")

    fun parse(lrcContent: String, transContent: String? = null): List<LyricLine> {
        if (lrcContent.isBlank()) return emptyList()

        val lines = mutableListOf<LyricLine>()
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
                val fracStr = match.groupValues[3]
                val millis = if (fracStr.isNotEmpty()) {
                    if (fracStr.length == 2) fracStr.toLong() * 10 else fracStr.toLong()
                } else 0L
                val totalMs = min * 60000L + sec * 1000L + millis
                lines.add(LyricLine(timeMs = totalMs, text = text))
            }
        }

        val sorted = lines.sortedBy { it.timeMs }

        // Merge translation if available
        if (!transContent.isNullOrBlank()) {
            val transLines = parse(transContent, null)
            for (t in transLines) {
                if (t.text.isBlank()) continue
                // Find matching line within 200ms
                val match = sorted.find { kotlin.math.abs(it.timeMs - t.timeMs) < 200 }
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
