package com.pickaudio.download

object ResumePolicy {
    private val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    fun canResume(offset: Long, previousEtag: String?, status: Int, contentRange: String?, etag: String?): Boolean {
        if (offset <= 0 || status != 206 || previousEtag == null || previousEtag != etag) return false
        val match = range.matchEntire(contentRange.orEmpty()) ?: return false
        val start = match.groupValues[1].toLongOrNull() ?: return false
        val end = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3].toLongOrNull() ?: return false
        return start == offset && end >= start && total > end && end == total - 1
    }
}
