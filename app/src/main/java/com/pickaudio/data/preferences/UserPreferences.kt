package com.pickaudio.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pickaudio.data.model.Quality
import com.pickaudio.data.model.ThemeMode
import com.pickaudio.data.model.ThemeColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

val Context.dataStore by preferencesDataStore(name = "pickaudio_settings")

class UserPreferences(private val context: Context) {
    private val gson = Gson()

    companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_THEME_COLOR = stringPreferencesKey("theme_color")
        val KEY_FILTER_SHORT_AUDIO = booleanPreferencesKey("filter_short_audio")
        val KEY_DEFAULT_ONLINE_QUALITY = stringPreferencesKey("default_online_quality")
        val KEY_DEFAULT_DOWNLOAD_QUALITY = stringPreferencesKey("default_download_quality")
        val KEY_WIFI_ONLY_DOWNLOAD = booleanPreferencesKey("wifi_only_download")
        val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
        val KEY_RECENT_TRACKS = stringPreferencesKey("recent_track_ids")
        val KEY_LYRIC_SIZE = intPreferencesKey("lyric_font_size")
        val KEY_LYRIC_TRANSLATION = booleanPreferencesKey("lyric_translation")
        val KEY_AUDIO_CACHE_MB = intPreferencesKey("audio_cache_mb")
        private val KEY_RESTORE_SESSION = stringPreferencesKey("last_restore_session")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_THEME] ?: ThemeMode.SYSTEM.name
        try { ThemeMode.valueOf(name) } catch (e: Exception) { ThemeMode.SYSTEM }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME] = mode.name }
    }

    val themeColor: Flow<ThemeColor> = context.dataStore.data.map { prefs ->
        ThemeColor.fromName(prefs[KEY_THEME_COLOR])
    }

    suspend fun setThemeColor(color: ThemeColor) {
        context.dataStore.edit { it[KEY_THEME_COLOR] = color.name }
    }

    val filterShortAudio: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_FILTER_SHORT_AUDIO] ?: true // default true: filter <30s
    }

    suspend fun setFilterShortAudio(enabled: Boolean) {
        context.dataStore.edit { it[KEY_FILTER_SHORT_AUDIO] = enabled }
    }

    val defaultOnlineQuality: Flow<Quality> = context.dataStore.data.map { prefs ->
        Quality.fromValue(prefs[KEY_DEFAULT_ONLINE_QUALITY] ?: "128k")
    }

    suspend fun setDefaultOnlineQuality(quality: Quality) {
        context.dataStore.edit { it[KEY_DEFAULT_ONLINE_QUALITY] = quality.value }
    }

    val defaultDownloadQuality: Flow<Quality> = context.dataStore.data.map { prefs ->
        Quality.fromValue(prefs[KEY_DEFAULT_DOWNLOAD_QUALITY] ?: "320k")
    }

    suspend fun setDefaultDownloadQuality(quality: Quality) {
        context.dataStore.edit { it[KEY_DEFAULT_DOWNLOAD_QUALITY] = quality.value }
    }

    val wifiOnlyDownload: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_WIFI_ONLY_DOWNLOAD] ?: true
    }

    suspend fun setWifiOnlyDownload(enabled: Boolean) {
        context.dataStore.edit { it[KEY_WIFI_ONLY_DOWNLOAD] = enabled }
    }

    val recentTrackIds: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_RECENT_TRACKS]?.split('|')?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun recordPlayed(trackId: String) {
        context.dataStore.edit { prefs ->
            val recent = prefs[KEY_RECENT_TRACKS]?.split('|').orEmpty().filter { it != trackId && it.isNotBlank() }
            prefs[KEY_RECENT_TRACKS] = (listOf(trackId) + recent).take(50).joinToString("|")
        }
    }

    val lyricFontSize: Flow<Int> = context.dataStore.data.map { (it[KEY_LYRIC_SIZE] ?: 18).coerceIn(14, 28) }
    val showLyricTranslation: Flow<Boolean> = context.dataStore.data.map { it[KEY_LYRIC_TRANSLATION] ?: true }
    val audioCacheMegabytes: Flow<Int> = context.dataStore.data.map { (it[KEY_AUDIO_CACHE_MB] ?: 256).takeIf { n -> n in listOf(64, 256, 1024) } ?: 256 }
    suspend fun setAudioCacheMegabytes(value: Int) { require(value in listOf(64, 256, 1024)); context.dataStore.edit { it[KEY_AUDIO_CACHE_MB] = value } }
    suspend fun setLyricFontSize(value: Int) { context.dataStore.edit { it[KEY_LYRIC_SIZE] = value.coerceIn(14, 28) } }
    suspend fun setShowLyricTranslation(value: Boolean) { context.dataStore.edit { it[KEY_LYRIC_TRANSLATION] = value } }

    suspend fun exportSettings(): Map<String, String> = context.dataStore.data.map { prefs ->
        mapOf(
            "theme" to (prefs[KEY_THEME] ?: ThemeMode.SYSTEM.name),
            "themeColor" to ThemeColor.fromName(prefs[KEY_THEME_COLOR]).name,
            "filterShortAudio" to (prefs[KEY_FILTER_SHORT_AUDIO] ?: true).toString(),
            "onlineQuality" to (prefs[KEY_DEFAULT_ONLINE_QUALITY] ?: "128k"),
            "downloadQuality" to (prefs[KEY_DEFAULT_DOWNLOAD_QUALITY] ?: "320k"),
            "wifiOnly" to (prefs[KEY_WIFI_ONLY_DOWNLOAD] ?: true).toString(),
            "lyricSize" to (prefs[KEY_LYRIC_SIZE] ?: 18).toString(),
            "translation" to (prefs[KEY_LYRIC_TRANSLATION] ?: true).toString(),
            "audioCacheMb" to (prefs[KEY_AUDIO_CACHE_MB] ?: 256).toString()
        )
    }.first()

    suspend fun restoreSettings(values: Map<String, String>, restoreSessionId: String? = null) {
        context.dataStore.edit { prefs ->
            if (restoreSessionId != null && prefs[KEY_RESTORE_SESSION] == restoreSessionId) return@edit
            values["theme"]?.takeIf { name -> ThemeMode.entries.any { it.name == name } }?.let { prefs[KEY_THEME] = it }
            values["themeColor"]?.takeIf { name -> ThemeColor.entries.any { it.name == name } }?.let { prefs[KEY_THEME_COLOR] = it }
            values["filterShortAudio"]?.toBooleanStrictOrNull()?.let { prefs[KEY_FILTER_SHORT_AUDIO] = it }
            values["onlineQuality"]?.takeIf { value -> Quality.entries.any { it.value == value } }?.let { prefs[KEY_DEFAULT_ONLINE_QUALITY] = it }
            values["downloadQuality"]?.takeIf { value -> Quality.entries.any { it.value == value } }?.let { prefs[KEY_DEFAULT_DOWNLOAD_QUALITY] = it }
            values["wifiOnly"]?.toBooleanStrictOrNull()?.let { prefs[KEY_WIFI_ONLY_DOWNLOAD] = it }
            values["lyricSize"]?.toIntOrNull()?.let { prefs[KEY_LYRIC_SIZE] = it.coerceIn(14, 28) }
            values["translation"]?.toBooleanStrictOrNull()?.let { prefs[KEY_LYRIC_TRANSLATION] = it }
            values["audioCacheMb"]?.toIntOrNull()?.takeIf { it in listOf(64, 256, 1024) }?.let { prefs[KEY_AUDIO_CACHE_MB] = it }
            if (restoreSessionId != null) prefs[KEY_RESTORE_SESSION] = restoreSessionId
        }
    }

    val searchHistory: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_SEARCH_HISTORY]
        if (json.isNullOrEmpty()) emptyList()
        else {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun addSearchHistory(keyword: String) {
        if (keyword.isBlank()) return
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_SEARCH_HISTORY]
            val list = if (json.isNullOrEmpty()) mutableListOf() else {
                try {
                    val type = object : TypeToken<MutableList<String>>() {}.type
                    gson.fromJson<MutableList<String>>(json, type) ?: mutableListOf()
                } catch (e: Exception) {
                    mutableListOf()
                }
            }
            list.remove(keyword)
            list.add(0, keyword)
            if (list.size > 20) {
                val trimmed = list.take(20)
                prefs[KEY_SEARCH_HISTORY] = gson.toJson(trimmed)
            } else {
                prefs[KEY_SEARCH_HISTORY] = gson.toJson(list)
            }
        }
    }

    suspend fun removeSearchHistory(keyword: String) {
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_SEARCH_HISTORY] ?: return@edit
            val type = object : TypeToken<MutableList<String>>() {}.type
            val list = gson.fromJson<MutableList<String>>(json, type) ?: return@edit
            list.remove(keyword)
            prefs[KEY_SEARCH_HISTORY] = gson.toJson(list)
        }
    }

    suspend fun clearSearchHistory() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SEARCH_HISTORY)
        }
    }
}
