package com.pickaudio.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import com.pickaudio.network.withResponse
import com.pickaudio.network.readLimitedText
import android.content.pm.PackageManager
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val releasePageUrl: String,
    val apkSize: Long = 0L,
    val publishTime: String = "",
    val sha256: String? = null
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
    @SerializedName("browser_download_url") val downloadUrl: String?,
    @SerializedName("digest") val digest: String?
)

private data class VersionManifest(
    @SerializedName("versionCode") val versionCode: Int?,
    @SerializedName("versionName") val versionName: String?,
    @SerializedName("changelog") val changelog: String?,
    @SerializedName("downloadUrl") val downloadUrl: String?,
    @SerializedName("releasePageUrl") val releasePageUrl: String?,
    @SerializedName("apkSize") val apkSize: Long?,
    @SerializedName("publishTime") val publishTime: String?,
    @SerializedName("sha256") val sha256: String?
)

class AppUpdateManager(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .build(),
    private val packageValidator: ((File, UpdateInfo) -> Unit)? = null,
    private val urlValidator: (String) -> Unit = ::requireOfficialUpdateUrl
) {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private var downloadJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationLock = Any()
    private var generation = 0L
    private var downloadedInfo: UpdateInfo? = null
    private val updatesDirectory get() = File(context.cacheDir, "updates")
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
        val token = synchronized(operationLock) {
            if (downloadJob?.isActive == true) return@withContext _status.value
            ++generation
        }
        publishStatus(token, UpdateStatus.Checking)

        val info = withTimeoutOrNull(15_000) {
            fetchFromGitHubReleases() ?: fetchFromVersionJson()
        }

        val result = if (info == null) {
            UpdateStatus.Error("无法获取版本信息，请检查网络连接")
        } else if (isNewer(info.versionName, info.versionCode)) {
            UpdateStatus.Available(info)
        } else {
            UpdateStatus.UpToDate
        }

        publishStatus(token, result)
        result
    }

    private suspend fun fetchFromGitHubReleases(): UpdateInfo? {
        val url = "https://api.github.com/repos/GodBook/PickAudio/releases/latest"
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "PickAudio-App")
                .build()

            return client.withResponse(request) { response ->
                if (!response.isSuccessful) return@withResponse null
                val body = response.body?.readLimitedText(2 * 1024 * 1024) ?: return@withResponse null
                val release = gson.fromJson(body, GitHubRelease::class.java) ?: return@withResponse null

                val tagName = release.tagName?.trim() ?: return@withResponse null
                val vName = tagName.removePrefix("v").removePrefix("V")
                val apkAsset = release.assets?.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }

                val downloadUrl = apkAsset?.downloadUrl
                    ?: "https://github.com/GodBook/PickAudio/releases/download/$tagName/PickAudio-$tagName-release.apk"

                val apkSize = apkAsset?.size ?: 0L
                val changelog = release.body?.takeIf { it.isNotBlank() } ?: (release.name ?: "版本更新")

                UpdateInfo(
                    // GitHub tags contain version names, not Android build codes.
                    versionCode = 0,
                    versionName = vName,
                    changelog = changelog,
                    downloadUrl = downloadUrl,
                    releasePageUrl = release.htmlUrl ?: "https://github.com/GodBook/PickAudio/releases",
                    apkSize = apkSize,
                    publishTime = release.publishedAt?.take(10) ?: "",
                    sha256 = apkAsset?.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    private suspend fun fetchFromVersionJson(): UpdateInfo? {
        val candidates = listOf(
            "https://raw.githubusercontent.com/GodBook/PickAudio/main/version.json",
            "https://cdn.jsdelivr.net/gh/GodBook/PickAudio@main/version.json"
        )

        for (url in candidates) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "PickAudio-App")
                    .build()

                val info = client.withResponse(request) { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.readLimitedText(2 * 1024 * 1024) ?: return@withResponse null
                        val manifest = gson.fromJson(body, VersionManifest::class.java) ?: return@withResponse null
                        val vName = manifest.versionName ?: return@withResponse null

                        UpdateInfo(
                            versionCode = manifest.versionCode ?: 0,
                            versionName = vName,
                            changelog = manifest.changelog ?: "版本更新",
                            downloadUrl = manifest.downloadUrl ?: "",
                            releasePageUrl = manifest.releasePageUrl ?: "https://github.com/GodBook/PickAudio/releases",
                            apkSize = manifest.apkSize ?: 0L,
                            publishTime = manifest.publishTime ?: "",
                            sha256 = manifest.sha256
                        )
                    } else null
                }
                if (info != null) return info
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Continue to next candidate mirror
            }
        }
        return null
    }

    private fun isNewer(remoteVersionName: String, remoteVersionCode: Int): Boolean =
        isNewerAppVersion(currentVersionName, currentVersionCode, remoteVersionName, remoteVersionCode)

    suspend fun startDownload(info: UpdateInfo) {
        val job = synchronized(operationLock) {
            val token = ++generation
            downloadJob?.cancel()
            val task = scope.launch(start = CoroutineStart.LAZY) {
                publishStatus(token, UpdateStatus.Downloading(0f, 0L, info.apkSize))
                try {
                    val downloader = UpdatePackageDownloader(client, updatesDirectory,
                        packageValidator ?: { file, expected -> validateApk(file, expected) }, urlValidator)
                    val file = downloader.download(info) { bytes, total ->
                        val progress = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
                        publishStatus(token, UpdateStatus.Downloading(progress, bytes, total))
                    }
                    synchronized(operationLock) {
                        if (token == generation) {
                            downloadedInfo = info
                            _status.value = UpdateStatus.Downloaded(info, file)
                        } else file.delete()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    publishStatus(token, UpdateStatus.Error(e.message ?: "下载失败，请重试"))
                } finally {
                    synchronized(operationLock) { if (token == generation) downloadJob = null }
                }
            }
            downloadJob = task
            task.start()
            task
        }
        // The manager owns the download; leaving Settings only cancels this wait.
        job.join()
    }

    private fun publishStatus(token: Long, value: UpdateStatus) = synchronized(operationLock) {
        if (token == generation) _status.value = value
    }

    fun cancelDownload() = synchronized(operationLock) {
        generation++
        downloadJob?.cancel()
        downloadJob = null
        _status.value = UpdateStatus.Idle
    }

    fun dismissUpdate() {
        synchronized(operationLock) {
            if (downloadJob?.isActive != true) {
                generation++
                _status.value = UpdateStatus.Idle
            }
        }
    }

    internal fun validateApk(file: File, expected: UpdateInfo? = null) {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("安装包无法读取")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "安装包不属于拾音" }
        require(archive.longVersionCode > installed.longVersionCode) { "安装包版本不高于当前版本" }
        expected?.let {
            require(archive.versionName == it.versionName.trim().removePrefix("v").removePrefix("V")) { "安装包版本与发布信息不一致" }
            if (it.versionCode > 0) require(archive.longVersionCode == it.versionCode.toLong()) { "安装包内部版本号不一致" }
        }
        fun hashes(signatures: Array<android.content.pm.Signature>) = signatures.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }.toSet()
        val incoming = archive.signingInfo ?: error("安装包缺少签名")
        val local = installed.signingInfo ?: error("当前应用签名无法读取")
        val same = if (local.hasMultipleSigners() || incoming.hasMultipleSigners()) {
            hashes(local.apkContentsSigners) == hashes(incoming.apkContentsSigners)
        } else hashes(local.apkContentsSigners).all { it in hashes(incoming.signingCertificateHistory) }
        require(same) { "安装包签名与当前应用不一致" }
    }

    fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            _status.value = UpdateStatus.Error("安装包文件不存在，请重新下载")
            return
        }

        try {
            require(apkFile.canonicalFile.parentFile == updatesDirectory.canonicalFile && apkFile.name.startsWith("update-") && apkFile.extension == "apk") { "安装包路径无效" }
            validateApk(apkFile, downloadedInfo)
        } catch (e: Exception) {
            _status.value = UpdateStatus.Error(e.message ?: "安装包校验失败")
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
