package com.pickaudio.ui.screens

import androidx.compose.runtime.*
import com.pickaudio.data.model.Platform
import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.online.NetEaseSearchAdapter
import com.pickaudio.online.QqMusicSearchAdapter
import com.pickaudio.online.SearchPage
import com.pickaudio.online.SEARCH_PAGE_SIZE
import kotlinx.coroutines.*

data class PlatformSearchState(
    val items: List<SearchSongItem> = emptyList(), val page: Int = 0,
    val loading: Boolean = false, val error: String? = null, val hasMore: Boolean = true
)

class SearchStateManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val fetch: (suspend (String, String, Int) -> List<SearchSongItem>)? = null
) {
    var query by mutableStateOf("")
    var selectedPlatform by mutableStateOf(Platform.ALL)
    var submittedQuery by mutableStateOf("")
        private set
    var platformStates by mutableStateOf<Map<String, PlatformSearchState>>(emptyMap())
        private set
    val isSearching: Boolean get() = platformStates.values.any { it.loading && it.items.isEmpty() }
    val searchResults: List<SearchSongItem> get() = platformStates.values.flatMap { it.items }
    private var generation = 0L
    private val jobs = mutableMapOf<String, Job>()
    fun selectPlatform(platform: Platform) {
        selectedPlatform = platform
        if (submittedQuery.isBlank()) return
        val platforms = if (platform == Platform.ALL) listOf("wy", "tx") else listOf(platform.id)
        platforms.filter { it !in platformStates }.forEach {
            platformStates = platformStates + (it to PlatformSearchState())
            loadMore(it)
        }
    }

    fun submit(keyword: String) {
        val value = keyword.trim()
        if (value.isEmpty()) return
        clearRequests()
        query = value
        submittedQuery = value
        val platforms = if (selectedPlatform == Platform.ALL) listOf("wy", "tx") else listOf(selectedPlatform.id)
        platformStates = platforms.associateWith { PlatformSearchState() }
        platforms.forEach { loadMore(it) }
    }

    fun loadMore(platform: String) {
        val state = platformStates[platform] ?: return
        if (state.loading || !state.hasMore) return
        val requestGeneration = generation
        val keyword = submittedQuery
        platformStates = platformStates + (platform to state.copy(loading = true, error = null))
        jobs[platform] = scope.launch {
            try {
                val page = state.page + 1
                val result = fetch?.let {
                    val items = it(platform, keyword, page)
                    SearchPage(items, if (items.size >= SEARCH_PAGE_SIZE) page + 1 else null)
                } ?: if (platform == "wy") NetEaseSearchAdapter.searchPage(keyword, page) else QqMusicSearchAdapter.searchPage(keyword, page)
                val items = result.items
                if (requestGeneration != generation) return@launch
                val previousIds = state.items.map { it.songId }.toSet()
                val added = items.any { it.songId !in previousIds }
                platformStates = platformStates + (platform to state.copy(
                    items = (state.items + items).distinctBy { it.songId }, page = state.page + 1,
                    hasMore = result.nextPage != null && added, loading = false
                ))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (requestGeneration == generation) platformStates = platformStates + (platform to state.copy(
                    error = "${Platform.fromId(platform).displayName}搜索失败：${when (e) {
                        is java.net.UnknownHostException -> "网络无法连接"
                        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "请求超时"
                        else -> e.message ?: "服务暂不可用"
                    }}，可以重试此页", loading = false
                ))
            }
        }
    }

    private fun clearRequests() {
        generation++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    fun clear() {
        clearRequests()
        query = ""
        submittedQuery = ""
        platformStates = emptyMap()
    }
}
