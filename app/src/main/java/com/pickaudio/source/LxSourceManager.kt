package com.pickaudio.source

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.db.PlatformSourceSelectionEntity
import com.pickaudio.data.db.SourceScriptEntity
import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.online.AlternativeVersionException
import com.pickaudio.online.NetEaseSearchAdapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Fast OkHttp client for quick API probing with 3s timeout
    private val quickHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // Active runtime instances per source ID
    private val activeEngines = ConcurrentHashMap<String, QuickJsEngine>()
    private val sourceCapabilities = ConcurrentHashMap<String, Map<String, SourcePlatformCapability>>()
    private val engineLocks = ConcurrentHashMap<String, Mutex>()
    private val _sourceHealth = MutableStateFlow<Map<String, String>>(emptyMap())
    val sourceHealth = _sourceHealth.asStateFlow()

    fun capabilitiesForSource(source: SourceScriptEntity): Map<String, SourcePlatformCapability> =
        runCatching {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, SourcePlatformCapability>>() {}.type
            gson.fromJson<Map<String, SourcePlatformCapability>>(source.capabilitiesJson, type) ?: emptyMap()
        }.getOrDefault(emptyMap())

    suspend fun supportedQualities(platform: String): List<String> {
        ensureBuiltinSources()
        val selected = sourceDao.getSelectionForPlatform(platform)
        val id = selected?.sourceId ?: if (selected == null) "builtin_aggregate" else return emptyList()
        val source = sourceDao.getSourceById(id) ?: return emptyList()
        return if (source.isEnabled) capabilitiesForSource(source)[platform]?.qualities.orEmpty() else emptyList()
    }

    suspend fun testSource(source: SourceScriptEntity) {
        _sourceHealth.value = _sourceHealth.value + (source.id to "正在测试")
        try {
            if (source.id == "builtin_aggregate") {
                val results = NetEaseSearchAdapter.search("晴天", pageSize = 1)
                require(results.isNotEmpty()) { "搜索服务暂时未返回结果" }
                _sourceHealth.value = _sourceHealth.value + (source.id to "搜索连接正常；歌曲可用性以播放结果为准")
            } else {
                testInitialize(source.scriptContent)
                _sourceHealth.value = _sourceHealth.value + (source.id to "脚本初始化通过；歌曲可用性以播放结果为准")
            }
        } catch (e: CancellationException) {
            _sourceHealth.value = _sourceHealth.value + (source.id to "测试已取消")
            throw e
        }
        catch (e: Exception) { _sourceHealth.value = _sourceHealth.value + (source.id to "测试失败：${e.message}") }
    }

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
        if (sourceId != null) {
            val source = sourceDao.getSourceById(sourceId) ?: error("音源不存在")
            require(capabilitiesForSource(source).containsKey(platform)) { "该音源不支持此平台" }
        }
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
            result
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("脚本初始化超过 15 秒，请检查网络或使用兼容的 LX 移动版脚本", e)
        } finally { engine.close() }
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

    private suspend fun getOrStartEngine(sourceId: String): QuickJsEngine = engineLocks.getOrPut(sourceId) { Mutex() }.withLock {
        activeEngines[sourceId]?.let { return@withLock it }
        withTimeout(15000) {
            val s = sourceDao.getSourceById(sourceId)
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
            try {
                engine.evaluate(s.scriptContent, "${s.name}.js")
                engine.executePendingJobs()
                initedDeferred.await()
                activeEngines[sourceId] = engine
                engine
            } catch (e: Exception) { engine.close(); throw e }
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
            if (existing == null || existing.version != "1.2.0") {
                val entity = SourceScriptEntity(
                    id = builtinId,
                    name = "拾音官方聚合音源",
                    version = "1.2.0",
                    author = "PickAudio Official",
                    description = "网易云多线路播放；QQ 原版本不可用时提供其他平台候选版本，需手动确认。内置线路提供标准与高品质，不宣称无损。",
                    homepage = "https://github.com/GodBook/PickAudio",
                    scriptHash = "builtin_aggregate_v110",
                    scriptContent = "// PickAudio Built-in Multi-Engine Aggregator",
                    capabilitiesJson = """{"wy":{"platform":"wy","name":"网易云","actions":["musicUrl"],"qualities":["128k","320k"]},"tx":{"platform":"tx","name":"QQ音乐候选版本","actions":["musicUrl"],"qualities":["128k","320k"]}}""",
                    isEnabled = true
                )
                sourceDao.insertOrUpdate(entity)
            }
            val wySel = sourceDao.getSelectionForPlatform("wy")
            if (wySel == null) {
                selectSourceForPlatform("wy", builtinId)
            }
            val txSel = sourceDao.getSelectionForPlatform("tx")
            if (txSel == null) {
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

        if (selection != null && sourceId == null) error("未配置音乐源，请选择支持此平台的音源后重试")
        val supported = supportedQualities(platform)
        require(quality in supported) { "当前音源不支持所选音质，请选择：${supported.joinToString()}" }
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

            val resJson = engine.evaluateAsync(evalJs, "<resolve>")
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
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("音源解析超过 15 秒，请重试或更换音源", e)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            _sourceHealth.value = _sourceHealth.value + (sourceId to "最近播放解析失败：${e.message}")
            throw IllegalStateException("音乐源解析失败，请重试或切换音源：${e.message}", e)
        }
        error("音源没有返回有效地址，请切换音源")
    }

    private suspend fun resolveBuiltinMusicUrl(
        platform: String,
        songId: String,
        quality: String,
        title: String?,
        artist: String?
    ): String {
        if (platform == "wy") {
            // Priority 1: GDStudio NetEase API (High Quality 320k / 128k) with quick timeout
            try {
                require(!quality.startsWith("flac")) { "内置音源不提供已验证的无损音质，请选择支持 FLAC 的自定义源" }
                val br = if (quality == "320k") "320" else "128"
                val req = Request.Builder()
                    .url("https://music-api.gdstudio.xyz/api.php?types=url&source=netease&id=$songId&br=$br")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
                val resp = quickHttpClient.newCall(req).execute()
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
                Log.w("LxSourceManager", "Builtin wy GDStudio failed/timeout: ${e.message}")
            }

            // Priority 2: Paugram API with quick timeout
            try {
                val req = Request.Builder()
                    .url("https://api.paugram.com/netease/?id=$songId")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
                val resp = quickHttpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.has("link")) {
                        val link = json.get("link").asString
                        if (link.isNotBlank() && link.startsWith("http")) return link
                    }
                }
            } catch (e: Exception) {
                Log.w("LxSourceManager", "Builtin wy Paugram failed/timeout: ${e.message}")
            }

            // Priority 3: NetEase standard outer URL (HTTP 302 stream - rock solid with cross-protocol redirect)
            return "https://music.163.com/song/media/outer/url?id=$songId.mp3"
        } else if (platform == "tx") {
            // Cross-platform recordings are candidates, never silently substituted.
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
                val candidates = NetEaseSearchAdapter.search("$queryTitle ${queryArtist.orEmpty()}".trim(), pageSize = 10)
                if (candidates.isNotEmpty()) throw AlternativeVersionException(candidates)
            }

            throw IllegalStateException("未找到该 QQ 歌曲的可用播放链接，请在音乐源管理中导入专用音源")
        }

        throw IllegalStateException("不支持的平台: $platform")
    }
}
