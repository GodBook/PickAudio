package com.pickaudio.data.repository

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException

data class AssetAccess(val available: Boolean, val reason: String? = null)

fun checkAssetAccess(context: Context, uri: Uri): AssetAccess = try {
    when (uri.scheme) {
        "file" -> if (File(uri.path.orEmpty()).isFile) AssetAccess(true) else AssetAccess(false, "文件已移动或删除")
        "content" -> context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { AssetAccess(true) }
            ?: AssetAccess(false, "文件已移动或删除")
        else -> AssetAccess(false, "需要重新关联音频文件")
    }
} catch (_: SecurityException) { AssetAccess(false, "文件访问权限已失效，请重新授权") }
catch (_: FileNotFoundException) { AssetAccess(false, "文件已移动或删除") }
catch (_: Exception) { AssetAccess(false, "暂时无法读取文件，请检查提供者或授权") }
