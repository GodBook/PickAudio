package com.pickaudio.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val releasePageUrl: String,
    val apkSize: Long = 0L,
    val publishTime: String = ""
)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateStatus
    data class Downloaded(val info: UpdateInfo, val apkFile: File) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}

private data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("html_url") val htmlUrl: String?,
    @SerializedName("published_at") val publishedAt: String?,
    @SerializedName("assets") val assets: List<GitHubAsset>?
)

private data class GitHubAsset(
    @SerializedName("name") val name: String?,
    @SerializedName("size") val size: Long?,
    @SerializedName("browser_download_url") val downloadUrl: String?
)

private data class VersionManifest(
    @SerializedName("versionCode") val versionCode: Int?,
    @SerializedName("versionName") val versionName: String?,
    @SerializedName("changelog") val changelog: String?,
    @SerializedName("downloadUrl") val downloadUrl: String?,
    @SerializedName("releasePageUrl") val releasePageUrl: String?,
    @SerializedName("apkSize") val apkSize: Long?,
    @SerializedName("publishTime") val publishTime: String?
)

class AppUpdateManager(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private var downloadJob: Job? = null
    private val gson = Gson()

    val currentVersionName: String
        get() = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }

    val currentVersionCode: Long
        get() = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (_: Exception) {
            1L
        }

    suspend fun checkForUpdates(): UpdateStatus = withContext(Dispatchers.IO) {
        _status.value = UpdateStatus.Checking

        val info = fetchFromGitHubReleases() ?: fetchFromVersionJson()

        val result = if (info == null) {
            UpdateStatus.Error("无法获取版本信息，请检查网络连接")
        } else if (isNewer(info.versionName, info.versionCode)) {
            UpdateStatus.Available(info)
        } else {
            UpdateStatus.UpToDate
        }

        _status.value = result
        result
    }

    private fun fetchFromGitHubReleases(): UpdateInfo? {
        val url = "https://api.github.com/repos/GodBook/PickAudio/releases/latest"
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "PickAudio-App")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val release = gson.fromJson(body, GitHubRelease::class.java) ?: return null

                val tagName = release.tagName?.trim() ?: return null
                val vName = tagName.removePrefix("v").removePrefix("V")
                val apkAsset = release.assets?.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }

                val downloadUrl = apkAsset?.downloadUrl
                    ?: "https://github.com/GodBook/PickAudio/releases/download/$tagName/PickAudio-$tagName-release.apk"

                val apkSize = apkAsset?.size ?: 0L
                val changelog = release.body?.takeIf { it.isNotBlank() } ?: (release.name ?: "版本更新")

                return UpdateInfo(
                    versionCode = extractVersionCode(vName),
                    versionName = vName,
                    changelog = changelog,
                    downloadUrl = downloadUrl,
                    releasePageUrl = release.htmlUrl ?: "https://github.com/GodBook/PickAudio/releases",
                    apkSize = apkSize,
                    publishTime = release.publishedAt?.take(10) ?: ""
                )
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun fetchFromVersionJson(): UpdateInfo? {
        val candidates = listOf(
            "https://raw.githubusercontent.com/GodBook/PickAudio/main/version.json",
            "https://raw.gitmirror.com/GodBook/PickAudio/main/version.json",
            "https://cdn.jsdelivr.net/gh/GodBook/PickAudio@main/version.json"
        )

        for (url in candidates) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "PickAudio-App")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val manifest = gson.fromJson(body, VersionManifest::class.java) ?: return@use
                        val vName = manifest.versionName ?: return@use

                        return UpdateInfo(
                            versionCode = manifest.versionCode ?: extractVersionCode(vName),
                            versionName = vName,
                            changelog = manifest.changelog ?: "版本更新",
                            downloadUrl = manifest.downloadUrl ?: "",
                            releasePageUrl = manifest.releasePageUrl ?: "https://github.com/GodBook/PickAudio/releases",
                            apkSize = manifest.apkSize ?: 0L,
                            publishTime = manifest.publishTime ?: ""
                        )
                    }
                }
            } catch (_: Exception) {
                // Continue to next candidate mirror
            }
        }
        return null
    }

    private fun isNewer(remoteVersionName: String, remoteVersionCode: Int): Boolean {
        if (remoteVersionCode > currentVersionCode) return true

        val currentParts = currentVersionName.removePrefix("v").removePrefix("V")
            .split(".").map { it.toIntOrNull() ?: 0 }
        val remoteParts = remoteVersionName.removePrefix("v").removePrefix("V")
            .split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(currentParts.size, remoteParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun extractVersionCode(versionName: String): Int {
        val digits = versionName.filter { it.isDigit() }
        return digits.toIntOrNull() ?: 1
    }

    suspend fun startDownload(info: UpdateInfo) = withContext(Dispatchers.IO) {
        if (info.downloadUrl.isBlank()) {
            _status.value = UpdateStatus.Error("下载地址无效")
            return@withContext
        }

        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        val apkFile = File(updatesDir, "PickAudio-v${info.versionName}.apk")
        if (apkFile.exists()) {
            apkFile.delete()
        }

        _status.value = UpdateStatus.Downloading(0f, 0L, info.apkSize)

        try {
            val request = Request.Builder()
                .url(info.downloadUrl)
                .header("User-Agent", "PickAudio-App")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _status.value = UpdateStatus.Error("下载失败: HTTP ${response.code}")
                    return@withContext
                }

                val body = response.body ?: run {
                    _status.value = UpdateStatus.Error("下载响应为空")
                    return@withContext
                }

                val totalLength = if (body.contentLength() > 0) body.contentLength() else info.apkSize
                var downloadedBytes = 0L

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(apkFile)

                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val progress = if (totalLength > 0) {
                        (downloadedBytes.toFloat() / totalLength).coerceIn(0f, 1f)
                    } else {
                        0.5f
                    }
                    _status.value = UpdateStatus.Downloading(progress, downloadedBytes, totalLength)
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                _status.value = UpdateStatus.Downloaded(info, apkFile)
            }
        } catch (e: CancellationException) {
            if (apkFile.exists()) apkFile.delete()
            _status.value = UpdateStatus.Idle
            throw e
        } catch (e: Exception) {
            if (apkFile.exists()) apkFile.delete()
            _status.value = UpdateStatus.Error("下载异常: ${e.localizedMessage ?: e.message}")
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _status.value = UpdateStatus.Idle
    }

    fun dismissUpdate() {
        _status.value = UpdateStatus.Idle
    }

    fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            _status.value = UpdateStatus.Error("安装包文件不存在，请重新下载")
            return
        }

        // Check unknown source install permission on Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _status.value = UpdateStatus.Error("调起安装程序失败: ${e.localizedMessage}")
        }
    }

    fun openBrowserReleasePage(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
