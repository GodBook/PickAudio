package com.pickaudio.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileNotFoundException

/** Unreadable is not the same as deleted: only local, mounted, readable stores can prove absence. */
fun isAudioFileConfirmedMissing(context: Context, uri: Uri): Boolean {
    fun absentFile(file: File): Boolean = try {
        Os.stat(file.absolutePath)
        false
    } catch (e: ErrnoException) {
        e.errno == OsConstants.ENOENT && file.parentFile?.let { it.isDirectory && it.canRead() } == true
    }
    return try {
        when {
            uri.scheme == "file" -> absentFile(File(uri.path ?: return false))
            uri.scheme == "content" && uri.authority == "media" -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    return false
                val volume = MediaStore.getVolumeName(uri)
                val mounted = MediaStore.getExternalVolumeNames(context)
                // The legacy merged external collection does not identify which volume
                // owned a deleted row. It could belong to a currently removed SD card.
                if (volume == MediaStore.VOLUME_EXTERNAL) return false
                if (volume != MediaStore.VOLUME_INTERNAL && volume !in mounted) return false
                // A successful query with permission on a mounted volume can prove a row
                // was removed. Provider failure/null cursors cannot.
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) true
                    else {
                        val path = cursor.getString(0) ?: return false
                        try {
                            context.contentResolver.openFileDescriptor(uri, "r")?.close()
                            false
                        } catch (_: FileNotFoundException) { absentFile(File(path)) }
                    }
                } ?: false
            }
            uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents" &&
                DocumentsContract.isTreeUri(uri) -> {
                val documentId = DocumentsContract.getDocumentId(uri)
                val rootId = DocumentsContract.getTreeDocumentId(uri)
                if (!documentId.startsWith("$rootId/") || '/' !in documentId) return false
                val volume = documentId.substringBefore(':').let { if (it == "primary") MediaStore.VOLUME_EXTERNAL_PRIMARY else it.lowercase() }
                if (volume !in MediaStore.getExternalVolumeNames(context)) return false
                val parentId = documentId.substringBeforeLast('/')
                val parent = DocumentsContract.buildDocumentUriUsingTree(uri, parentId)
                val column = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                // Both queries require the live tree permission. Confirm the parent itself
                // still exists before accepting an empty child listing as evidence.
                val parentExists = context.contentResolver.query(parent, column, null, null, null)?.use {
                    it.moveToFirst() && it.getString(0) == parentId
                } ?: false
                if (!parentExists) return false
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentId)
                context.contentResolver.query(children, column, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) if (cursor.getString(0) == documentId) return false
                    true
                } ?: false
            }
            else -> false // Single-file grants, cloud providers and backup hints need relinking.
        }
    } catch (_: Exception) { false }
}
