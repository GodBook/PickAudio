package com.pickaudio.online

import com.google.gson.JsonParser
import com.pickaudio.data.model.SearchSongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object QqMusicSearchAdapter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 20): List<SearchSongItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=$page&n=$pageSize&w=$encoded&format=json"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Referer", "https://y.qq.com/")
            .build()

        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return@withContext emptyList()
        val root = JsonParser.parseString(body).asJsonObject

        val songList = root.getAsJsonObject("data")
            ?.getAsJsonObject("song")
            ?.getAsJsonArray("list") ?: return@withContext emptyList()

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
                    coverUrl = coverUrl
                )
            )
        }
        result
    }

    suspend fun getLyric(songmid: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&format=json&nobase64=1"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://y.qq.com/")
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return@withContext Pair("", null)
        val root = JsonParser.parseString(body).asJsonObject

        val lyric = root.get("lyric")?.asString ?: ""
        val trans = root.get("trans")?.asString
        Pair(lyric, trans)
    }
}
