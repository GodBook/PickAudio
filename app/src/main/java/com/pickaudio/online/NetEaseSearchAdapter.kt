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

object NetEaseSearchAdapter {
    private val client = NetworkPolicy.Default.client(OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build())

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = SEARCH_PAGE_SIZE): List<SearchSongItem> =
        searchPage(keyword, page, pageSize).items
    suspend fun searchPage(keyword: String, page: Int = 1, pageSize: Int = SEARCH_PAGE_SIZE): SearchPage = withContext(Dispatchers.IO) {
        require(page >= 1 && pageSize in 1..100)
        val offset = (page - 1) * pageSize
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://music.163.com/api/search/get/web?s=$encoded&type=1&offset=$offset&limit=$pageSize"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        val body = client.withResponse(req) { resp ->
            check(resp.isSuccessful) { "网易云服务返回 ${resp.code}" }
            resp.body?.readLimitedText(2 * 1024 * 1024) ?: error("搜索响应为空")
        }
        val root = JsonParser.parseString(body).asJsonObject
        check(root.get("code")?.asInt == 200) { "网易云搜索服务暂不可用" }

        val songsArray = root.getAsJsonObject("result")?.getAsJsonArray("songs") ?: return@withContext SearchPage(emptyList(), null)
        val result = mutableListOf<SearchSongItem>()

        for (elem in songsArray) {
            val s = elem.asJsonObject
            val id = s.get("id").asString
            val name = s.get("name").asString
            val artists = mutableListOf<String>()
            s.getAsJsonArray("artists")?.forEach {
                artists.add(it.asJsonObject.get("name").asString)
            }
            val artistName = if (artists.isEmpty()) "未知歌手" else artists.joinToString(", ")
            val albumObj = s.getAsJsonObject("album")
            val albumName = albumObj?.get("name")?.asString ?: "未知专辑"
            val picUrl = albumObj?.get("picUrl")?.asString
            val duration = s.get("duration")?.asLong ?: 0L

            result.add(
                SearchSongItem(
                    platform = "wy",
                    songId = id,
                    title = name,
                    artist = artistName,
                    album = albumName,
                    durationMs = duration,
                    coverUrl = picUrl,
                    metadataJson = s.toString()
                )
            )
        }
        val total = runCatching { root.getAsJsonObject("result")?.get("songCount")?.asInt }.getOrNull()
        val more = if (total != null) page.toLong() * pageSize < total else result.size >= pageSize
        SearchPage(result, if (more) page + 1 else null, total)
    }

    suspend fun getLyric(songId: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        val url = "https://music.163.com/api/song/lyric?id=${URLEncoder.encode(songId, "UTF-8")}&lv=1&kv=1&tv=-1"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val body = client.withResponse(req) { resp ->
            check(resp.isSuccessful) { "歌词服务返回 ${resp.code}" }
            resp.body?.readLimitedText(2 * 1024 * 1024) ?: error("歌词响应为空")
        }
        val root = JsonParser.parseString(body).asJsonObject
        check(root.get("code")?.asInt == 200) { "歌词服务暂不可用" }

        val lyric = root.getAsJsonObject("lrc")?.get("lyric")?.asString ?: ""
        val tlyric = root.getAsJsonObject("tlyric")?.get("lyric")?.asString
        Pair(lyric, tlyric)
    }
}
