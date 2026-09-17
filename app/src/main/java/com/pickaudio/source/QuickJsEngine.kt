package com.pickaudio.source

import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class QuickJsEngine : Closeable {
    private val lock = ReentrantLock()
    private var rtPtr: Long = 0
    private var ctxPtr: Long = 0

    init {
        rtPtr = QuickJsNativeBridge.nativeCreateRuntime()
        if (rtPtr == 0L) {
            throw IllegalStateException("Failed to create QuickJS runtime")
        }
        // Set 64MB memory limit and 2000ms execution timeout
        QuickJsNativeBridge.nativeSetMemoryLimit(rtPtr, 64L * 1024 * 1024)
        QuickJsNativeBridge.nativeSetExecutionTimeout(rtPtr, 2000L)

        ctxPtr = QuickJsNativeBridge.nativeCreateContext(rtPtr)
        if (ctxPtr == 0L) {
            QuickJsNativeBridge.nativeDestroyRuntime(rtPtr)
            rtPtr = 0
            throw IllegalStateException("Failed to create QuickJS context")
        }
    }

    fun registerHostBridge(callback: QuickJsHostCallback) {
        lock.withLock {
            checkOpen()
            QuickJsNativeBridge.nativeRegisterHostBridge(ctxPtr, callback)
        }
    }

    fun evaluate(script: String, filename: String = "script.js"): String? {
        return lock.withLock {
            checkOpen()
            val res = QuickJsNativeBridge.nativeEvaluate(ctxPtr, script, filename)
            // Process microtasks
            QuickJsNativeBridge.nativeExecutePendingJobs(rtPtr)
            res
        }
    }

    fun resolveLxRequest(reqId: Long, isErr: Boolean, dataJson: String?) {
        lock.withLock {
            if (ctxPtr != 0L) {
                QuickJsNativeBridge.nativeResolveLxRequestCallback(ctxPtr, reqId, isErr, dataJson)
                QuickJsNativeBridge.nativeExecutePendingJobs(rtPtr)
            }
        }
    }

    fun executePendingJobs(): Int {
        return lock.withLock {
            if (rtPtr != 0L) {
                QuickJsNativeBridge.nativeExecutePendingJobs(rtPtr)
            } else 0
        }
    }

    private fun checkOpen() {
        if (ctxPtr == 0L || rtPtr == 0L) {
            throw IllegalStateException("QuickJsEngine is closed")
        }
    }

    override fun close() {
        lock.withLock {
            if (ctxPtr != 0L) {
                QuickJsNativeBridge.nativeDestroyContext(ctxPtr)
                ctxPtr = 0
            }
            if (rtPtr != 0L) {
                QuickJsNativeBridge.nativeDestroyRuntime(rtPtr)
                rtPtr = 0
            }
        }
    }
}
