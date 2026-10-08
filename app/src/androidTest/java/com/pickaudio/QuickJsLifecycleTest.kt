package com.pickaudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.pickaudio.source.LxSourceManager
import com.pickaudio.source.QuickJsEngine
import com.pickaudio.source.QuickJsHostCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickJsLifecycleTest {
    private data class Request(val id: Long, val url: String, val options: String)
    private fun initialize(engine: QuickJsEngine, requests: Channel<Request>) {
        engine.registerHostBridge(object : QuickJsHostCallback {
            override fun onConsoleLog(level: String, message: String) = Unit
            override fun onLxSend(eventName: String, dataJson: String) = Unit
            override fun onLxRequest(reqId: Long, url: String, optionsJson: String) {
                requests.trySend(Request(reqId, url, optionsJson))
            }
        })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = context.assets.open(LxSourceManager.STELLARWAVE_ASSET).bufferedReader().use { it.readText() }
        engine.evaluate("lx.currentScriptInfo = ${Gson().toJson(mapOf("rawScript" to script))}; undefined;")
        engine.evaluate(script)
    }

    private val qqRequest = """__lx_handlers.request({source:'tx',action:'musicUrl',info:{type:'128k',musicInfo:{songmid:'original_mid',songId:123,strMediaMid:'media_mid',name:'QQ回归',singer:'测试'}}})"""

    @Test fun bundledQqOfficialRequestKeepsSongAndMediaIdsSeparate() = runBlocking {
        QuickJsEngine().use { engine ->
            val requests = Channel<Request>(Channel.UNLIMITED)
            initialize(engine, requests)
            val pending = async(Dispatchers.Default) { engine.evaluateAsync(qqRequest) }
            try {
                val request = withTimeout(5_000) { requests.receive() }
                val payload = JsonParser.parseString(JsonParser.parseString(request.options).asJsonObject.get("body").asString)
                    .asJsonObject.getAsJsonObject("req_0").getAsJsonObject("param")
                assertEquals("original_mid", payload.getAsJsonArray("songmid")[0].asString)
                assertEquals("M500media_mid.mp3", payload.getAsJsonArray("filename")[0].asString)
            } finally { pending.cancelAndJoin() }
        }
    }

    @Test fun allQqBackendsFailAndAnotherRequestCanStillResolve() = runBlocking {
        QuickJsEngine().use { engine ->
            val requests = Channel<Request>(Channel.UNLIMITED)
            initialize(engine, requests)
            val failure = async(Dispatchers.Default) { runCatching { engine.evaluateAsync(qqRequest) }.exceptionOrNull() }
            var count = 0
            val responses = launch(Dispatchers.IO) {
                for (request in requests) {
                    count++
                    engine.resolveLxRequest(request.id, true, "fixture backend unavailable")
                }
            }
            try {
                val error = withTimeout(10_000) { failure.await() }
                assertTrue(error?.message.orEmpty(), error?.message?.contains("所有后端均失败") == true)
                assertTrue("QQ fallback pool was not exhausted", count > 10)
            } finally { responses.cancelAndJoin() }
            val next = async(Dispatchers.Default) { engine.evaluateAsync(qqRequest) }
            val official = withTimeout(5_000) { requests.receive() }
            assertTrue(official.url.contains("musicu.fcg"))
            engine.resolveLxRequest(official.id, false,
                """{"statusCode":200,"body":"{\"req_0\":{\"data\":{\"sip\":[\"https://example.com/\"],\"midurlinfo\":[{\"purl\":\"original.mp3\"}]}}}"}""")
            assertEquals("https://example.com/original.mp3", JsonParser.parseString(withTimeout(5_000) { next.await() }).asString)
        }
    }

    @Test fun cancelledQqRequestsCanCloseWithOutstandingCallbacks() = runBlocking {
        repeat(10) {
            val engine = QuickJsEngine()
            val requests = Channel<Request>(Channel.UNLIMITED)
            try {
                initialize(engine, requests)
                val pending = async(Dispatchers.Default) { engine.evaluateAsync(qqRequest) }
                val request = withTimeout(5_000) { requests.receive() }
                pending.cancelAndJoin()
                engine.close()
                // A network response racing cancellation must not touch the freed native context.
                withContext(Dispatchers.IO) { engine.resolveLxRequest(request.id, true, "late response") }
            } finally { engine.close() }
        }
    }
}
