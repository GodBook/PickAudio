package com.pickaudio.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pickaudio.data.model.Quality
import com.pickaudio.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "pickaudio_settings")

class UserPreferences(private val context: Context) {
    private val gson = Gson()

    companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_FILTER_SHORT_AUDIO = booleanPreferencesKey("filter_short_audio")
        val KEY_DEFAULT_ONLINE_QUALITY = stringPreferencesKey("default_online_quality")
        val KEY_DEFAULT_DOWNLOAD_QUALITY = stringPreferencesKey("default_download_quality")
        val KEY_WIFI_ONLY_DOWNLOAD = booleanPreferencesKey("wifi_only_download")
        val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_THEME] ?: ThemeMode.SYSTEM.name
        try { ThemeMode.valueOf(name) } catch (e: Exception) { ThemeMode.SYSTEM }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME] = mode.name }
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
