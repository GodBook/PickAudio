package com.pickaudio.data.repository

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.pickaudio.data.db.LyricRecordEntity
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.model.Track
import com.pickaudio.online.*
import kotlinx.coroutines.*

data class LyricPayload(val original: String, val translation: String? = null)
data class LoadedLyrics(val lines: List<LyricLine>, val offsetMs: Long, val local: Boolean, val warning: String? = null)

class LyricRepository(private val context: Context, private val database: PickAudioDatabase,
    private val fetch: suspend (String, String) -> Pair<String, String?> = { platform, songId ->
        if (platform == "wy") NetEaseSearchAdapter.getLyric(songId) else QqMusicSearchAdapter.getLyric(songId)
    }) {
    private val dao = database.lyricDao()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        android.util.Log.e("Lyrics", "Unable to save lyric calibration", error)
    })
    private val offsetWrites = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private fun parse(content: String): List<LyricLine> = if (content.trimStart().startsWith("{")) {
        val payload = gson.fromJson(content, LyricPayload::class.java)
        LyricParser.parse(payload.original, payload.translation)
    } else LyricParser.parse(content)

    suspend fun load(track: Track, refresh: Boolean = false): LoadedLyrics = withContext(Dispatchers.IO) {
        val cached = dao.getLyricForTrack(track.id)
        if (cached != null && (!refresh || cached.sourceType in listOf("MANUAL_LRC", "SAME_DIR_LRC", "BACKUP_RESTORED")))
            return@withContext LoadedLyrics(parse(cached.content), cached.offsetMs, cached.sourceType != "CACHED_ONLINE")
        val platform = track.platform
        val songId = track.platformSongId
        if (platform == null || songId == null) return@withContext LoadedLyrics(emptyList(), cached?.offsetMs ?: 0, true)
        val result = try {
            fetch(platform, songId)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (cached != null) return@withContext LoadedLyrics(parse(cached.content), cached.offsetMs, false,
                "更新歌词失败，正在显示缓存")
            throw e
        }
        currentCoroutineContext().ensureActive()
        val lines = LyricParser.parse(result.first, result.second)
        if (lines.isEmpty() && cached != null) return@withContext LoadedLyrics(parse(cached.content), cached.offsetMs, false,
            "此次未返回同步歌词，正在显示缓存")
        if (lines.isNotEmpty()) dao.insertOrUpdate(LyricRecordEntity(track.id, "CACHED_ONLINE", gson.toJson(LyricPayload(result.first, result.second)), cached?.offsetMs ?: 0))
        LoadedLyrics(lines, cached?.offsetMs ?: 0, false)
    }

    suspend fun importLrc(trackId: String, uri: Uri, sourceType: String = "MANUAL_LRC") = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(2 * 1024 * 1024 + 1) } ?: error("无法读取歌词文件")
        require(bytes.size <= 2 * 1024 * 1024) { "歌词文件超过 2MB" }
        val content = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { String(bytes, charset("GB18030")) }.removePrefix("\uFEFF")
        require(LyricParser.parse(content).isNotEmpty()) { "没有读到带时间轴的歌词，请选择有效的 LRC 文件" }
        val old = dao.getLyricForTrack(trackId)
        if (sourceType == "SAME_DIR_LRC" && old?.sourceType == "MANUAL_LRC") return@withContext
        dao.insertOrUpdate(LyricRecordEntity(trackId, sourceType, content, old?.offsetMs ?: 0))
    }

    suspend fun saveOffset(trackId: String, offsetMs: Long) = withContext(Dispatchers.IO) { dao.updateOffset(trackId, offsetMs.coerceIn(-60000, 60000)) }
    fun scheduleOffset(trackId: String, offsetMs: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(200)
            saveOffset(trackId, offsetMs)
        }
        offsetWrites.put(trackId, job)?.cancel()
        job.invokeOnCompletion { offsetWrites.remove(trackId, job) }
        job.start()
    }
}
