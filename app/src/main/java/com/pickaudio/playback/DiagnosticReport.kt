package com.pickaudio.playback

import android.content.Context
import android.net.Uri
import android.os.Build
import com.pickaudio.BuildConfig
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.preferences.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import android.app.ActivityManager

/** Explicit local export, with aggregate counters and no song, URI, script or credential data. */
object DiagnosticReport {
    suspend fun export(context: Context, database: PickAudioDatabase, preferences: UserPreferences, target: Uri) = withContext(Dispatchers.IO) {
        val metrics = PlaybackDiagnostics.metrics.value
        val report = JSONObject().apply {
            put("version", BuildConfig.VERSION_NAME); put("versionCode", BuildConfig.VERSION_CODE)
            put("sdk", Build.VERSION.SDK_INT); put("schema", database.openHelper.readableDatabase.version)
            put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
            // OS-retained exit categories survive process death; do not export traces/URLs.
            put("recentProcessExits", JSONArray().apply {
                context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 5).forEach { exit ->
                        put(JSONObject().apply {
                            put("timestamp", exit.timestamp); put("reason", exit.reason)
                            put("status", exit.status); put("importance", exit.importance)
                            put("pssKiB", exit.pss); put("rssKiB", exit.rss)
                        })
                    }
            })
            put("tracks", database.trackDao().getTrackCount())
            put("cacheLimitMiB", preferences.audioCacheMegabytes.first())
            put("cacheBytes", AudioCacheManager.size(context))
            put("plays", metrics.plays); put("lastFirstAudioMs", metrics.firstAudioMs ?: JSONObject.NULL)
            put("bufferCount", metrics.buffers); put("resolveFailures", metrics.resolveFailures)
            put("lastErrorCategory", metrics.lastError ?: JSONObject.NULL)
            put("servedAudioBytes", metrics.servedBytes); put("cachedAudioBytes", metrics.cachedBytes)
            put("downloadStates", JSONObject(database.downloadDao().getAllTasksSync().groupingBy { it.status }.eachCount()))
        }
        context.contentResolver.openOutputStream(target, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(report.toString(2)) }
            ?: error("无法写入诊断文件")
    }
}
