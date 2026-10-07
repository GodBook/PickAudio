package com.pickaudio.online

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pickaudio.network.readLimitedText
import com.pickaudio.network.withResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.random.Random

/** Ask QQ for the requested recording; an empty purl is an access failure, not a playable URL. */
internal class QqMusicPlaybackAdapter(
    private val client: OkHttpClient,
    private val endpoint: String = "https://u.y.qq.com/cgi-bin/musicu.fcg"
) {
    suspend fun resolve(songMid: String, mediaMid: String, quality: String): String = withContext(Dispatchers.IO) {
        require(songMid.isNotBlank() && mediaMid.isNotBlank()) { "QQ 歌曲缺少媒体编号" }
        val filename = when (quality) {
            "128k" -> "M500$mediaMid.mp3"
            "320k" -> "M800$mediaMid.mp3"
            else -> error("内置 QQ 路线不支持此音质")
        }
        val guid = Random.nextLong(1_000_000_000L, 10_000_000_000L).toString()
        val payload = JsonObject().apply {
            add("comm", JsonObject().apply { addProperty("uin", "0"); addProperty("format", "json"); addProperty("ct", 24); addProperty("cv", 0) })
            add("req_0", JsonObject().apply {
                addProperty("module", "vkey.GetVkeyServer"); addProperty("method", "CgiGetVkey")
                add("param", JsonObject().apply {
                    addProperty("guid", guid); addProperty("uin", "0"); addProperty("loginflag", 0); addProperty("platform", "20")
                    add("songmid", JsonArray().apply { add(songMid) })
                    add("songtype", JsonArray().apply { add(0) })
                    add("filename", JsonArray().apply { add(filename) })
                })
            })
        }
        val request = Request.Builder().url(endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("format", "json").addQueryParameter("data", payload.toString()).build())
            .header("User-Agent", "Mozilla/5.0").header("Referer", "https://y.qq.com/").build()
        val body = client.withResponse(request) { response ->
            check(response.isSuccessful) { "QQ 播放服务返回 ${response.code}" }
            response.body?.readLimitedText(256 * 1024) ?: error("QQ 播放响应为空")
        }
        parseUrl(body, songMid)
    }

    internal fun parseUrl(body: String, songMid: String): String {
        val root = JsonParser.parseString(body).asJsonObject
        check(root.get("code")?.asInt == 0) { "QQ 播放服务暂不可用" }
        val response = root.getAsJsonObject("req_0") ?: error("QQ 播放响应缺少结果")
        val data = response.getAsJsonObject("data") ?: error("QQ 未提供播放结果，请更换音源")
        check(response.get("code")?.asInt == 0 && (data.get("retcode")?.asInt ?: 0) == 0) {
            "QQ 未提供匿名播放权限，请选择支持 QQ 的自定义音源"
        }
        val item = data.getAsJsonArray("midurlinfo")?.firstOrNull {
            it.asJsonObject.get("songmid")?.asString == songMid
        }?.asJsonObject ?: error("QQ 没有返回该歌曲的地址")
        val path = item.get("purl")?.asString?.takeIf { it.isNotBlank() }
            ?: error("此 QQ 歌曲需要平台授权或当前不可用，请更换音源")
        // Never choose testfile/preview URLs or a URL belonging to another song.
        val servers = data.getAsJsonArray("sip")?.mapNotNull { element ->
            runCatching { element.asString.toHttpUrl() }.getOrNull()?.takeIf {
                it.host == "stream.qqmusic.qq.com" || it.host.endsWith(".stream.qqmusic.qq.com")
            }
        }.orEmpty()
        val server = servers.firstOrNull { it.isHttps } ?: servers.firstOrNull()
            ?: error("QQ 没有返回有效的播放服务器")
        val url = server.resolve(path) ?: error("QQ 返回了无效播放地址")
        require(url.host == server.host && url.scheme == server.scheme && url.username.isEmpty() && url.password.isEmpty()) {
            "QQ 返回了无效播放地址"
        }
        return url.toString()
    }
}
