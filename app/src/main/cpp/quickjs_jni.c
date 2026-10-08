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
    int64_t next_req_id;
    JSContext *rejection_ctx;
    JSValue rejection_promise;
    JSValue rejection_reason;
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
    return (*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) == JNI_OK ? env : NULL;
}
/* JNI modified UTF-8 is not JS UTF-8. Use standard UTF-8 in both directions. */
typedef struct { char *data; size_t length; } Utf8Text;
static Utf8Text java_utf8(JNIEnv *env, jstring string) {
    Utf8Text text = { NULL, 0 };
    if (!string) return text;
    jclass clazz = (*env)->FindClass(env, "java/lang/String");
    jmethodID method = clazz ? (*env)->GetMethodID(env, clazz, "getBytes", "(Ljava/lang/String;)[B") : NULL;
    jstring encoding = (*env)->NewStringUTF(env, "UTF-8");
    jbyteArray bytes = method && encoding ? (jbyteArray)(*env)->CallObjectMethod(env, string, method, encoding) : NULL;
    if (bytes && !(*env)->ExceptionCheck(env)) {
        text.length = (size_t)(*env)->GetArrayLength(env, bytes);
        text.data = (char *)malloc(text.length + 1);
        if (text.data) {
            (*env)->GetByteArrayRegion(env, bytes, 0, (jsize)text.length, (jbyte *)text.data);
            text.data[text.length] = 0;
        } else {
            jclass oom = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
            if (oom) { (*env)->ThrowNew(env, oom, "UTF-8 allocation failed"); (*env)->DeleteLocalRef(env, oom); }
        }
    }
    if (bytes) (*env)->DeleteLocalRef(env, bytes);
    if (encoding) (*env)->DeleteLocalRef(env, encoding);
    if (clazz) (*env)->DeleteLocalRef(env, clazz);
    return text;
}
static jstring new_java_utf8(JNIEnv *env, const char *data, size_t length) {
    if (!data) return NULL;
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
    if (!bytes) return NULL;
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, (const jbyte *)data);
    jclass clazz = (*env)->FindClass(env, "java/lang/String");
    jmethodID constructor = clazz ? (*env)->GetMethodID(env, clazz, "<init>", "([BLjava/lang/String;)V") : NULL;
    jstring encoding = (*env)->NewStringUTF(env, "UTF-8");
    jstring result = constructor && encoding ? (jstring)(*env)->NewObject(env, clazz, constructor, bytes, encoding) : NULL;
    (*env)->DeleteLocalRef(env, bytes);
    if (encoding) (*env)->DeleteLocalRef(env, encoding);
    if (clazz) (*env)->DeleteLocalRef(env, clazz);
    return result;
}
static jstring java_js_value(JSContext *ctx, JNIEnv *env, JSValueConst value) {
    size_t length = 0;
    const char *text = JS_ToCStringLen(ctx, &length, value);
    jstring result = text ? new_java_utf8(env, text, length) : NULL;
    if (text) JS_FreeCString(ctx, text);
    return result;
}
static void throw_java_message(JNIEnv *env, const char *message, size_t length) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass clazz = (*env)->FindClass(env, "java/lang/IllegalStateException");
    jmethodID constructor = clazz ? (*env)->GetMethodID(env, clazz, "<init>", "(Ljava/lang/String;)V") : NULL;
    jstring text = new_java_utf8(env, message, length);
    jobject error = constructor && text ? (*env)->NewObject(env, clazz, constructor, text) : NULL;
    if (error) { (*env)->Throw(env, (jthrowable)error); (*env)->DeleteLocalRef(env, error); }
    if (text) (*env)->DeleteLocalRef(env, text);
    if (clazz) (*env)->DeleteLocalRef(env, clazz);
}
static void throw_js_exception(JSContext *ctx, JNIEnv *env) {
    JSValue error = JS_GetException(ctx);
    size_t length = 0;
    const char *text = JS_ToCStringLen(ctx, &length, error);
    const char *fallback = "QuickJS execution failed";
    throw_java_message(env, text ? text : fallback, text ? length : strlen(fallback));
    if (text) JS_FreeCString(ctx, text);
    JS_FreeValue(ctx, error);
}
static int host_failed(JSContext *ctx, JNIEnv *env) {
    if (!(*env)->ExceptionCheck(env)) return 0;
    jthrowable error = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    jclass clazz = (*env)->GetObjectClass(env, error);
    jmethodID method = clazz ? (*env)->GetMethodID(env, clazz, "toString", "()Ljava/lang/String;") : NULL;
    jstring message = method ? (jstring)(*env)->CallObjectMethod(env, error, method) : NULL;
    Utf8Text text = java_utf8(env, message);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    JS_ThrowInternalError(ctx, "%s", text.data ? text.data : "Host callback failed");
    free(text.data);
    if (message) (*env)->DeleteLocalRef(env, message);
    if (clazz) (*env)->DeleteLocalRef(env, clazz);
    (*env)->DeleteLocalRef(env, error);
    return 1;
}
static void delete_callback(JSContext *ctx, JSValueConst callbacks, const char *key) {
    JSAtom atom = JS_NewAtom(ctx, key);
    JS_DeleteProperty(ctx, callbacks, atom, 0);
    JS_FreeAtom(ctx, atom);
}

static void clear_rejection(RuntimeUserData *ud) {
    if (ud->rejection_ctx) {
        JS_FreeValue(ud->rejection_ctx, ud->rejection_promise);
        JS_FreeValue(ud->rejection_ctx, ud->rejection_reason);
    }
    ud->rejection_ctx = NULL;
    ud->rejection_promise = ud->rejection_reason = JS_UNDEFINED;
}
static void promise_rejected(JSContext *ctx, JSValueConst promise, JSValueConst reason, JS_BOOL handled, void *opaque) {
    RuntimeUserData *ud = opaque;
    if (handled) {
        if (ud->rejection_ctx && JS_VALUE_GET_PTR(ud->rejection_promise) == JS_VALUE_GET_PTR(promise))
            clear_rejection(ud);
    } else {
        clear_rejection(ud);
        ud->rejection_ctx = ctx;
        ud->rejection_promise = JS_DupValue(ctx, promise);
        ud->rejection_reason = JS_DupValue(ctx, reason);
    }
}

// Host JS functions
static JSValue js_console_log(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    RuntimeUserData *ud = JS_GetRuntimeOpaque(JS_GetRuntime(ctx));
    JNIEnv *env = ud ? get_jni_env(ud->jvm) : NULL;
    if (!env || !ud->host_callback_global || argc < 1) return JS_UNDEFINED;
    jstring text = java_js_value(ctx, env, argv[0]);
    jclass clazz = (*env)->GetObjectClass(env, ud->host_callback_global);
    jmethodID method = clazz ? (*env)->GetMethodID(env, clazz, "onConsoleLog", "(Ljava/lang/String;Ljava/lang/String;)V") : NULL;
    jstring level = (*env)->NewStringUTF(env, "info");
    if (method && text && level) (*env)->CallVoidMethod(env, ud->host_callback_global, method, level, text);
    if (text) (*env)->DeleteLocalRef(env, text);
    if (level) (*env)->DeleteLocalRef(env, level);
    if (clazz) (*env)->DeleteLocalRef(env, clazz);
    return host_failed(ctx, env) ? JS_EXCEPTION : JS_UNDEFINED;
}
static JSValue js_lx_send(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    RuntimeUserData *ud = JS_GetRuntimeOpaque(JS_GetRuntime(ctx));
    JNIEnv *env = ud ? get_jni_env(ud->jvm) : NULL;
    if (!env || !ud->host_callback_global || argc < 2) return JS_UNDEFINED;
    JSValue json = JS_JSONStringify(ctx, argv[1], JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(json)) return json;
    jstring event = java_js_value(ctx, env, argv[0]);
    jstring data = java_js_value(ctx, env, json);
    jclass clazz = (*env)->GetObjectClass(env, ud->host_callback_global);
    jmethodID method = clazz ? (*env)->GetMethodID(env, clazz, "onLxSend", "(Ljava/lang/String;Ljava/lang/String;)V") : NULL;
    if (method && event && data) (*env)->CallVoidMethod(env, ud->host_callback_global, method, event, data);
    if (event) (*env)->DeleteLocalRef(env, event);
    if (data) (*env)->DeleteLocalRef(env, data);
    if (clazz) (*env)->DeleteLocalRef(env, clazz);
    JS_FreeValue(ctx, json);
    return host_failed(ctx, env) ? JS_EXCEPTION : JS_UNDEFINED;
}
static JSValue js_lx_request(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    RuntimeUserData *ud = JS_GetRuntimeOpaque(JS_GetRuntime(ctx));
    JNIEnv *env = ud ? get_jni_env(ud->jvm) : NULL;
    if (!env || !ud->host_callback_global || argc < 3) return JS_UNDEFINED;
    if (!JS_IsFunction(ctx, argv[2])) return JS_ThrowTypeError(ctx, "lx.request requires a callback");
    JSValue json = JS_JSONStringify(ctx, argv[1], JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(json)) return json;
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue callbacks = JS_GetPropertyStr(ctx, global, "__lx_callbacks");
    if (!JS_IsObject(callbacks)) {
        JS_FreeValue(ctx, callbacks);
        callbacks = JS_NewObject(ctx);
        JS_SetPropertyStr(ctx, global, "__lx_callbacks", JS_DupValue(ctx, callbacks));
    }
    int64_t req_id = ++ud->next_req_id;
    char key[32];
    snprintf(key, sizeof(key), "%lld", (long long)req_id);
    int stored = JS_SetPropertyStr(ctx, callbacks, key, JS_DupValue(ctx, argv[2]));
    jstring url = java_js_value(ctx, env, argv[0]);
    jstring options = java_js_value(ctx, env, json);
    jclass clazz = (*env)->GetObjectClass(env, ud->host_callback_global);
    jmethodID method = clazz ? (*env)->GetMethodID(env, clazz, "onLxRequest", "(JLjava/lang/String;Ljava/lang/String;)V") : NULL;
    if (stored >= 0 && method && url && options)
        (*env)->CallVoidMethod(env, ud->host_callback_global, method, (jlong)req_id, url, options);
    int failed = host_failed(ctx, env);
    if (failed || stored < 0) delete_callback(ctx, callbacks, key);
    if (url) (*env)->DeleteLocalRef(env, url);
    if (options) (*env)->DeleteLocalRef(env, options);
    if (clazz) (*env)->DeleteLocalRef(env, clazz);
    JS_FreeValue(ctx, callbacks);
    JS_FreeValue(ctx, global);
    JS_FreeValue(ctx, json);
    return failed || stored < 0 ? JS_EXCEPTION : JS_UNDEFINED;
}
static JSValue js_host_md5(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    RuntimeUserData *ud = JS_GetRuntimeOpaque(JS_GetRuntime(ctx));
    JNIEnv *env = ud ? get_jni_env(ud->jvm) : NULL;
    if (!env || !ud->host_callback_global || argc < 1) return JS_ThrowTypeError(ctx, "MD5 input is required");
    const uint8_t *bytes = NULL;
    size_t length = 0, offset = 0, bytes_per_element = 0;
    const char *string = NULL;
    JSValue buffer = JS_UNDEFINED;
    if (JS_IsString(argv[0])) {
        string = JS_ToCStringLen(ctx, &length, argv[0]);
        if (!string) return JS_EXCEPTION;
        bytes = (const uint8_t *)string;
    } else {
        buffer = JS_GetTypedArrayBuffer(ctx, argv[0], &offset, &length, &bytes_per_element);
        if (JS_IsException(buffer)) return buffer;
        size_t total = 0;
        bytes = JS_GetArrayBuffer(ctx, &total, buffer);
        if (!bytes || offset + length > total) { JS_FreeValue(ctx, buffer); return JS_ThrowTypeError(ctx, "Invalid MD5 buffer"); }
        bytes += offset;
    }
    if (length > 1024 * 1024) {
        if (string) JS_FreeCString(ctx, string);
        JS_FreeValue(ctx, buffer);
        return JS_ThrowRangeError(ctx, "MD5 input exceeds 1 MiB");
    }
    jbyteArray input = (*env)->NewByteArray(env, (jsize)length);
    if (input) (*env)->SetByteArrayRegion(env, input, 0, (jsize)length, (const jbyte *)bytes);
    jclass clazz = (*env)->GetObjectClass(env, ud->host_callback_global);
    jmethodID method = clazz ? (*env)->GetMethodID(env, clazz, "md5", "([B)Ljava/lang/String;") : NULL;
    jstring digest = input && method ? (jstring)(*env)->CallObjectMethod(env, ud->host_callback_global, method, input) : NULL;
    JSValue result;
    if (host_failed(ctx, env)) result = JS_EXCEPTION;
    else {
        Utf8Text text = java_utf8(env, digest);
        result = text.data ? JS_NewStringLen(ctx, text.data, text.length) : JS_ThrowInternalError(ctx, "MD5 unavailable");
        free(text.data);
    }
    if (input) (*env)->DeleteLocalRef(env, input);
    if (clazz) (*env)->DeleteLocalRef(env, clazz);
    if (digest) (*env)->DeleteLocalRef(env, digest);
    if (string) JS_FreeCString(ctx, string);
    JS_FreeValue(ctx, buffer);
    return result;
}

static jbyteArray js_crypto_bytes(JSContext *ctx, JNIEnv *env, JSValueConst value) {
    size_t offset = 0, length = 0, element_size = 0, total = 0;
    JSValue buffer = JS_GetTypedArrayBuffer(ctx, value, &offset, &length, &element_size);
    if (JS_IsException(buffer)) return NULL;
    uint8_t *bytes = JS_GetArrayBuffer(ctx, &total, buffer);
    if (length > 1024 * 1024 || offset > total || length > total - offset || (!bytes && length)) {
        JS_FreeValue(ctx, buffer);
        JS_ThrowRangeError(ctx, "Invalid AES buffer or input exceeds 1 MiB");
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)length);
    if (result && length) (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)(bytes + offset));
    JS_FreeValue(ctx, buffer);
    return result;
}

static JSValue js_host_aes_encrypt(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    RuntimeUserData *ud = JS_GetRuntimeOpaque(JS_GetRuntime(ctx));
    JNIEnv *env = ud ? get_jni_env(ud->jvm) : NULL;
    if (!env || !ud->host_callback_global || argc < 4) return JS_ThrowTypeError(ctx, "AES requires data, mode, key and IV");
    JSValue result = JS_EXCEPTION;
    jbyteArray data = NULL, key = NULL, iv = NULL, encrypted = NULL;
    jstring mode = NULL;
    jclass clazz = NULL;
    uint8_t *output = NULL;
    data = js_crypto_bytes(ctx, env, argv[0]);
    if (!data) goto cleanup;
    mode = java_js_value(ctx, env, argv[1]);
    if (!mode) goto cleanup;
    key = js_crypto_bytes(ctx, env, argv[2]);
    if (!key) goto cleanup;
    iv = js_crypto_bytes(ctx, env, argv[3]);
    if (!iv) goto cleanup;
    clazz = (*env)->GetObjectClass(env, ud->host_callback_global);
    jmethodID method = clazz ? (*env)->GetMethodID(env, clazz, "aesEncrypt", "([BLjava/lang/String;[B[B)[B") : NULL;
    if (method) encrypted = (jbyteArray)(*env)->CallObjectMethod(env, ud->host_callback_global, method, data, mode, key, iv);
    if (host_failed(ctx, env)) goto cleanup;
    if (!encrypted) { result = JS_ThrowInternalError(ctx, "AES encryption unavailable"); goto cleanup; }
    jsize length = (*env)->GetArrayLength(env, encrypted);
    output = malloc((size_t)length);
    if (!output) { result = JS_ThrowOutOfMemory(ctx); goto cleanup; }
    (*env)->GetByteArrayRegion(env, encrypted, 0, length, (jbyte *)output);
    if (host_failed(ctx, env)) goto cleanup;
    result = JS_NewArrayBufferCopy(ctx, output, (size_t)length);
cleanup:
    free(output);
    if (data) (*env)->DeleteLocalRef(env, data);
    if (key) (*env)->DeleteLocalRef(env, key);
    if (iv) (*env)->DeleteLocalRef(env, iv);
    if (mode) (*env)->DeleteLocalRef(env, mode);
    if (encrypted) (*env)->DeleteLocalRef(env, encrypted);
    if (clazz) (*env)->DeleteLocalRef(env, clazz);
    host_failed(ctx, env);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeCreateRuntime(JNIEnv *env, jobject thiz) {
    JSRuntime *rt = JS_NewRuntime();
    if (!rt) return 0;

    RuntimeUserData *ud = (RuntimeUserData *)calloc(1, sizeof(RuntimeUserData));
    if (!ud) { JS_FreeRuntime(rt); return 0; }
    (*env)->GetJavaVM(env, &ud->jvm);
    ud->next_req_id = 1000;
    ud->rejection_promise = ud->rejection_reason = JS_UNDEFINED;
    ud->timeout_ms = 2000; // default 2 seconds execution timeout
    ud->start_time_ms = get_time_ms();
    JS_SetRuntimeOpaque(rt, ud);
    JS_SetInterruptHandler(rt, js_interrupt_handler, ud);
    JS_SetHostPromiseRejectionTracker(rt, promise_rejected, ud);

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
        ud->start_time_ms = get_time_ms();
        JS_UpdateStackTop(rt);
        if (ud->host_callback_global) {
            (*env)->DeleteGlobalRef(env, ud->host_callback_global);
        }
        ud->host_callback_global = (*env)->NewGlobalRef(env, callback);
    }

    JSValue global_obj = JS_GetGlobalObject(ctx);

    JS_SetPropertyStr(ctx, global_obj, "__host_md5", JS_NewCFunction(ctx, js_host_md5, "md5", 1));
    JS_SetPropertyStr(ctx, global_obj, "__host_aesEncrypt", JS_NewCFunction(ctx, js_host_aes_encrypt, "aesEncrypt", 4));

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
        "        md5: function(str) { return globalThis.__host_md5(str); },\n"
        "        aesEncrypt: function(data, mode, key, iv) {\n"
        "            var from = globalThis.lx.utils.buffer.from;\n"
        "            return new Uint8Array(globalThis.__host_aesEncrypt(from(data), mode, from(key), from(iv || '')));\n"
        "        },\n"
        "        randomBytes: function(size) {\n"
        "            var arr = new Uint8Array(size);\n"
        "            for (var i = 0; i < size; i++) arr[i] = Math.floor(Math.random() * 256);\n"
        "            return arr;\n"
        "        }\n"
        "    }\n"
        "};\n";

    JSValue bval = JS_Eval(ctx, bootstrap_js, strlen(bootstrap_js), "<bootstrap>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(bval)) {
        throw_js_exception(ctx, env);
        JS_FreeValue(ctx, bval);
        JS_FreeValue(ctx, global_obj);
        return;
    }
    JS_FreeValue(ctx, bval);

    JS_FreeValue(ctx, global_obj);
}

JNIEXPORT jstring JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeEvaluate(JNIEnv *env, jobject thiz, jlong ctx_ptr, jstring script, jstring filename) {
    JSContext *ctx = (JSContext *)ctx_ptr;
    if (!ctx) return NULL;
    JSRuntime *rt = JS_GetRuntime(ctx);
    RuntimeUserData *ud = JS_GetRuntimeOpaque(rt);
    if (ud) ud->start_time_ms = get_time_ms();
    JS_UpdateStackTop(rt);
    Utf8Text source = java_utf8(env, script), file = java_utf8(env, filename);
    if (!source.data || !file.data || (*env)->ExceptionCheck(env)) { free(source.data); free(file.data); return NULL; }
    JSValue value = JS_Eval(ctx, source.data, source.length, file.data, JS_EVAL_TYPE_GLOBAL);
    free(source.data); free(file.data);
    if (JS_IsException(value)) { throw_js_exception(ctx, env); JS_FreeValue(ctx, value); return NULL; }
    JSValue json = JS_JSONStringify(ctx, value, JS_UNDEFINED, JS_UNDEFINED);
    jstring result = NULL;
    if (JS_IsException(json)) throw_js_exception(ctx, env);
    else if (!JS_IsUndefined(json)) result = java_js_value(ctx, env, json);
    JS_FreeValue(ctx, json);
    JS_FreeValue(ctx, value);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeExecutePendingJobs(JNIEnv *env, jobject thiz, jlong rt_ptr) {
    JSRuntime *rt = (JSRuntime *)rt_ptr;
    if (!rt) return 0;
    JS_UpdateStackTop(rt);
    RuntimeUserData *ud = JS_GetRuntimeOpaque(rt);
    if (ud) ud->start_time_ms = get_time_ms();
    JSContext *ctx = NULL;
    int count = 0, status = 0;
    while ((status = JS_ExecutePendingJob(rt, &ctx)) > 0) {
        if (++count >= 1000) {
            const char *message = "QuickJS pending job budget exceeded";
            throw_java_message(env, message, strlen(message));
            return count;
        }
    }
    if (status < 0 && ctx) throw_js_exception(ctx, env);
    else if (ud && ud->rejection_ctx) {
        ctx = ud->rejection_ctx;
        JS_Throw(ctx, JS_DupValue(ctx, ud->rejection_reason));
        clear_rejection(ud);
        throw_js_exception(ctx, env);
    }
    return count;
}

JNIEXPORT void JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeResolveLxRequestCallback(JNIEnv *env, jobject thiz, jlong ctx_ptr, jlong req_id, jboolean is_err, jstring data_json) {
    JSContext *ctx = (JSContext *)ctx_ptr;
    if (!ctx) return;
    JSRuntime *rt = JS_GetRuntime(ctx);
    JS_UpdateStackTop(rt);
    RuntimeUserData *ud = JS_GetRuntimeOpaque(rt);
    if (ud) ud->start_time_ms = get_time_ms();
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue callbacks = JS_GetPropertyStr(ctx, global, "__lx_callbacks");
    char key[32];
    snprintf(key, sizeof(key), "%lld", (long long)req_id);
    JSValue callback = JS_GetPropertyStr(ctx, callbacks, key);
    if (JS_IsFunction(ctx, callback)) {
        // Remove the property before invoking it, including the exception path.
        delete_callback(ctx, callbacks, key);
        Utf8Text text = java_utf8(env, data_json);
        JSValue args[2] = { JS_UNDEFINED, JS_UNDEFINED };
        if (is_err) {
            args[0] = JS_NewError(ctx);
            JS_SetPropertyStr(ctx, args[0], "message", text.data ? JS_NewStringLen(ctx, text.data, text.length) : JS_NewString(ctx, "Request error"));
        } else {
            args[0] = JS_NULL;
            args[1] = text.data ? JS_ParseJSON(ctx, text.data, text.length, "<json>") : JS_NULL;
        }
        free(text.data);
        if (JS_IsException(args[1])) throw_js_exception(ctx, env);
        else if (!(*env)->ExceptionCheck(env)) {
            JSValue result = JS_Call(ctx, callback, JS_UNDEFINED, 2, args);
            if (JS_IsException(result)) throw_js_exception(ctx, env);
            JS_FreeValue(ctx, result);
        }
        JS_FreeValue(ctx, args[0]);
        JS_FreeValue(ctx, args[1]);
    }
    JS_FreeValue(ctx, callback);
    JS_FreeValue(ctx, callbacks);
    JS_FreeValue(ctx, global);
}

JNIEXPORT void JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeDestroyContext(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    JSContext *ctx = (JSContext *)ctx_ptr;
    if (ctx) {
        RuntimeUserData *ud = JS_GetRuntimeOpaque(JS_GetRuntime(ctx));
        if (ud && ud->rejection_ctx == ctx) clear_rejection(ud);
        JS_FreeContext(ctx);
    }
}

JNIEXPORT jlong JNICALL
Java_com_pickaudio_source_QuickJsNativeBridge_nativeMemoryUsage(JNIEnv *env, jobject thiz, jlong rt_ptr) {
    JSRuntime *rt = (JSRuntime *)rt_ptr;
    if (!rt) return 0;
    JS_RunGC(rt);
    JSMemoryUsage usage;
    JS_ComputeMemoryUsage(rt, &usage);
    return (jlong)usage.memory_used_size;
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
        }
        JS_FreeRuntime(rt);
        free(ud);
    }
}
