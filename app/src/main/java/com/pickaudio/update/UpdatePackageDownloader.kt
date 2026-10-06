package com.pickaudio.update

import com.pickaudio.network.withResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

internal class UpdatePackageDownloader(
    private val client: OkHttpClient,
    private val directory: File,
    private val validatePackage: (File, UpdateInfo) -> Unit,
    private val validateUrl: (String) -> Unit = ::requireOfficialUpdateUrl
) {
    suspend fun download(info: UpdateInfo, progress: (Long, Long) -> Unit): File {
        validateUrl(info.downloadUrl)
        require(info.apkSize in 0..MAX_APK_BYTES) { "安装包大小无效" }
        require(info.sha256 == null || info.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "安装包校验信息无效" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建更新目录" }
        // Remote names never become filesystem paths.
        val token = UUID.randomUUID().toString()
        val partial = File(directory, "update-$token.part")
        val completed = File(directory, "update-$token.apk")
        try {
            val request = Request.Builder().url(info.downloadUrl).header("User-Agent", "PickAudio-App").build()
            client.withResponse(request) { response ->
                check(response.isSuccessful) { "下载失败: HTTP ${response.code}" }
                val body = response.body ?: error("下载响应为空")
                val length = body.contentLength()
                require(length <= MAX_APK_BYTES) { "安装包超过允许大小" }
                if (info.apkSize > 0 && length >= 0) require(length == info.apkSize) { "安装包大小与发布信息不一致" }
                val total = if (info.apkSize > 0) info.apkSize else length.coerceAtLeast(0)
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                var reported = 0L
                progress(0, total)
                FileOutputStream(partial).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            require(bytes <= MAX_APK_BYTES) { "安装包超过允许大小" }
                            if (info.apkSize > 0) require(bytes <= info.apkSize) { "安装包大小与发布信息不一致" }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            if (bytes - reported >= 128 * 1024 || reported == 0L) {
                                progress(bytes, total)
                                reported = bytes
                            }
                        }
                    }
                    output.fd.sync()
                }
                require(bytes > 0 && (total <= 0 || bytes == total)) { "安装包下载不完整，请重试" }
                info.sha256?.let { expected ->
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    require(actual.equals(expected, ignoreCase = true)) { "安装包完整性校验失败，请重新下载" }
                }
                val header = partial.inputStream().use { it.readNBytes(4) }
                require(header.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) { "响应不是有效安装包" }
                currentCoroutineContext().ensureActive()
                validatePackage(partial, info)
                currentCoroutineContext().ensureActive()
                check(partial.renameTo(completed)) { "无法保存安装包" }
                progress(bytes, total)
            }
            return completed
        } catch (failure: Throwable) {
            partial.delete()
            completed.delete()
            throw failure
        }
    }

    companion object { const val MAX_APK_BYTES = 256 * 1024 * 1024L }
}

internal fun requireOfficialUpdateUrl(value: String) {
    val url = value.toHttpUrl()
    require(url.isHttps && url.host == "github.com" &&
        url.encodedPath.startsWith("/GodBook/PickAudio/releases/download/") &&
        url.encodedPath.endsWith(".apk") && url.username.isEmpty() && url.password.isEmpty()) {
        "安装包地址不是拾音官方发布地址"
    }
}
