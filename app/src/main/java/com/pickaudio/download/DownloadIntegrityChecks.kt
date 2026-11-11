package com.pickaudio.download

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.pickaudio.data.db.DownloadTaskEntity
import java.security.MessageDigest

/** Only successful packet checks are memoized. The app excludes shared preferences from backup. */
internal class DownloadIntegrityChecks(private val context: Context) {
    private val preferences = context.getSharedPreferences("download_integrity_checks", Context.MODE_PRIVATE)

    fun fingerprint(task: DownloadTaskEntity, expectedDurationMs: Long): String? = runCatching {
        val uri = Uri.parse(requireNotNull(task.targetUri))
        val version = MediaStore.getVersion(context, MediaStore.getVolumeName(uri))?.takeIf { it.isNotBlank() }
            ?: return null
        val statSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            ?.takeIf { it >= 0 } ?: return null
        val columns = arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED)
        val metadata = context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || columns.indices.any { cursor.isNull(it) }) return null
            columns.indices.map { cursor.getLong(it) }
        } ?: return null
        val identity = listOf("packets-v1", task.id, task.targetUri, task.executionGeneration, task.updatedAt,
            task.targetQuality, expectedDurationMs, version, statSize, metadata.joinToString(":")).joinToString("\u0000")
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun wasChecked(taskId: String, fingerprint: String?): Boolean =
        fingerprint != null && preferences.getString(taskId, null) == fingerprint

    fun passed(taskId: String, fingerprint: String) { preferences.edit().putString(taskId, fingerprint).apply() }
    fun forget(taskId: String) { preferences.edit().remove(taskId).apply() }
    fun retain(taskIds: Set<String>) {
        val obsolete = preferences.all.keys - taskIds
        if (obsolete.isNotEmpty()) preferences.edit().also { edit -> obsolete.forEach { edit.remove(it) } }.apply()
    }
}
