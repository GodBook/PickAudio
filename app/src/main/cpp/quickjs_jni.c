#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <android/log.h>
#include "quickjs.h"

#define LOG_TAG "QuickJsBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct {
    int64_t start_time_ms;
    int64_t timeout_ms;
    JavaVM *jvm;
    jobject host_callback_global;
} RuntimeUserData;

static int64_t get_time_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + (ts.tv_nsec / 1000000);
}

static int js_interrupt_handler(JSRuntime *rt, void *opaque) {
    RuntimeUserData *data = (RuntimeUserData *)opaque;
    if (!data || data->timeout_ms <= 0) {
        return 0;
    }
    int64_t now = get_time_ms();
    if (now - data->start_time_ms > data->timeout_ms) {
        LOGE("QuickJS execution timed out after %lld ms", (long long)(now - data->start_time_ms));
        return 1; // interrupt
    }
    return 0;
}

static JNIEnv *get_jni_env(JavaVM *jvm) {
    JNIEnv *env = NULL;
    if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*jvm)->AttachCurrentThread(jvm, &env, NULL);
    }
    return env;
}

// Host JS functions
static JSValue js_console_log(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JSRuntime *rt = JS_GetRuntime(ctx);
    RuntimeUserData *ud = (RuntimeUserData *)JS_GetRuntimeOpaque(rt);
    if (!ud || !ud->jvm || !ud->host_callback_global) return JS_UNDEFINED;

    JNIEnv *env = get_jni_env(ud->jvm);
    if (!env) return JS_UNDEFINED;

    if (argc > 0) {
        const char *str = JS_ToCString(ctx, argv[0]);
        if (str) {
            jstring jstr = (*env)->NewStringUTF(env, str);
            jclass clazz = (*env)->GetObjectClass(env, ud->host_callback_global);
            jmethodID mid = (*env)->GetMethodID(env, clazz, "onConsoleLog", "(Ljava/lang/String;Ljava/lang/String;)V");
            if (mid) {
                jstring level = (*env)->NewStringUTF(env, "info");
                (*env)->CallVoidMethod(env, ud->host_callback_global, mid, level, jstr);
                (*env)->DeleteLocalRef(env, level);
            }
            (*env)->DeleteLocalRef(env, jstr);
            (*env)->DeleteLocalRef(env, clazz);
            JS_FreeCString(ctx, str);
        }
    }
    return JS_UNDEFINED;
}

static JSValue js_lx_send(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JSRuntime *rt = JS_GetRuntime(ctx);
    RuntimeUserData *ud = (RuntimeUserData *)JS_GetRuntimeOpaque(rt);
    if (!ud || !ud->jvm || !ud->host_callback_global) return JS_UNDEFINED;

    JNIEnv *env = get_jni_env(ud->jvm);
    if (!env) return JS_UNDEFINED;

    if (argc >= 2) {
        const char *event_name = JS_ToCString(ctx, argv[0]);
        JSValue json_val = JS_JSONStringify(ctx, argv[1], JS_UNDEFINED, JS_UNDEFINED);
        const char *data_json = JS_ToCString(ctx, json_val);

        if (event_name && data_json) {
            jstring jevent = (*env)->NewStringUTF(env, event_name);
            jstring jdata = (*env)->NewStringUTF(env, data_json);
            jclass clazz = (*env)->GetObjectClass(env, ud->host_callback_global);
            jmethodID mid = (*env)->GetMethodID(env, clazz, "onLxSend", "(Ljava/lang/String;Ljava/lang/String;)V");
            if (mid) {
                (*env)->CallVoidMethod(env, ud->host_callback_global, mid, jevent, jdata);
            }
            (*env)->DeleteLocalRef(env, jevent);
            (*env)->DeleteLocalRef(env, jdata);
            (*env)->DeleteLocalRef(env, clazz);
        }
        if (event_name) JS_FreeCString(ctx, event_name);
        if (data_json) JS_FreeCString(ctx, data_json);
        JS_FreeValue(ctx, json_val);
    }
    return JS_UNDEFINED;
}

static JSValue js_lx_request(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JSRuntime *rt = JS_GetRuntime(ctx);
    RuntimeUserData *ud = (RuntimeUserData *)JS_GetRuntimeOpaque(rt);
    if (!ud || !ud->jvm || !ud->host_callback_global) return JS_UNDEFINED;

    JNIEnv *env = get_jni_env(ud->jvm);
    if (!env) return JS_UNDEFINED;

    if (argc >= 3) {
        const char *url = JS_ToCString(ctx, argv[0]);
        JSValue opt_json_val = JS_JSONStringify(ctx, argv[1], JS_UNDEFINED, JS_UNDEFINED);
        const char *options_json = JS_ToCString(ctx, opt_json_val);

        // Store callback function in __lx_callbacks
        JSValue global_obj = JS_GetGlobalObject(ctx);
        JSValue callbacks = JS_GetPropertyStr(ctx, global_obj, "__lx_callbacks");
        if (!JS_IsObject(callbacks)) {
            callbacks = JS_NewObject(ctx);
            JS_SetPropertyStr(ctx, global_obj, "__lx_callbacks", callbacks);
        }

        static int64_t req_id_seq = 1000;
        int64_t req_id = ++req_id_seq;
        char req_id_str[32];
        snprintf(req_id_str, sizeof(req_id_str), "%lld", (long long)req_id);

        JS_SetPropertyStr(ctx, callbacks, req_id_str, JS_DupValue(ctx, argv[2]));
        JS_FreeValue(ctx, global_obj);

        if (url && options_json) {
            jstring jurl = (*env)->NewStringUTF(env, url);
            jstring jopt = (*env)->NewStringUTF(env, options_json);
            jclass clazz = (*env)->GetObjectClass(env, ud->host_callback_global);
            jmethodID mid = (*env)->GetMethodID(env, clazz, "onLxRequest", "(JLjava/lang/String;Ljava/lang/String;)V");
            if (mid) {
                (*env)->CallVoidMethod(env, ud->host_callback_global, mid, (jlong)req_id, jurl, jopt);
            }
            (*env)->DeleteLocalRef(env, jurl);
            (*env)->DeleteLocalRef(env, jopt);
            (*env)->DeleteLocalRef(env, clazz);
        }
        if (url) JS_FreeCString(ctx, url);
        if (options_json) JS_FreeCString(ctx, options_json);
        JS_FreeValue(ctx, opt_json_val);
    }
    return JS_UNDEFINED;
}

JNIEXPORT jlong JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeCreateRuntime(JNIEnv *env, jobject thiz) {
    JSRuntime *rt = JS_NewRuntime();
    if (!rt) return 0;

    RuntimeUserData *ud = (RuntimeUserData *)calloc(1, sizeof(RuntimeUserData));
    (*env)->GetJavaVM(env, &ud->jvm);
    ud->timeout_ms = 2000; // default 2 seconds execution timeout
    JS_SetRuntimeOpaque(rt, ud);
    JS_SetInterruptHandler(rt, js_interrupt_handler, ud);

    // 64MB memory limit as specified in design doc
    JS_SetMemoryLimit(rt, (size_t)64 * 1024 * 1024);

    return (jlong)rt;
}

JNIEXPORT void JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeSetMemoryLimit(JNIEnv *env, jobject thiz, jlong rt_ptr, jlong limit_bytes) {
    JSRuntime *rt = (JSRuntime *)rt_ptr;
    if (rt) {
        JS_SetMemoryLimit(rt, (size_t)limit_bytes);
    }
}

JNIEXPORT void JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeSetExecutionTimeout(JNIEnv *env, jobject thiz, jlong rt_ptr, jlong timeout_ms) {
    JSRuntime *rt = (JSRuntime *)rt_ptr;
    if (rt) {
        RuntimeUserData *ud = (RuntimeUserData *)JS_GetRuntimeOpaque(rt);
        if (ud) {
            ud->timeout_ms = timeout_ms;
        }
    }
}

JNIEXPORT jlong JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeCreateContext(JNIEnv *env, jobject thiz, jlong rt_ptr) {
    JSRuntime *rt = (JSRuntime *)rt_ptr;
    if (!rt) return 0;
    JSContext *ctx = JS_NewContext(rt);
    return (jlong)ctx;
}

JNIEXPORT void JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeRegisterHostBridge(JNIEnv *env, jobject thiz, jlong ctx_ptr, jobject callback) {
    JSContext *ctx = (JSContext *)ctx_ptr;
    if (!ctx) return;
    JSRuntime *rt = JS_GetRuntime(ctx);
    RuntimeUserData *ud = (RuntimeUserData *)JS_GetRuntimeOpaque(rt);
    if (ud) {
        if (ud->host_callback_global) {
            (*env)->DeleteGlobalRef(env, ud->host_callback_global);
        }
        ud->host_callback_global = (*env)->NewGlobalRef(env, callback);
    }

    JSValue global_obj = JS_GetGlobalObject(ctx);

    // Setup console
    JSValue console = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, console, "log", JS_NewCFunction(ctx, js_console_log, "log", 1));
    JS_SetPropertyStr(ctx, console, "info", JS_NewCFunction(ctx, js_console_log, "info", 1));
    JS_SetPropertyStr(ctx, console, "warn", JS_NewCFunction(ctx, js_console_log, "warn", 1));
    JS_SetPropertyStr(ctx, console, "error", JS_NewCFunction(ctx, js_console_log, "error", 1));
    JS_SetPropertyStr(ctx, global_obj, "console", console);

    // Setup lx object
    JSValue lx = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, lx, "version", JS_NewString(ctx, "2.0.0"));
    JS_SetPropertyStr(ctx, lx, "env", JS_NewString(ctx, "mobile"));

    JSValue event_names = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, event_names, "inited", JS_NewString(ctx, "inited"));
    JS_SetPropertyStr(ctx, event_names, "request", JS_NewString(ctx, "request"));
    JS_SetPropertyStr(ctx, event_names, "updateAlert", JS_NewString(ctx, "updateAlert"));
    JS_SetPropertyStr(ctx, lx, "EVENT_NAMES", event_names);

    JS_SetPropertyStr(ctx, lx, "send", JS_NewCFunction(ctx, js_lx_send, "send", 2));
    JS_SetPropertyStr(ctx, lx, "request", JS_NewCFunction(ctx, js_lx_request, "request", 3));

    // Register lx on globalThis
    JS_SetPropertyStr(ctx, global_obj, "lx", JS_DupValue(ctx, lx));
    JS_FreeValue(ctx, lx);

    // Register callback holder
    JSValue callbacks = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, global_obj, "__lx_callbacks", callbacks);

    // Inject support library JS for lx.on, lx.utils, etc.
    const char *bootstrap_js = 
        "globalThis.global = globalThis;\n"
        "globalThis.__lx_handlers = {};\n"
        "globalThis.lx.on = function(event_name, handler) {\n"
        "    globalThis.__lx_handlers[event_name] = handler;\n"
        "};\n"
        "globalThis.lx.utils = {\n"
        "    buffer: {\n"
        "        from: function(data, enc) {\n"
        "            if (typeof data === 'string') {\n"
        "                if (enc === 'hex') {\n"
        "                    var bytes = [];\n"
        "                    for (var i = 0; i < data.length; i += 2) bytes.push(parseInt(data.substr(i, 2), 16));\n"
        "                    return new Uint8Array(bytes);\n"
        "                } else if (enc === 'base64') {\n"
        "                    var bin = atob(data);\n"
        "                    var bytes = new Uint8Array(bin.length);\n"
        "                    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);\n"
        "                    return bytes;\n"
        "                }\n"
        "                var utf8 = unescape(encodeURIComponent(data));\n"
        "                var arr = new Uint8Array(utf8.length);\n"
        "                for (var i = 0; i < utf8.length; i++) arr[i] = utf8.charCodeAt(i);\n"
        "                return arr;\n"
        "            }\n"
        "            return new Uint8Array(data);\n"
        "        },\n"
        "        bufToString: function(buf, enc) {\n"
        "            var u8 = (buf instanceof Uint8Array) ? buf : new Uint8Array(buf);\n"
        "            if (enc === 'hex') {\n"
        "                var hex = '';\n"
        "                for (var i = 0; i < u8.length; i++) {\n"
        "                    var h = u8[i].toString(16);\n"
        "                    hex += (h.length < 2 ? '0' + h : h);\n"
        "                }\n"
        "                return hex;\n"
        "            } else if (enc === 'base64') {\n"
        "                var bin = '';\n"
        "                for (var i = 0; i < u8.length; i++) bin += String.fromCharCode(u8[i]);\n"
        "                return btoa(bin);\n"
        "            }\n"
        "            var bin = '';\n"
        "            for (var i = 0; i < u8.length; i++) bin += String.fromCharCode(u8[i]);\n"
        "            try { return decodeURIComponent(escape(bin)); } catch(e) { return bin; }\n"
        "        }\n"
        "    },\n"
        "    crypto: {\n"
        "        md5: function(str) { return globalThis.__host_md5 ? globalThis.__host_md5(str) : ''; },\n"
        "        randomBytes: function(size) {\n"
        "            var arr = new Uint8Array(size);\n"
        "            for (var i = 0; i < size; i++) arr[i] = Math.floor(Math.random() * 256);\n"
        "            return arr;\n"
        "        }\n"
        "    }\n"
        "};\n";

    JSValue bval = JS_Eval(ctx, bootstrap_js, strlen(bootstrap_js), "<bootstrap>", JS_EVAL_TYPE_GLOBAL);
    JS_FreeValue(ctx, bval);

    JS_FreeValue(ctx, global_obj);
}

JNIEXPORT jstring JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeEvaluate(JNIEnv *env, jobject thiz, jlong ctx_ptr, jstring script, jstring filename) {
    JSContext *ctx = (JSContext *)ctx_ptr;
    if (!ctx) return NULL;

    JSRuntime *rt = JS_GetRuntime(ctx);
    RuntimeUserData *ud = (RuntimeUserData *)JS_GetRuntimeOpaque(rt);
    if (ud) {
        ud->start_time_ms = get_time_ms();
    }

    const char *c_script = (*env)->GetStringUTFChars(env, script, NULL);
    const char *c_file = (*env)->GetStringUTFChars(env, filename, NULL);

    JSValue val = JS_Eval(ctx, c_script, strlen(c_script), c_file, JS_EVAL_TYPE_GLOBAL);

    (*env)->ReleaseStringUTFChars(env, script, c_script);
    (*env)->ReleaseStringUTFChars(env, filename, c_file);

    if (JS_IsException(val)) {
        JSValue exception_val = JS_GetException(ctx);
        const char *err_str = JS_ToCString(ctx, exception_val);
        JS_FreeValue(ctx, exception_val);
        JS_FreeValue(ctx, val);

        jclass ex_class = (*env)->FindClass(env, "java/lang/IllegalStateException");
        (*env)->ThrowNew(env, ex_class, err_str ? err_str : "QuickJS evaluation error");
        if (err_str) JS_FreeCString(ctx, err_str);
        return NULL;
    }

    JSValue json_val = JS_JSONStringify(ctx, val, JS_UNDEFINED, JS_UNDEFINED);
    const char *result_json = JS_ToCString(ctx, json_val);
    jstring res = NULL;
    if (result_json) {
        res = (*env)->NewStringUTF(env, result_json);
        JS_FreeCString(ctx, result_json);
    }
    JS_FreeValue(ctx, json_val);
    JS_FreeValue(ctx, val);
    return res;
}

JNIEXPORT jint JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeExecutePendingJobs(JNIEnv *env, jobject thiz, jlong rt_ptr) {
    JSRuntime *rt = (JSRuntime *)rt_ptr;
    if (!rt) return 0;
    JSContext *pctx = NULL;
    int count = 0;
    while (JS_ExecutePendingJob(rt, &pctx) > 0) {
        count++;
        if (count > 1000) break; // prevent infinite job loops
    }
    return count;
}

JNIEXPORT void JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeResolveLxRequestCallback(JNIEnv *env, jobject thiz, jlong ctx_ptr, jlong req_id, jboolean is_err, jstring data_json) {
    JSContext *ctx = (JSContext *)ctx_ptr;
    if (!ctx) return;

    JSValue global_obj = JS_GetGlobalObject(ctx);
    JSValue callbacks = JS_GetPropertyStr(ctx, global_obj, "__lx_callbacks");
    char req_id_str[32];
    snprintf(req_id_str, sizeof(req_id_str), "%lld", (long long)req_id);

    JSValue cb = JS_GetPropertyStr(ctx, callbacks, req_id_str);
    if (JS_IsFunction(ctx, cb)) {
        JSValue args[2];
        if (is_err) {
            const char *err_str = data_json ? (*env)->GetStringUTFChars(env, data_json, NULL) : "Request error";
            args[0] = JS_NewError(ctx);
            JS_SetPropertyStr(ctx, args[0], "message", JS_NewString(ctx, err_str));
            args[1] = JS_UNDEFINED;
            if (data_json) (*env)->ReleaseStringUTFChars(env, data_json, err_str);
        } else {
            args[0] = JS_NULL;
            const char *resp_str = (*env)->GetStringUTFChars(env, data_json, NULL);
            args[1] = JS_ParseJSON(ctx, resp_str, strlen(resp_str), "<json>");
            (*env)->ReleaseStringUTFChars(env, data_json, resp_str);
        }
        JSValue ret = JS_Call(ctx, cb, JS_UNDEFINED, 2, args);
        JS_FreeValue(ctx, ret);
        JS_FreeValue(ctx, args[0]);
        JS_FreeValue(ctx, args[1]);

        // Delete callback
        JS_SetPropertyStr(ctx, callbacks, req_id_str, JS_UNDEFINED);
    }
    JS_FreeValue(ctx, cb);
    JS_FreeValue(ctx, callbacks);
    JS_FreeValue(ctx, global_obj);
}

JNIEXPORT void JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeDestroyContext(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    JSContext *ctx = (JSContext *)ctx_ptr;
    if (ctx) {
        JS_FreeContext(ctx);
    }
}

JNIEXPORT void JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeDestroyRuntime(JNIEnv *env, jobject thiz, jlong rt_ptr) {
    JSRuntime *rt = (JSRuntime *)rt_ptr;
    if (rt) {
        RuntimeUserData *ud = (RuntimeUserData *)JS_GetRuntimeOpaque(rt);
        if (ud) {
            if (ud->host_callback_global) {
                (*env)->DeleteGlobalRef(env, ud->host_callback_global);
            }
            free(ud);
        }
        JS_FreeRuntime(rt);
    }
}
