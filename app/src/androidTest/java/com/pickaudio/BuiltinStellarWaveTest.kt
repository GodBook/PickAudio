package com.pickaudio

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.db.PlatformSourceSelectionEntity
import com.pickaudio.data.db.SourceScriptEntity
import com.pickaudio.network.NetworkPolicy
import com.pickaudio.source.LxSourceManager
import com.pickaudio.source.QuickJsEngine
import com.pickaudio.source.QuickJsHostCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class BuiltinStellarWaveTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
    private fun aggregate() = SourceScriptEntity("builtin_aggregate", "原默认", version = "1.3.2",
        scriptHash = "builtin_aggregate_v132", scriptContent = "", capabilitiesJson = "{}")

    @Test fun freshInstallIncludesExactScriptAndSelectsItWithoutNetwork() = runBlocking {
        val db = database()
        try {
            LxSourceManager(context, db).use { manager ->
                manager.ensureBuiltinSources()
                val source = db.sourceDao().getSourceById(LxSourceManager.BUILTIN_STELLARWAVE_ID)!!
                val original = context.assets.open(LxSourceManager.STELLARWAVE_ASSET).use { it.readBytes() }
                assertArrayEquals(original, source.scriptContent.toByteArray(Charsets.UTF_8))
                assertEquals("3.2.0", source.version)
                assertEquals(MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }, source.scriptHash)
                assertEquals(setOf("tx", "wy", "kw", "kg", "mg"), manager.capabilitiesForSource(source).keys)
                for (platform in listOf("wy", "tx")) {
                    assertEquals(source.id, db.sourceDao().getSelectionForPlatform(platform)?.sourceId)
                    assertEquals(listOf("128k", "320k", "flac"), manager.supportedQualities(platform))
                }
                assertEquals(source.id, manager.importSourceFromCode(source.scriptContent).id)
                manager.testSource(source)
                assertTrue(manager.sourceHealth.value[source.id]!!.contains("初始化通过"))
                assertTrue(runCatching { manager.deleteSource(source.id) }.isFailure)
                assertNotNull(db.sourceDao().getSourceById(source.id))
            }
        } finally { db.close() }
    }

    @Test fun upgradeReplacesOldDefaultsOnceAndKeepsLaterFallbackChoice() = runBlocking {
        val db = database()
        try {
            db.sourceDao().insertOrUpdate(aggregate())
            for (platform in listOf("wy", "tx")) db.sourceDao().setPlatformSelection(PlatformSourceSelectionEntity(platform, "builtin_aggregate"))
            LxSourceManager(context, db).use { manager ->
                manager.ensureBuiltinSources()
                for (platform in listOf("wy", "tx")) assertEquals(LxSourceManager.BUILTIN_STELLARWAVE_ID, db.sourceDao().getSelectionForPlatform(platform)?.sourceId)
                db.sourceDao().setPlatformSelection(PlatformSourceSelectionEntity("wy", "builtin_aggregate"))
            }
            LxSourceManager(context, db).use { it.ensureBuiltinSources() }
            assertEquals("builtin_aggregate", db.sourceDao().getSelectionForPlatform("wy")?.sourceId)
            assertEquals(2, db.sourceDao().getAllSources().first().size)
        } finally { db.close() }
    }

    @Test fun concurrentInitializationPreservesCustomBindingAndExplicitDisable() = runBlocking {
        val db = database()
        try {
            db.sourceDao().insertOrUpdate(aggregate())
            val custom = SourceScriptEntity("custom_existing", "已有自定义", scriptHash = "existing_hash",
                scriptContent = "unchanged", capabilitiesJson = "{}")
            db.sourceDao().insertOrUpdate(custom)
            db.sourceDao().setPlatformSelection(PlatformSourceSelectionEntity("wy", custom.id))
            db.sourceDao().setPlatformSelection(PlatformSourceSelectionEntity("tx", null))
            LxSourceManager(context, db).use { first ->
                LxSourceManager(context, db).use { second ->
                    awaitAll(async { first.ensureBuiltinSources() }, async { second.ensureBuiltinSources() })
                    assertEquals(custom.id, db.sourceDao().getSelectionForPlatform("wy")?.sourceId)
                    assertNotNull(db.sourceDao().getSelectionForPlatform("tx"))
                    assertNull(db.sourceDao().getSelectionForPlatform("tx")?.sourceId)
                    assertTrue(second.supportedQualities("tx").isEmpty())
                    assertEquals(custom, db.sourceDao().getSourceById(custom.id))
                }
            }
        } finally { db.close() }
    }

    private data class ScriptRequest(val id: Long, val url: String, val options: String)

    @Test fun suppliedScriptUsesRealAesEapiAndResolvesItsConcurrentFallback() = runBlocking {
        QuickJsEngine().use { engine ->
            val requests = Channel<ScriptRequest>(Channel.UNLIMITED)
            var initialized = ""
            engine.registerHostBridge(object : QuickJsHostCallback {
                override fun onConsoleLog(level: String, message: String) = Unit
                override fun onLxSend(eventName: String, dataJson: String) { initialized = dataJson }
                override fun onLxRequest(reqId: Long, url: String, optionsJson: String) {
                    requests.trySend(ScriptRequest(reqId, url, optionsJson))
                }
            })
            val script = context.assets.open(LxSourceManager.STELLARWAVE_ASSET).bufferedReader().use { it.readText() }
            engine.evaluate("lx.currentScriptInfo = ${Gson().toJson(mapOf("rawScript" to script))}; undefined;")
            engine.evaluate(script)
            assertTrue(JsonParser.parseString(initialized).asJsonObject.get("status").asBoolean)
            val result = async(Dispatchers.Default) {
                engine.evaluateAsync("__lx_handlers.request({source:'wy',action:'musicUrl',info:{type:'flac',musicInfo:{songmid:'347230'}}})")
            }
            var officialSeen = false
            repeat(3) {
                val request = withTimeout(5000) { requests.receive() }
                if (request.url.contains("/eapi/")) {
                    officialSeen = true
                    val options = JsonParser.parseString(request.options).asJsonObject
                    assertEquals("POST", options.get("method").asString)
                    val params = options.getAsJsonObject("form").get("params").asString
                    val encrypted = params.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
                    val plain = cipher.doFinal(encrypted).toString(Charsets.UTF_8).split("-36cd479b6b5-")
                    assertEquals("/api/song/enhance/player/url/v1", plain[0])
                    val payload = JsonParser.parseString(plain[1]).asJsonObject
                    assertEquals(347230, payload.getAsJsonArray("ids")[0].asInt)
                    assertEquals("lossless", payload.get("level").asString)
                    engine.resolveLxRequest(request.id, false,
                        """{"statusCode":200,"body":"{\"data\":[{\"url\":\"https://example.com/original.flac\"}]}"}""")
                } else engine.resolveLxRequest(request.id, true, "fixture backend unavailable")
            }
            assertTrue("Official EAPI was skipped, possibly due to missing crypto", officialSeen)
            assertEquals("https://example.com/original.flac", JsonParser.parseString(result.await()).asString)
            assertEquals("0", engine.evaluate("Object.keys(__lx_callbacks).length"))
        }
    }

    @Test fun scriptFormRequestsAndRawScriptMetadataReachRealHttpBridge() = runBlocking {
        val db = database()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("https://example.com/form-audio"))
                LxSourceManager(context, db, NetworkPolicy(setOf("localhost", "127.0.0.1"))).use { manager ->
                    val code = """
                        /*! @name 表单桥接验证 */
                        lx.on(lx.EVENT_NAMES.request, () => new Promise((resolve,reject) => {
                            if (!lx.currentScriptInfo.rawScript.includes('表单桥接验证')) throw new Error('rawScript unavailable');
                            lx.request('${server.url("/eapi")}', {method:'POST', timeout:4000, form:{params:'拾音 +&='}},
                                (err,resp) => err ? reject(err) : resolve(resp.body));
                        }));
                        lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{wy:{name:'表单',actions:['musicUrl'],qualitys:['128k']}}});
                    """.trimIndent()
                    val source = manager.importSourceFromCode(code)
                    manager.selectSourceForPlatform("wy", source.id)
                    assertEquals("https://example.com/form-audio", manager.resolveMusicUrl("wy", "1"))
                    val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                    assertEquals("POST", request.method)
                    assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"))
                    val body = request.body.readUtf8()
                    assertEquals("拾音 +&=", java.net.URLDecoder.decode(body.substringAfter("params="), "UTF-8"))
                }
            }
        } finally { db.close() }
    }
}
