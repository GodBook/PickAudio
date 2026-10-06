package com.pickaudio.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileNotFoundException

internal const val DELETION_PENDING = "删除待确认"

data class AssetDeletionFailure(val uri: String, val reason: String)
data class TrackDeletionReport(val recordRemoved: Boolean, val deletedFiles: Int = 0,
    val missingFiles: Int = 0, val failedFiles: List<AssetDeletionFailure> = emptyList(), val error: String? = null)

internal enum class AssetDeletionResult { DELETED, MISSING }

/** Only single audio resources may be deleted. A directory is never a deletion target. */
internal fun deleteAudioAsset(context: Context, value: String): AssetDeletionResult {
    val uri = Uri.parse(value)
    if (uri.scheme == "missing") return AssetDeletionResult.MISSING
    if (uri.scheme == "file" || uri.scheme == null) {
        val file = File(uri.path ?: value)
        if (!file.exists()) return AssetDeletionResult.MISSING
        require(file.isFile) { "所选资源不是音频文件，未删除目录" }
        check(file.delete()) { "文件删除失败，请检查写入权限" }
        return AssetDeletionResult.DELETED
    }
    require(uri.scheme == "content") { "不支持删除此类文件地址" }
    require(DocumentFile.fromSingleUri(context, uri)?.isDirectory != true) { "所选资源是目录，未删除目录" }
    try {
        val present = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        if (!present) return AssetDeletionResult.MISSING
    } catch (_: FileNotFoundException) { return AssetDeletionResult.MISSING }
    if (context.contentResolver.delete(uri, null, null) > 0) return AssetDeletionResult.DELETED
    if (DocumentFile.fromSingleUri(context, uri)?.delete() == true) return AssetDeletionResult.DELETED
    error("文件删除失败，请重新授予写入或删除权限")
}
