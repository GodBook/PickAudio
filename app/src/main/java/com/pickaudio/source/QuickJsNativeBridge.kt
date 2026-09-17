package com.pickaudio.source

interface QuickJsHostCallback {
    fun onConsoleLog(level: String, message: String)
    fun onLxSend(eventName: String, dataJson: String)
    fun onLxRequest(reqId: Long, url: String, optionsJson: String)
}

object QuickJsNativeBridge {
    init {
        try {
            System.loadLibrary("quickjs_bridge")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    external fun nativeCreateRuntime(): Long
    external fun nativeSetMemoryLimit(rtPtr: Long, limitBytes: Long)
    external fun nativeSetExecutionTimeout(rtPtr: Long, timeoutMs: Long)
    external fun nativeCreateContext(rtPtr: Long): Long
    external fun nativeRegisterHostBridge(ctxPtr: Long, callback: QuickJsHostCallback)
    external fun nativeEvaluate(ctxPtr: Long, script: String, filename: String): String?
    external fun nativeExecutePendingJobs(rtPtr: Long): Int
    external fun nativeResolveLxRequestCallback(ctxPtr: Long, reqId: Long, isErr: Boolean, dataJson: String?)
    external fun nativeDestroyContext(ctxPtr: Long)
    external fun nativeDestroyRuntime(rtPtr: Long)
}
