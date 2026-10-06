package com.pickaudio.online

import com.pickaudio.data.model.SearchSongItem
import kotlin.math.abs

object VersionMatcher {
    private fun normalize(value: String) = value.lowercase().replace(Regex("[\\s\\p{P}]+"), "")
    fun sameRecording(title: String, artist: String, durationMs: Long, candidate: SearchSongItem): Boolean {
        if (normalize(title) != normalize(candidate.title)) return false
        val singers = { value: String -> value.lowercase().split(Regex("[、/&;,，]")).map(::normalize).filter { it.isNotEmpty() }.toSet() }
        if (singers(artist).isEmpty() || singers(artist) != singers(candidate.artist)) return false
        return durationMs > 0 && candidate.durationMs > 0 && abs(durationMs - candidate.durationMs) <= 2000
    }
}

class AlternativeVersionException(val candidates: List<SearchSongItem>) :
    IllegalStateException("该来源暂时无法提供原版本，请确认其他平台的候选歌曲")
