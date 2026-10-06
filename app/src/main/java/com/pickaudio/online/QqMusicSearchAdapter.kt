package com.pickaudio.online

import com.google.gson.JsonParser
import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.network.NetworkPolicy
import com.pickaudio.network.readLimitedText
import com.pickaudio.network.withResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object QqMusicSearchAdapter {
    private val client = NetworkPolicy.Default.client(OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build())

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = SEARCH_PAGE_SIZE): List<SearchSongItem> =
        searchPage(keyword, page, pageSize).items
    suspend fun searchPage(keyword: String, page: Int = 1, pageSize: Int = SEARCH_PAGE_SIZE): SearchPage = withContext(Dispatchers.IO) {
        require(page >= 1 && pageSize in 1..100)
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=$page&n=$pageSize&w=$encoded&format=json"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Referer", "https://y.qq.com/")
            .build()

        val body = client.withResponse(req) { resp ->
            check(resp.isSuccessful) { "QQ 音乐服务返回 ${resp.code}" }
            resp.body?.readLimitedText(2 * 1024 * 1024) ?: error("搜索响应为空")
        }
        val root = JsonParser.parseString(body).asJsonObject
        check(root.get("code")?.asInt == 0) { "QQ 音乐搜索服务暂不可用" }

        val songList = root.getAsJsonObject("data")
            ?.getAsJsonObject("song")
            ?.getAsJsonArray("list") ?: return@withContext SearchPage(emptyList(), null)

        val result = mutableListOf<SearchSongItem>()
        for (elem in songList) {
            val s = elem.asJsonObject
            val songmid = s.get("songmid")?.asString ?: continue
            val songname = s.get("songname")?.asString ?: "未知歌曲"
            val singers = mutableListOf<String>()
            s.getAsJsonArray("singer")?.forEach {
                singers.add(it.asJsonObject.get("name").asString)
            }
            val singerName = if (singers.isEmpty()) "未知歌手" else singers.joinToString(", ")
            val albumname = s.get("albumname")?.asString ?: "未知专辑"
            val albummid = s.get("albummid")?.asString
            val durationSec = s.get("interval")?.asLong ?: 0L

            val coverUrl = if (!albummid.isNullOrEmpty()) {
                "https://y.qq.com/music/photo_new/T002R300x300M000${albummid}.jpg"
            } else null

            result.add(
                SearchSongItem(
                    platform = "tx",
                    songId = songmid,
                    title = songname,
                    artist = singerName,
                    album = albumname,
                    durationMs = durationSec * 1000L,
                    coverUrl = coverUrl,
                    metadataJson = s.toString()
                )
            )
        }
        val total = runCatching { root.getAsJsonObject("data")?.getAsJsonObject("song")?.get("totalnum")?.asInt }.getOrNull()
        val more = if (total != null) page.toLong() * pageSize < total else result.size >= pageSize
        SearchPage(result, if (more) page + 1 else null, total)
    }

    suspend fun getLyric(songmid: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${URLEncoder.encode(songmid, "UTF-8")}&format=json&nobase64=1"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://y.qq.com/")
            .build()
        val body = client.withResponse(req) { resp ->
            check(resp.isSuccessful) { "歌词服务返回 ${resp.code}" }
            resp.body?.readLimitedText(2 * 1024 * 1024) ?: error("歌词响应为空")
        }
        val root = JsonParser.parseString(body).asJsonObject
        check(root.get("code")?.asInt == 0) { "歌词服务暂不可用" }

        val lyric = root.get("lyric")?.asString ?: ""
        val trans = root.get("trans")?.asString
        Pair(lyric, trans)
    }
}
