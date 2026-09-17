package com.pickaudio.source

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.db.PlatformSourceSelectionEntity
import com.pickaudio.data.db.SourceScriptEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SourceInfo(
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val homepage: String
)

data class SourcePlatformCapability(
    val platform: String,
    val name: String,
    val actions: List<String>,
    val qualities: List<String>
)

class LxSourceManager(
    private val context: Context,
    private val database: PickAudioDatabase
) {
    private val sourceDao = database.sourceDao()
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // OkHttp client for script requests
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // Active runtime instances per source ID
    private val activeEngines = ConcurrentHashMap<String, QuickJsEngine>()
    private val sourceCapabilities = ConcurrentHashMap<String, Map<String, SourcePlatformCapability>>()

    init {
        scope.launch(Dispatchers.IO) {
            ensureBuiltinSources()
        }
    }

    fun getAllSources(): Flow<List<SourceScriptEntity>> = sourceDao.getAllSources()
    fun getPlatformSelections(): Flow<List<PlatformSourceSelectionEntity>> = sourceDao.getPlatformSelections()

    fun parseSourceHeader(content: String): SourceInfo {
        var name = "未命名音源"
        var description = ""
        var version = "1.0.0"
        var author = ""
        var homepage = ""

        val lines = content.lines().take(50) // Read top 50 lines
        for (line in lines) {
            val trimmed = line.trim().trimStart('*').trim()
            if (trimmed.startsWith("@name")) {
                name = trimmed.removePrefix("@name").trim()
            } else if (trimmed.startsWith("@description")) {
                description = trimmed.removePrefix("@description").trim()
            } else if (trimmed.startsWith("@version")) {
                version = trimmed.removePrefix("@version").trim()
            } else if (trimmed.startsWith("@author")) {
                author = trimmed.removePrefix("@author").trim()
            } else if (trimmed.startsWith("@homepage")) {
                homepage = trimmed.removePrefix("@homepage").trim()
            }
        }
        return SourceInfo(name, description, version, author, homepage)
    }

    private fun sha256(str: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(str.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun importSourceFromCode(code: String): SourceScriptEntity = withContext(Dispatchers.IO) {
        if (code.toByteArray(Charsets.UTF_8).size > 5 * 1024 * 1024) {
            throw IllegalArgumentException("脚本大小超过 5MB 限制")
        }

        val header = parseSourceHeader(code)
        val hash = sha256(code)

        val existing = sourceDao.getSourceByHash(hash)
        if (existing != null) {
            return@withContext existing
        }

        // Test init to verify validity and extract capabilities
        val capabilities = testInitialize(code)

        val entity = SourceScriptEntity(
            id = UUID.randomUUID().toString(),
            name = header.name,
            version = header.version,
            author = header.author,
            description = header.description,
            homepage = header.homepage,
            scriptHash = hash,
            scriptContent = code,
            capabilitiesJson = gson.toJson(capabilities),
            isEnabled = true
        )
        sourceDao.insertOrUpdate(entity)

        // Automatically select if first source
        if (sourceDao.getSelectionForPlatform("wy") == null && capabilities.containsKey("wy")) {
            selectSourceForPlatform("wy", entity.id)
        }
        if (sourceDao.getSelectionForPlatform("tx") == null && capabilities.containsKey("tx")) {
            selectSourceForPlatform("tx", entity.id)
        }

        entity
    }

    suspend fun importSourceFromUrl(url: String): SourceScriptEntity = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("仅支持安全的 HTTPS 脚本链接")
        }
        val req = Request.Builder().url(url).build()
        val resp = okHttpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw IOException("下载脚本失败: HTTP ${resp.code}")
        }
        val body = resp.body?.string() ?: throw IOException("脚本内容为空")
        importSourceFromCode(body)
    }

    suspend fun selectSourceForPlatform(platform: String, sourceId: String?) {
        sourceDao.setPlatformSelection(PlatformSourceSelectionEntity(platform, sourceId))
    }

    suspend fun deleteSource(sourceId: String) {
        val s = sourceDao.getSourceById(sourceId)
        if (s != null) {
            stopEngine(sourceId)
            sourceDao.delete(s)
        }
    }

    private suspend fun testInitialize(code: String): Map<String, SourcePlatformCapability> = withTimeout(15000) {
        val engine = QuickJsEngine()
        val capabilities = CompletableDeferred<Map<String, SourcePlatformCapability>>()

        val callback = object : QuickJsHostCallback {
            override fun onConsoleLog(level: String, message: String) {
                Log.d("LX_Script", "[$level] $message")
            }

            override fun onLxSend(eventName: String, dataJson: String) {
                if (eventName == "inited") {
                    try {
                        val parsed = parseInitedSources(dataJson)
                        capabilities.complete(parsed)
                    } catch (e: Exception) {
                        capabilities.completeExceptionally(e)
                    }
                }
            }

            override fun onLxRequest(reqId: Long, url: String, optionsJson: String) {
                dispatchNetworkRequest(engine, reqId, url, optionsJson)
            }
        }

        engine.registerHostBridge(callback)
        try {
            engine.evaluate(code, "source.js")
            engine.executePendingJobs()
            val result = capabilities.await()
            engine.close()
            result
        } catch (e: Exception) {
            engine.close()
            throw e
        }
    }

    private fun parseInitedSources(dataJson: String): Map<String, SourcePlatformCapability> {
        val map = mutableMapOf<String, SourcePlatformCapability>()
        val root = JsonParser.parseString(dataJson).asJsonObject
        val sources = root.getAsJsonObject("sources") ?: return emptyMap()

        for (key in sources.keySet()) {
            val srcObj = sources.getAsJsonObject(key) ?: continue
            val name = srcObj.get("name")?.asString ?: key
            val actions = mutableListOf<String>()
            srcObj.getAsJsonArray("actions")?.forEach { actions.add(it.asString) }
            val qualities = mutableListOf<String>()
            srcObj.getAsJsonArray("qualitys")?.forEach { qualities.add(it.asString) }

            map[key] = SourcePlatformCapability(
                platform = key,
                name = name,
                actions = actions,
                qualities = qualities
            )
        }
        return map
    }

    private fun getOrStartEngine(sourceId: String): QuickJsEngine {
        return activeEngines.computeIfAbsent(sourceId) {
            val s = runBlocking { sourceDao.getSourceById(sourceId) }
                ?: throw IllegalStateException("未找到音乐源 $sourceId")
            val engine = QuickJsEngine()
            val initedDeferred = CompletableDeferred<Boolean>()

            val callback = object : QuickJsHostCallback {
                override fun onConsoleLog(level: String, message: String) {
                    Log.d("LX_Script", "[$level] $message")
                }

                override fun onLxSend(eventName: String, dataJson: String) {
                    if (eventName == "inited") {
                        val parsed = parseInitedSources(dataJson)
                        sourceCapabilities[sourceId] = parsed
                        initedDeferred.complete(true)
                    }
                }

                override fun onLxRequest(reqId: Long, url: String, optionsJson: String) {
                    dispatchNetworkRequest(engine, reqId, url, optionsJson)
                }
            }

            engine.registerHostBridge(callback)
            engine.evaluate(s.scriptContent, "${s.name}.js")
            engine.executePendingJobs()
            engine
        }
    }

    private fun stopEngine(sourceId: String) {
        val engine = activeEngines.remove(sourceId)
        engine?.close()
        sourceCapabilities.remove(sourceId)
    }

    private fun dispatchNetworkRequest(engine: QuickJsEngine, reqId: Long, urlStr: String, optionsJson: String) {
        scope.launch {
            try {
                // Security check: Block private and loopback networks
                val u = URL(urlStr)
                val inet = InetAddress.getByName(u.host)
                if (inet.isLoopbackAddress || inet.isSiteLocalAddress || inet.isLinkLocalAddress) {
                    engine.resolveLxRequest(reqId, true, "禁止访问本地及局域网地址")
                    return@launch
                }

                val opt = try { JsonParser.parseString(optionsJson).asJsonObject } catch (e: Exception) { JsonObject() }
                val method = opt.get("method")?.asString?.uppercase() ?: "GET"
                val headersBuilder = Headers.Builder()
                opt.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) ->
                    headersBuilder.add(k, v.asString)
                }

                val reqBuilder = Request.Builder()
                    .url(urlStr)
                    .headers(headersBuilder.build())

                val bodyStr = opt.get("body")?.asString
                if (method == "POST" || method == "PUT") {
                    val mediaType = (opt.get("headers")?.asJsonObject?.get("Content-Type")?.asString ?: "application/json").toMediaTypeOrNull()
                    reqBuilder.method(method, (bodyStr ?: "").toRequestBody(mediaType))
                } else {
                    reqBuilder.method(method, null)
                }

                val resp = okHttpClient.newCall(reqBuilder.build()).execute()
                val respBytes = resp.body?.bytes() ?: ByteArray(0)
                if (respBytes.size > 8 * 1024 * 1024) {
                    engine.resolveLxRequest(reqId, true, "响应体大小超过 8MB 限制")
                    return@launch
                }

                val respText = String(respBytes, Charsets.UTF_8)
                val respJson = JsonObject()
                respJson.addProperty("statusCode", resp.code)
                respJson.addProperty("body", respText)

                val headersObj = JsonObject()
                for (name in resp.headers.names()) {
                    headersObj.addProperty(name, resp.headers[name])
                }
                respJson.add("headers", headersObj)

                engine.resolveLxRequest(reqId, false, respJson.toString())
            } catch (e: Exception) {
                engine.resolveLxRequest(reqId, true, e.message ?: "网络请求失败")
            }
        }
    }

    suspend fun ensureBuiltinSources() = withContext(Dispatchers.IO) {
        try {
            val builtinId = "builtin_aggregate"
            val existing = sourceDao.getSourceById(builtinId)
            if (existing == null) {
                val entity = SourceScriptEntity(
                    id = builtinId,
                    name = "拾音官方聚合音源",
                    version = "1.1.0",
                    author = "PickAudio Official",
                    description = "内置多线路高品质聚合解析服务，支持网易云与QQ音乐在线高品质试听与下载",
                    homepage = "https://github.com/GodBook/PickAudio",
                    scriptHash = "builtin_aggregate_v110",
                    scriptContent = "// PickAudio Built-in Multi-Engine Aggregator",
                    capabilitiesJson = """{"wy":{"platform":"wy","name":"网易云","actions":["musicUrl"],"qualities":["128k","320k","flac"]},"tx":{"platform":"tx","name":"QQ音乐","actions":["musicUrl"],"qualities":["128k","320k","flac"]}}""",
                    isEnabled = true
                )
                sourceDao.insertOrUpdate(entity)
            }
            val wySel = sourceDao.getSelectionForPlatform("wy")
            if (wySel?.sourceId == null) {
                selectSourceForPlatform("wy", builtinId)
            }
            val txSel = sourceDao.getSelectionForPlatform("tx")
            if (txSel?.sourceId == null) {
                selectSourceForPlatform("tx", builtinId)
            }
        } catch (e: Exception) {
            Log.e("LxSourceManager", "ensureBuiltinSources error", e)
        }
    }

    suspend fun resolveMusicUrl(
        platform: String,
        songId: String,
        quality: String = "128k",
        title: String? = null,
        artist: String? = null
    ): String = withContext(Dispatchers.IO) {
        val selection = sourceDao.getSelectionForPlatform(platform)
        val sourceId = selection?.sourceId

        // If no source is selected or source is builtin_aggregate, use builtin resolver
        if (sourceId == null || sourceId == "builtin_aggregate") {
            return@withContext resolveBuiltinMusicUrl(platform, songId, quality, title, artist)
        }

        // Try custom LX script first
        try {
            val engine = getOrStartEngine(sourceId)
            val musicInfo = JsonObject().apply {
                addProperty("songmid", songId)
                addProperty("id", songId)
            }
            val info = JsonObject().apply {
                addProperty("type", quality)
                add("musicInfo", musicInfo)
            }

            val evalJs = """
                (function() {
                    var handler = globalThis.__lx_handlers && globalThis.__lx_handlers.request;
                    if (!handler) return Promise.reject(new Error("源脚本未注册 request 处理器"));
                    return handler({
                        source: "$platform",
                        action: "musicUrl",
                        info: ${info}
                    });
                })()
            """.trimIndent()

            val resJson = engine.evaluate(evalJs, "<resolve>") ?: throw IllegalStateException("解析返回空值")
            val parsed = JsonParser.parseString(resJson)
            val url = if (parsed.isJsonPrimitive) {
                parsed.asString
            } else if (parsed.isJsonObject && parsed.asJsonObject.has("url")) {
                parsed.asJsonObject.get("url").asString
            } else {
                parsed.toString().trim('"')
            }
            if (url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                return@withContext url
            }
        } catch (e: Exception) {
            Log.w("LxSourceManager", "Custom script resolve failed for $platform, fallback to built-in: ${e.message}")
        }

        // Fallback to built-in aggregator
        resolveBuiltinMusicUrl(platform, songId, quality, title, artist)
    }

    private suspend fun resolveBuiltinMusicUrl(
        platform: String,
        songId: String,
        quality: String,
        title: String?,
        artist: String?
    ): String {
        if (platform == "wy") {
            // Priority 1: GDStudio NetEase API (High Quality 320k / 128k)
            try {
                val br = if (quality == "flac" || quality == "320k") "320" else "128"
                val req = Request.Builder()
                    .url("https://music-api.gdstudio.xyz/api.php?types=url&source=netease&id=$songId&br=$br")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
                val resp = okHttpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.has("url")) {
                        val u = json.get("url").asString
                        if (u.isNotBlank() && u.startsWith("http")) {
                            return u
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("LxSourceManager", "Builtin wy GDStudio failed: ${e.message}")
            }

            // Priority 2: Paugram API
            try {
                val req = Request.Builder()
                    .url("https://api.paugram.com/netease/?id=$songId")
                    .build()
                val resp = okHttpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.has("link")) {
                        val link = json.get("link").asString
                        if (link.isNotBlank() && link.startsWith("http")) return link
                    }
                }
            } catch (e: Exception) {
                Log.w("LxSourceManager", "Builtin wy Paugram failed: ${e.message}")
            }

            // Priority 3: NetEase standard outer URL (HTTP 302 stream)
            return "https://music.163.com/song/media/outer/url?id=$songId.mp3"
        } else if (platform == "tx") {
            // For QQ Music, cross-match NetEase database with song title and artist
            var queryTitle = title
            var queryArtist = artist
            if (queryTitle.isNullOrBlank()) {
                val dbTrack = database.trackDao().getTrackById("online_tx_$songId")
                if (dbTrack != null) {
                    queryTitle = dbTrack.title
                    queryArtist = dbTrack.artist
                }
            }

            if (!queryTitle.isNullOrBlank()) {
                try {
                    val searchKeyword = java.net.URLEncoder.encode("$queryTitle ${queryArtist ?: ""}".trim(), "UTF-8")
                    val searchUrl = "https://music.163.com/api/search/get/web?csrf_token=&s=$searchKeyword&type=1&offset=0&total=true&limit=1"
                    val searchReq = Request.Builder()
                        .url(searchUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()
                    val resp = okHttpClient.newCall(searchReq).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val root = JsonParser.parseString(body).asJsonObject
                        val songs = root.getAsJsonObject("result")?.getAsJsonArray("songs")
                        if (songs != null && songs.size() > 0) {
                            val matchedSongId = songs[0].asJsonObject.get("id").asLong.toString()
                            return resolveBuiltinMusicUrl("wy", matchedSongId, quality, queryTitle, queryArtist)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("LxSourceManager", "Builtin tx cross-match failed: ${e.message}")
                }
            }

            throw IllegalStateException("未找到该 QQ 歌曲的可用播放链接，请在音乐源管理中导入专用音源")
        }

        throw IllegalStateException("不支持的平台: $platform")
    }
}
