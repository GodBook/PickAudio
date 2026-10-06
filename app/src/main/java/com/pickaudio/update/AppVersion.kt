package com.pickaudio.update

internal fun isNewerAppVersion(
    currentName: String, currentCode: Long, remoteName: String, remoteCode: Int
): Boolean {
    fun parts(name: String): List<Int>? = name.trim().removePrefix("v").removePrefix("V")
        .split(".").map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }

    val current = parts(currentName)
    val remote = parts(remoteName)
    if (current != null && remote != null) {
        for (index in 0 until maxOf(current.size, remote.size)) {
            val difference = remote.getOrElse(index) { 0 }.compareTo(current.getOrElse(index) { 0 })
            if (difference != 0) return difference > 0
        }
    }
    return remoteCode > currentCode
}
