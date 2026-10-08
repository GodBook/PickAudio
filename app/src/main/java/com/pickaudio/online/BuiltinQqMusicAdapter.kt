package com.pickaudio.online

import com.google.gson.JsonParser
import com.pickaudio.network.readLimitedText
import com.pickaudio.network.withResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Resolve the requested QQ recording, and check that the CDN actually serves audio. */
internal class BuiltinQqMusicAdapter(
    private val client: OkHttpClient,
    private val endpoint: String = "https://api.vkeys.cn/music/tencent/song/link"
) {
    suspend fun resolve(songMid: String, quality: String): String {
        require(songMid.isNotBlank()) { "QQ 歌曲缺少编号" }
        val level = when (quality) {
            "128k" -> 4
            "320k" -> 8
            "flac" -> 10
            else -> error("内置 QQ 音源不支持此音质")
        }
        val request = Request.Builder().url(endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("mid", songMid).addQueryParameter("quality", level.toString()).build())
            .header("User-Agent", "Mozilla/5.0").build()
        val url = client.withResponse(request) { response ->
            check(response.isSuccessful) { "QQ 音源服务返回 ${response.code}" }
            parseUrl(response.body?.readLimitedText(256 * 1024) ?: error("QQ 音源响应为空"), songMid)
        }
        verifyAudio(url)
        return url
    }

    internal fun parseUrl(body: String, songMid: String): String {
        val root = JsonParser.parseString(body).asJsonObject
        check(root.get("code")?.asInt == 0) { "QQ 音源暂未提供此歌曲，请稍后重试或换源" }
        val data = root.getAsJsonObject("data") ?: error("QQ 音源没有返回歌曲")
        check(data.get("songMID")?.asString == songMid) { "QQ 音源返回了其他歌曲，已停止播放" }
        val url = data.get("url")?.asString?.takeIf { it.isNotBlank() }?.toHttpUrl()
            ?: error("QQ 音源没有返回播放地址")
        check(!url.pathSegments.last().startsWith("RS", ignoreCase = true)) { "QQ 音源仅返回试听片段，请换源" }
        check(url.username.isEmpty() && url.password.isEmpty()) { "QQ 音源返回了无效地址" }
        return url.toString()
    }

    private suspend fun verifyAudio(url: String) {
        client.withResponse(Request.Builder().url(url).header("Range", "bytes=0-4095")
            .header("Accept-Encoding", "identity").header("Referer", "https://y.qq.com/")
            .header("User-Agent", "Mozilla/5.0").build()) { response ->
            check(response.isSuccessful) { "QQ 音频地址不可用（${response.code}），请重试或换源" }
            val bytes = response.body?.source()?.readByteArray(12) ?: error("QQ 音频为空")
            val magic = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            val mp3 = bytes[0] == 0xff.toByte() && (bytes[1].toInt() and 0xe0) == 0xe0
            check(magic.startsWith("ID3") || magic == "fLaC" || magic == "OggS" || mp3 ||
                bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
                "QQ 音源返回的内容不是音频，请换源"
            }
        }
    }
}
