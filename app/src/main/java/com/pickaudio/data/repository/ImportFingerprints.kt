package com.pickaudio.data.repository

import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Disposable hints only. Unknown modification identity always falls back to inspecting the file. */
internal class ImportFingerprints(context: Context) {
    private val preferences = context.getSharedPreferences("import_fingerprints_v1", Context.MODE_PRIVATE)
    private val pending = ConcurrentHashMap<String, String>()
    private fun key(uri: String) = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun matches(uri: String, stamp: String?): Boolean = stamp != null && key(uri).let {
        (pending[it] ?: preferences.getString(it, null)) == stamp
    }
    fun record(uri: String, stamp: String?) { if (stamp != null) pending[key(uri)] = stamp }
    fun flush() {
        val snapshot = pending.toMap()
        if (snapshot.isEmpty()) return
        val editor = preferences.edit()
        snapshot.forEach { (key, value) -> editor.putString(key, value) }
        if (editor.commit()) snapshot.forEach { (key, value) -> pending.remove(key, value) }
    }
}

internal fun importStamp(size: Long, modified: Long, generation: Long = 0): String? =
    if (size > 0 && (modified > 0 || generation > 0)) "v1:$size:$modified:$generation" else null
