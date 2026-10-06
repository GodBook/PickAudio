package com.pickaudio

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.db.PlatformSourceSelectionEntity
import com.pickaudio.data.db.SourceScriptEntity
import com.pickaudio.network.NetworkPolicy
import com.pickaudio.source.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OptimizationSourceTest {
    private open class Host : QuickJsHostCallback {
        var id = 0L
        var message = ""
        override fun onConsoleLog(level: String, message: String) { this.message = message }
        override fun onLxSend(eventName: String, dataJson: String) { message = dataJson }
        override fun onLxRequest(reqId: Long, url: String, optionsJson: String) { id = reqId }
    }
    @Test fun unicodeAndMd5UseRealUtf8InBothDirections() {
        QuickJsEngine().use { engine ->
            val host = Host(); engine.registerHostBridge(host)
            val text = "拾音🎵𠮷\u0000尾"
            val json = com.google.gson.Gson().toJson(text)
            assertEquals(text, JsonParser.parseString(engine.evaluate(json)).asString)
            engine.evaluate("console.log($json)")
            assertEquals(text, host.message)
            assertEquals("900150983cd24fb0d6963f7d28e17f72",
                JsonParser.parseString(engine.evaluate("lx.utils.crypto.md5('abc')")).asString)
            assertEquals("900150983cd24fb0d6963f7d28e17f72",
                JsonParser.parseString(engine.evaluate("lx.utils.crypto.md5(new Uint8Array([97,98,99]))")).asString)
        }
    }
    @Test fun thousandCompletedCallbacksReleaseKeysAndNativeReferences() {
        QuickJsEngine().use { engine ->
            val host = Host(); engine.registerHostBridge(host)
            val baseline = engine.memoryUsageBytes()
            repeat(1000) {
                engine.evaluate("lx.request('https://example.com/', {}, function(e, data) { globalThis.last = data.body; })")
                engine.resolveLxRequest(host.id, false, """{"body":"拾音🎵"}""")
            }
            assertEquals("0", engine.evaluate("Object.keys(globalThis.__lx_callbacks).length"))
            assertEquals("拾音🎵", JsonParser.parseString(engine.evaluate("globalThis.last")).asString)
            assertTrue("Native retained too much memory", engine.memoryUsageBytes() - baseline < 128 * 1024)
        }
    }
    @Test fun synchronousAndUnhandledAsyncExceptionsReportActualReason() {
        QuickJsEngine().use { engine ->
            engine.registerHostBridge(Host())
            assertTrue(runCatching { engine.evaluate("throw new Error('同步🎵')") }.exceptionOrNull()?.message?.contains("同步🎵") == true)
            assertTrue(runCatching { engine.evaluate("Promise.resolve().then(function() { throw new Error('异步🎵'); })") }
                .exceptionOrNull()?.message?.contains("异步🎵") == true)
        }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun script(handler: String) = """
        lx.on(lx.EVENT_NAMES.request, $handler);
        lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{wy:{name:'验证',actions:['musicUrl'],qualitys:['128k']}}});
    """.trimIndent()

    @Test fun lateDefaultSourceInitializationKeepsUserSelectionAndExplicitDisable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
        try {
            val dao = db.sourceDao()
            dao.insertOrUpdate(SourceScriptEntity(id = "builtin_aggregate", name = "默认",
                scriptHash = "default-fixture", scriptContent = "", capabilitiesJson = "{}"))
            dao.insertOrUpdate(SourceScriptEntity(id = "custom-fixture", name = "自定义",
                scriptHash = "custom-fixture", scriptContent = "", capabilitiesJson = "{}"))
            // Default initialization can observe no row before a user selection is committed.
            assertNull(dao.getSelectionForPlatform("wy"))
            dao.setPlatformSelection(PlatformSourceSelectionEntity("wy", "custom-fixture"))
            dao.insertDefaultPlatformSelection(PlatformSourceSelectionEntity("wy", "builtin_aggregate"))
            assertEquals("custom-fixture", dao.getSelectionForPlatform("wy")?.sourceId)
            dao.setPlatformSelection(PlatformSourceSelectionEntity("wy", null))
            dao.insertDefaultPlatformSelection(PlatformSourceSelectionEntity("wy", "builtin_aggregate"))
            assertNotNull(dao.getSelectionForPlatform("wy"))
            assertNull(dao.getSelectionForPlatform("wy")?.sourceId)
            dao.insertDefaultPlatformSelection(PlatformSourceSelectionEntity("tx", "builtin_aggregate"))
            assertEquals("builtin_aggregate", dao.getSelectionForPlatform("tx")?.sourceId)
        } finally { db.close() }
    }

    @Test fun returnedPrivateUrlRequiresExplicitFixtureInjection() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
        try {
            LxSourceManager(context, db).use { manager ->
                val source = manager.importSourceFromCode(script("() => 'https://127.0.0.1/audio'"))
                manager.selectSourceForPlatform("wy", source.id)
                assertTrue(runCatching { manager.resolveMusicUrl("wy", "1") }.exceptionOrNull()?.message?.contains("禁止访问") == true)
            }
        } finally { db.close() }
    }
    @Test fun cancelledScriptRequestsStopAndFreshResolutionIsIndependent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("https://example.com/audio").throttleBody(1, 1, TimeUnit.SECONDS))
                server.enqueue(MockResponse().setBody("https://example.com/audio"))
                LxSourceManager(context, db, NetworkPolicy(setOf("localhost", "127.0.0.1"))).use { manager ->
                    val handler = """() => new Promise((resolve,reject) => lx.request('${server.url("/")}', {}, (e,r) => e ? reject(e) : resolve(r.body)))"""
                    val source = manager.importSourceFromCode(script(handler))
                    manager.selectSourceForPlatform("wy", source.id)
                    val pending = async { manager.resolveMusicUrl("wy", "1") }
                    assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
                    withTimeout(3000) { pending.cancelAndJoin() }
                    assertEquals("https://example.com/audio", withTimeout(5000) { manager.resolveMusicUrl("wy", "1") })
                }
            }
        } finally { db.close() }
    }
    @Test fun thrownNetworkCallbackFailsResolutionImmediately() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("ok"))
                LxSourceManager(context, db, NetworkPolicy(setOf("localhost", "127.0.0.1"))).use { manager ->
                    val source = manager.importSourceFromCode(script("""() => new Promise(() => lx.request('${server.url("/")}', {}, () => {throw new Error('回调🎵');}))"""))
                    manager.selectSourceForPlatform("wy", source.id)
                    val error = withTimeout(5000) { runCatching { manager.resolveMusicUrl("wy", "1") }.exceptionOrNull() }
                    assertTrue(error?.message.orEmpty(), error?.message?.contains("回调🎵") == true)
                }
            }
        } finally { db.close() }
    }
}
