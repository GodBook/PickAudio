package com.pickaudio.source

import android.content.Context
import android.net.Network
import android.util.Log
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.db.PlatformSourceSelectionEntity
import com.pickaudio.data.db.SourceScriptEntity
import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.online.AlternativeVersionException
import com.pickaudio.online.BuiltinQqMusicAdapter
import com.pickaudio.online.NetEaseSearchAdapter
import com.pickaudio.online.QqMusicPlaybackAdapter
import com.pickaudio.online.QqMusicSearchAdapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import com.pickaudio.network.NetworkPolicy
import com.pickaudio.network.readLimitedText
import com.pickaudio.network.withResponse
import java.io.Closeable
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
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
    private val database: PickAudioDatabase,
    private val networkPolicy: NetworkPolicy = NetworkPolicy.Default
) : Closeable {
    companion object {
        const val BUILTIN_STELLARWAVE_ID = "builtin_stellarwave"
        const val BUILTIN_QQ_ID = "builtin_qq"
        const val STELLARWAVE_ASSET = "sources/stellarwave-v3.2.0.js"
        fun defaultSourceId(platform: String) = if (platform == "tx") BUILTIN_QQ_ID else BUILTIN_STELLARWAVE_ID
        fun isBuiltinSource(sourceId: String) = sourceId in setOf("builtin_aggregate", BUILTIN_STELLARWAVE_ID, BUILTIN_QQ_ID)
    }

    private val sourceDao = database.sourceDao()
    private val gson = Gson()
    private val builtinMutex = Mutex()
    private val stellarWaveCode by lazy { context.assets.open(STELLARWAVE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() } }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        Log.e("LxSourceManager", "Source background operation failed", e)
    })

    // OkHttp client for script requests
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // Fast OkHttp client for quick API probing with 3s timeout
    private val quickHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    // A runtime belongs to one operation; finishing, cancelling or deleting its source closes it.
    private class Session(val sourceId: String?, val engine: QuickJsEngine, val job: CompletableJob,
        val client: OkHttpClient, val pending: AtomicInteger = AtomicInteger())
    private val activeSessions = ConcurrentHashMap<QuickJsEngine, Session>()
    private val engineSlots = Semaphore(4)
    private val networkSlots = Semaphore(8)

    fun audioClient(base: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build(), network: Network? = null): OkHttpClient {
        val routed = if (network == null) base else base.newBuilder().socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            }).build()
        return networkPolicy.client(routed).newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val qqStream = request.url.host == "stream.qqmusic.qq.com" || request.url.host.endsWith(".stream.qqmusic.qq.com")
            val builder = request.newBuilder()
            if (qqStream) {
                if (request.header("Referer") == null) builder.header("Referer", "https://y.qq.com/")
                if (request.header("User-Agent") == null) builder.header("User-Agent", "Mozilla/5.0")
            }
            chain.proceed(builder.build())
        }.build()
    }
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
        val id = selected?.sourceId ?: if (selected == null) defaultSourceId(platform) else return emptyList()
        val source = sourceDao.getSourceById(id) ?: return emptyList()
        return if (source.isEnabled) capabilitiesForSource(source)[platform]?.qualities.orEmpty() else emptyList()
    }

    suspend fun testSource(source: SourceScriptEntity) {
        _sourceHealth.value = _sourceHealth.value + (source.id to "正在测试")
        try {
            if (source.id == BUILTIN_QQ_ID) {
                BuiltinQqMusicAdapter(audioClient(okHttpClient)).resolve("002NkERn2LNVI4", "128k")
                _sourceHealth.value = _sourceHealth.value + (source.id to "QQ 原曲音频连接正常；歌曲可用性以播放结果为准")
            } else if (source.id == "builtin_aggregate") {
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

    private val sourcesFlow = sourceDao.getAllSources()
    private val selectionsFlow = sourceDao.getPlatformSelections()
    fun getAllSources(): Flow<List<SourceScriptEntity>> = sourcesFlow
    fun getPlatformSelections(): Flow<List<PlatformSourceSelectionEntity>> = selectionsFlow

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
                version = trimmed.removePrefix("@version").trim().removePrefix("v")
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
        if (capabilities.containsKey("wy")) {
            sourceDao.insertDefaultPlatformSelection(PlatformSourceSelectionEntity("wy", entity.id))
        }
        if (capabilities.containsKey("tx")) {
            sourceDao.insertDefaultPlatformSelection(PlatformSourceSelectionEntity("tx", entity.id))
        }

        entity
    }

    suspend fun importSourceFromUrl(url: String): SourceScriptEntity = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("仅支持安全的 HTTPS 脚本链接")
        }
        val req = Request.Builder().url(networkPolicy.validate(url)).build()
        val body = networkPolicy.client(okHttpClient, httpsOnly = true).withResponse(req) { resp ->
            if (!resp.isSuccessful) throw IOException("下载脚本失败: HTTP ${resp.code}")
            resp.body?.readLimitedText(5 * 1024 * 1024) ?: throw IOException("脚本内容为空")
        }
        importSourceFromCode(body)
    }

    suspend fun selectSourceForPlatform(platform: String, sourceId: String?) {
        ensureBuiltinSources()
        if (sourceId != null) {
            val source = sourceDao.getSourceById(sourceId) ?: error("音源不存在")
            require(capabilitiesForSource(source).containsKey(platform)) { "该音源不支持此平台" }
        }
        sourceDao.setPlatformSelection(PlatformSourceSelectionEntity(platform, sourceId))
    }

    suspend fun deleteSource(sourceId: String) {
        require(!isBuiltinSource(sourceId)) { "内置音源不可删除，可为平台选择其他音源或停用" }
        val s = sourceDao.getSourceById(sourceId)
        if (s != null) {
            stopEngine(sourceId)
            sourceDao.delete(s)
        }
    }

    private suspend fun testInitialize(code: String): Map<String, SourcePlatformCapability> = withTimeout(15000) {
        withEngine(code, null, null) { _, capabilities -> capabilities }
    }

    private suspend fun <T> withEngine(code: String, sourceId: String?, network: Network?,
        use: suspend (QuickJsEngine, Map<String, SourcePlatformCapability>) -> T): T = engineSlots.withPermit {
        coroutineScope {
            check(scope.isActive) { "音乐源管理器已关闭" }
            val engine = QuickJsEngine()
            val session = Session(sourceId, engine, SupervisorJob(currentCoroutineContext().job), audioClient(okHttpClient, network))
            activeSessions[engine] = session
            val initialized = CompletableDeferred<Map<String, SourcePlatformCapability>>(session.job)
            try {
                engine.registerHostBridge(object : QuickJsHostCallback {
                    override fun onConsoleLog(level: String, message: String) { Log.d("LX_Script", "[$level] $message") }
                    override fun onLxSend(eventName: String, dataJson: String) {
                        if (eventName == "inited") {
                            try {
                                val root = JsonParser.parseString(dataJson).asJsonObject
                                require(root.get("status")?.asBoolean != false) { root.get("message")?.asString ?: "音源初始化失败" }
                                initialized.complete(parseInitedSources(dataJson))
                            } catch (e: Exception) { initialized.completeExceptionally(e) }
                        }
                    }
                    override fun onLxRequest(reqId: Long, url: String, optionsJson: String) {
                        check(session.pending.incrementAndGet() <= 32) { "音源同时请求过多" }
                        dispatchNetworkRequest(session, reqId, url, optionsJson)
                    }
                })
                engine.evaluate("globalThis.lx.currentScriptInfo = ${gson.toJson(mapOf("rawScript" to code))}; undefined;", "<script-info>")
                engine.evaluate(code, "source.js")
                use(engine, initialized.await())
            } finally {
                activeSessions.remove(engine)
                session.job.cancel()
                engine.close()
            }
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

    private fun stopEngine(sourceId: String) {
        activeSessions.values.filter { it.sourceId == sourceId }.forEach {
            it.job.cancel()
            it.engine.close()
        }
    }

    override fun close() {
        scope.cancel()
        activeSessions.values.forEach { it.job.cancel(); it.engine.close() }
        activeSessions.clear()
    }

    private fun dispatchNetworkRequest(session: Session, reqId: Long, urlStr: String, optionsJson: String) {
        CoroutineScope(Dispatchers.IO + session.job).launch {
            try {
                val result = networkSlots.withPermit {
                    networkPolicy.validate(urlStr)
                    val opt = JsonParser.parseString(optionsJson).asJsonObject
                    val method = opt.get("method")?.asString?.uppercase() ?: "GET"
                    require(method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) { "不支持的请求方式" }
                    val headers = Headers.Builder()
                    opt.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) ->
                        require(k.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding")) { "不支持的请求头" }
                        headers.add(k, v.asString)
                    }
                    val req = Request.Builder().url(urlStr).headers(headers.build())
                    val form = opt.get("form")?.takeUnless { it.isJsonNull }?.asJsonObject
                    val rawBody = opt.get("body")?.takeUnless { it.isJsonNull }
                    val body = if (rawBody?.isJsonPrimitive == true) rawBody.asString else rawBody?.toString().orEmpty()
                    val requestBody = if (form != null) {
                        FormBody.Builder().apply { form.entrySet().forEach { (key, value) ->
                            add(key, if (value.isJsonPrimitive) value.asString else value.toString())
                        } }.build().also { req.header("Content-Type", "application/x-www-form-urlencoded") }
                    } else body.toRequestBody((headers.get("Content-Type") ?: "application/json").toMediaTypeOrNull())
                    require(requestBody.contentLength() <= 1024 * 1024) { "请求内容超过 1MB 限制" }
                    req.method(method, if (method in setOf("POST", "PUT", "PATCH")) requestBody else null)
                    val timeout = (opt.get("timeout")?.asLong ?: 10000L).coerceIn(1000L, 15000L)
                    session.client.newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS)
                        .readTimeout(timeout, TimeUnit.MILLISECONDS).build().withResponse(req.build()) { resp ->
                        JsonObject().apply {
                            addProperty("statusCode", resp.code)
                            addProperty("body", resp.body?.readLimitedText(8 * 1024 * 1024).orEmpty())
                            add("headers", JsonObject().apply { resp.headers.names().forEach { addProperty(it, resp.header(it)) } })
                        }.toString()
                    }
                }
                currentCoroutineContext().ensureActive()
                session.engine.resolveLxRequest(reqId, false, result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (session.job.isActive) session.engine.resolveLxRequest(reqId, true, e.message ?: "网络请求失败")
            } finally { session.pending.decrementAndGet() }
        }
    }

    suspend fun ensureBuiltinSources() = withContext(Dispatchers.IO) { builtinMutex.withLock {
        try {
            val builtinId = "builtin_aggregate"
            val existing = sourceDao.getSourceById(builtinId)
            if (existing == null || existing.version != "1.3.2") {
                val entity = SourceScriptEntity(
                    id = builtinId,
                    name = "拾音官方聚合音源",
                    version = "1.3.2",
                    author = "PickAudio Official",
                    description = "网易云多线路播放；QQ 优先请求原曲，平台拒绝匿名播放时可更换音源或确认其他平台候选。内置线路提供标准与高品质，不宣称无损。",
                    homepage = "https://github.com/GodBook/PickAudio",
                    scriptHash = "builtin_aggregate_v132",
                    scriptContent = "// PickAudio Built-in Multi-Engine Aggregator",
                    capabilitiesJson = """{"wy":{"platform":"wy","name":"网易云","actions":["musicUrl"],"qualities":["128k","320k"]},"tx":{"platform":"tx","name":"QQ音乐","actions":["musicUrl"],"qualities":["128k","320k"]}}""",
                    isEnabled = true
                )
                sourceDao.insertOrUpdate(entity)
            }
            val code = stellarWaveCode
            val hash = sha256(code)
            val currentStellarWave = sourceDao.getSourceById(BUILTIN_STELLARWAVE_ID)
            val stellarWave = if (currentStellarWave?.scriptHash != hash) {
                val header = parseSourceHeader(code)
                SourceScriptEntity(id = BUILTIN_STELLARWAVE_ID, name = header.name, version = header.version,
                    author = header.author, description = header.description, homepage = header.homepage,
                    scriptHash = hash, scriptContent = code, capabilitiesJson = gson.toJson(testInitialize(code)),
                    isEnabled = currentStellarWave?.isEnabled ?: true,
                    createdAt = currentStellarWave?.createdAt ?: System.currentTimeMillis())
            } else requireNotNull(currentStellarWave)
            database.withTransaction {
                val firstInstall = sourceDao.getSourceById(BUILTIN_STELLARWAVE_ID) == null
                if (currentStellarWave?.scriptHash != hash) sourceDao.insertOrUpdate(stellarWave)
                if (firstInstall) sourceDao.replacePlatformSelection("wy", builtinId, BUILTIN_STELLARWAVE_ID)
                sourceDao.insertDefaultPlatformSelection(PlatformSourceSelectionEntity("wy", BUILTIN_STELLARWAVE_ID))
                val firstQqInstall = sourceDao.getSourceById(BUILTIN_QQ_ID) == null
                if (firstQqInstall) {
                    sourceDao.insertOrUpdate(SourceScriptEntity(
                        id = BUILTIN_QQ_ID, name = "拾音 QQ 直连", version = "1.0.0", author = "PickAudio",
                        description = "按 QQ 原曲编号解析完整音频，支持标准、高品质和 FLAC；校验歌曲身份和音频内容，拒绝试听片段。",
                        homepage = "https://github.com/GodBook/PickAudio", scriptHash = "builtin_qq_v1",
                        scriptContent = "// PickAudio native QQ source",
                        capabilitiesJson = """{"tx":{"platform":"tx","name":"QQ音乐","actions":["musicUrl"],"qualities":["128k","320k","flac"]}}"""))
                    val previous = sourceDao.getSelectionForPlatform("tx")?.sourceId
                    if (previous in setOf(builtinId, BUILTIN_STELLARWAVE_ID) && sourceDao.getSourceById(previous!!)?.isEnabled == true) {
                        sourceDao.replacePlatformSelection("tx", previous, BUILTIN_QQ_ID)
                    }
                }
                sourceDao.insertDefaultPlatformSelection(PlatformSourceSelectionEntity("tx", BUILTIN_QQ_ID))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e("LxSourceManager", "ensureBuiltinSources error", e)
        }
    } }

    suspend fun resolveMusicUrl(
        platform: String, songId: String, quality: String = "128k", title: String? = null,
        artist: String? = null, network: Network? = null
    ): String = resolveMusicResource(platform, songId, quality, title, artist, network).url

    data class MusicResource(val url: String, val sourceIdentity: String)
    suspend fun resolveMusicResource(
        platform: String, songId: String, quality: String = "128k", title: String? = null,
        artist: String? = null, network: Network? = null
    ): MusicResource = withContext(Dispatchers.IO) {
        try {
            withTimeout(15000) {
                ensureBuiltinSources()
                val selection = sourceDao.getSelectionForPlatform(platform)
                val sourceId = selection?.sourceId ?: if (selection == null) defaultSourceId(platform) else null
                if (selection != null && sourceId == null) error("未配置音乐源，请选择支持此平台的音源后重试")
                val supported = supportedQualities(platform)
                require(quality in supported) { "当前音源不支持所选音质，请选择：${supported.joinToString()}" }
                val metadata = if (sourceId == BUILTIN_QQ_ID) "{}" else platformMetadata(platform, songId, network)
                var sourceIdentity = "builtin_aggregate:v132"
                val url = if (sourceId == null || sourceId == "builtin_aggregate") {
                    resolveBuiltinMusicUrl(platform, songId, quality, title, artist, network, metadata)
                } else if (sourceId == BUILTIN_QQ_ID) {
                    sourceIdentity = "$BUILTIN_QQ_ID:v1"
                    BuiltinQqMusicAdapter(audioClient(okHttpClient, network)).resolve(songId, quality)
                } else {
                    val source = sourceDao.getSourceById(sourceId) ?: error("音源已移除")
                    sourceIdentity = "$sourceId:${source.scriptHash}"
                    withEngine(source.scriptContent, sourceId, network) { engine, _ ->
                        val musicInfo = lxMusicInfo(platform, songId, metadata, title, artist)
                        val request = JsonObject().apply {
                            addProperty("source", platform); addProperty("action", "musicUrl")
                            add("info", JsonObject().apply { addProperty("type", quality); add("musicInfo", musicInfo) })
                        }
                        val expression = """
                            (function() {
                                var handler = globalThis.__lx_handlers && globalThis.__lx_handlers.request;
                                if (!handler) throw new Error("源脚本未注册 request 处理器");
                                return handler($request);
                            })()
                        """.trimIndent()
                        val parsed = JsonParser.parseString(engine.evaluateAsync(expression, "<resolve>"))
                        when {
                            parsed.isJsonPrimitive -> parsed.asString
                            parsed.isJsonObject && parsed.asJsonObject.has("url") -> parsed.asJsonObject.get("url").asString
                            else -> error("音源没有返回有效地址，请切换音源")
                        }
                    }
                }
                MusicResource(networkPolicy.validate(url).toString(), sourceIdentity)
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("音源解析超过 15 秒，请重试或更换音源", e)
        } catch (e: CancellationException) { throw e }
        catch (e: AlternativeVersionException) { throw e }
        catch (e: Exception) {
            // Aggregate scripts can report every backend's failure. Keep the complete cause in
            // diagnostics without pushing the player's controls below a wall of backend details.
            val reason = e.message?.lineSequence()?.firstOrNull()?.take(160).orEmpty()
            throw IllegalStateException("音乐源解析失败${if (reason.isBlank()) "" else "：$reason"}，请重试或更换音源", e)
        }
    }

    private suspend fun platformMetadata(platform: String, songId: String, network: Network?): String {
        val ref = database.onlineRefDao().getByPlatformId(platform, songId)
        val stored = ref?.platformMetadataJson ?: "{}"
        if (platform != "tx") return stored
        val info = lxMusicInfo(platform, songId, stored, null, null)
        val raw = runCatching { JsonParser.parseString(stored).asJsonObject }.getOrElse { JsonObject() }
        val file = raw.get("file")?.takeIf { it.isJsonObject }?.asJsonObject
        val hasMedia = listOf(raw.get("strMediaMid"), raw.get("media_mid"), file?.get("media_mid")).any {
            it?.takeIf { value -> value.isJsonPrimitive }?.asString?.isNotBlank() == true
        }
        val numericId = runCatching { info.get("songId")?.asLong }.getOrNull()
        if (hasMedia && numericId != null && numericId > 0) return stored
        return try {
            val fresh = QqMusicSearchAdapter.getSongMetadata(songId, audioClient(quickHttpClient, network))
            if (ref != null) database.onlineRefDao().insertOrUpdate(ref.copy(platformMetadataJson = fresh))
            fresh
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { stored }
    }

    private suspend fun resolveBuiltinMusicUrl(
        platform: String,
        songId: String,
        quality: String,
        title: String?,
        artist: String?,
        network: Network?,
        platformMetadata: String
    ): String {
        val quickClient = audioClient(quickHttpClient, network)
        val encodedId = URLEncoder.encode(songId, "UTF-8")
        if (platform == "wy") {
            // Priority 1: GDStudio NetEase API (High Quality 320k / 128k) with quick timeout
            try {
                require(!quality.startsWith("flac")) { "内置音源不提供已验证的无损音质，请选择支持 FLAC 的自定义源" }
                val br = if (quality == "320k") "320" else "128"
                val req = Request.Builder()
                    .url("https://music-api.gdstudio.xyz/api.php?types=url&source=netease&id=$encodedId&br=$br")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
                val body = quickClient.withResponse(req) { resp ->
                    check(resp.isSuccessful) { "音源服务返回 ${resp.code}" }
                    resp.body?.readLimitedText(256 * 1024).orEmpty()
                }
                if (body.isNotBlank()) {
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.has("url")) {
                        val u = json.get("url").asString
                        if (u.isNotBlank() && u.startsWith("http")) {
                            return u
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("LxSourceManager", "Builtin wy GDStudio failed/timeout: ${e.message}")
            }

            // Priority 2: Paugram API with quick timeout
            try {
                val req = Request.Builder()
                    .url("https://api.paugram.com/netease/?id=$encodedId")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
                val body = quickClient.withResponse(req) { resp ->
                    check(resp.isSuccessful) { "音源服务返回 ${resp.code}" }
                    resp.body?.readLimitedText(256 * 1024).orEmpty()
                }
                if (body.isNotBlank()) {
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.has("link")) {
                        val link = json.get("link").asString
                        if (link.isNotBlank() && link.startsWith("http")) return link
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("LxSourceManager", "Builtin wy Paugram failed/timeout: ${e.message}")
            }

            // Priority 3: NetEase standard outer URL (HTTP 302 stream - rock solid with cross-protocol redirect)
            return "https://music.163.com/song/media/outer/url?id=$encodedId.mp3"
        } else if (platform == "tx") {
            val ref = database.onlineRefDao().getByPlatformId(platform, songId)
            val metadata = lxMusicInfo(platform, songId, platformMetadata, title, artist)
            val originalFailure = try {
                return QqMusicPlaybackAdapter(quickClient).resolve(songId, metadata.get("strMediaMid").asString, quality)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { e }
            // Cross-platform recordings are candidates, never silently substituted.
            var queryTitle = title
            var queryArtist = artist
            if (queryTitle.isNullOrBlank()) {
                val dbTrack = database.trackDao().getTrackById(ref?.trackId ?: "online_tx_$songId")
                if (dbTrack != null) {
                    queryTitle = dbTrack.title
                    queryArtist = dbTrack.artist
                }
            }

            if (!queryTitle.isNullOrBlank()) {
                try {
                    val candidates = NetEaseSearchAdapter.search("$queryTitle ${queryArtist.orEmpty()}".trim(), pageSize = 10)
                    if (candidates.isNotEmpty()) throw AlternativeVersionException(candidates, originalFailure.message)
                } catch (e: CancellationException) { throw e }
                catch (e: AlternativeVersionException) { throw e }
                catch (_: Exception) { /* Preserve the original QQ error when candidate search also fails. */ }
            }

            throw IllegalStateException(originalFailure.message ?: "QQ 原曲暂不可用，请选择支持 QQ 的音乐源", originalFailure)
        }

        throw IllegalStateException("不支持的平台: $platform")
    }
}
