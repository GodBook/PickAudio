package com.pickaudio.online

import com.google.gson.JsonParser
import com.pickaudio.data.model.SearchSongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object NetEaseSearchAdapter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 20): List<SearchSongItem> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * pageSize
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://music.163.com/api/search/get/web?s=$encoded&type=1&offset=$offset&limit=$pageSize"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return@withContext emptyList()
        val root = JsonParser.parseString(body).asJsonObject

        val songsArray = root.getAsJsonObject("result")?.getAsJsonArray("songs") ?: return@withContext emptyList()
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
                    coverUrl = picUrl
                )
            )
        }
        result
    }

    suspend fun getLyric(songId: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        val url = "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return@withContext Pair("", null)
        val root = JsonParser.parseString(body).asJsonObject

        val lyric = root.getAsJsonObject("lrc")?.get("lyric")?.asString ?: ""
        val tlyric = root.getAsJsonObject("tlyric")?.get("lyric")?.asString
        Pair(lyric, tlyric)
    }
}
