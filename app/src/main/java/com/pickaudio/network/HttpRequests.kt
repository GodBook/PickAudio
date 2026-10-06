package com.pickaudio.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream

/** Own the call for the entire response, including streaming reads. */
suspend fun <T> OkHttpClient.withResponse(request: Request, read: suspend (Response) -> T): T =
    coroutineScope {
        val call = newCall(request)
        val worker = async(Dispatchers.IO) {
            call.execute().use { response ->
                currentCoroutineContext().ensureActive()
                read(response)
            }
        }
        try {
            worker.await()
        } finally {
            // await resumes on cancellation even when the IO worker is blocked in read().
            call.cancel()
        }
    }

suspend fun ResponseBody.readLimitedBytes(maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    require(contentLength() <= maxBytes) { "响应内容超过允许大小" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
    byteStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes.toLong() - output.size() + 1).toInt())
            if (count < 0) break
            require(output.size().toLong() + count <= maxBytes) { "响应内容超过允许大小" }
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

suspend fun ResponseBody.readLimitedText(maxBytes: Int): String =
    String(readLimitedBytes(maxBytes), contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
