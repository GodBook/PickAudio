package com.pickaudio.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pickaudio.data.model.Track
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.data.repository.LibraryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

data class LibraryQuery(val query: String = "", val view: String = "全部", val group: String? = null,
    val sort: String = "最近添加", val filter: String = "全部")
data class LibraryGroup(val key: String, val label: String, val count: Int, val preview: String)
data class LibraryViewState(val allTracks: List<Track> = emptyList(), val tracks: List<Track> = emptyList(),
    val groups: List<LibraryGroup> = emptyList(), val error: String? = null)
data class IndexedTrack(val track: Track, val title: String, val artist: String, val album: String, val folder: String)
data class LibraryIndex(val tracks: List<Track>, val rows: List<IndexedTrack>)
fun buildLibraryIndex(tracks: List<Track>) = LibraryIndex(tracks, tracks.map {
    IndexedTrack(it, it.title.lowercase(Locale.ROOT), it.artist.lowercase(Locale.ROOT),
        it.album.lowercase(Locale.ROOT), it.folderName.lowercase(Locale.ROOT))
})

/** Pure production filtering/grouping; it runs away from the main thread in the ViewModel. */
fun buildLibraryView(all: List<Track>, options: LibraryQuery, recentIds: List<String>): LibraryViewState =
    buildLibraryView(buildLibraryIndex(all), options, recentIds)

fun buildLibraryView(index: LibraryIndex, options: LibraryQuery, recentIds: List<String>): LibraryViewState {
    val all = index.tracks
    val query = options.query.lowercase(Locale.ROOT)
    val recent = recentIds.withIndex().associate { it.value to it.index }
    val matching = index.rows.filter { row ->
        val track = row.track
        (query.isBlank() || row.title.contains(query) || row.artist.contains(query) || row.album.contains(query) || row.folder.contains(query)) &&
            when (options.filter) {
                "本地" -> track.localUri != null; "已下载" -> track.sourceType == "DOWNLOADED"
                "在线" -> track.localUri == null && track.platform != null; "待修复" -> track.repairReason != null
                else -> true
            } && (options.view != "最近播放" || track.id in recent)
    }.map { it.track }
    fun key(track: Track): String = when (options.view) {
        "歌手" -> track.artist
        "专辑" -> "${track.album}\u0000${track.artist}"
        "文件夹" -> track.folderId.ifBlank { track.folderName.ifBlank { "unclassified:${track.localUri != null}" } }
        else -> ""
    }
    fun label(track: Track): String = when (options.view) {
        "歌手" -> track.artist
        "专辑" -> "${track.album} · ${track.artist}"
        "文件夹" -> track.folderName.ifBlank { if (track.localUri != null) "未分类本地文件" else "在线或待关联文件" }
        else -> ""
    }
    val grouped = if (options.view in listOf("歌手", "专辑", "文件夹")) matching.groupBy(::key) else emptyMap()
    val groups = grouped.map { (id, tracks) -> LibraryGroup(id, label(tracks.first()), tracks.size, tracks.first().title) }
        .sortedBy { it.label.lowercase(Locale.ROOT) }
    val selected = if (options.group == null) matching else grouped[options.group].orEmpty()
    val sorted = if (options.view == "最近播放") selected.sortedBy { recent[it.id] ?: Int.MAX_VALUE } else when (options.sort) {
        "歌曲名称" -> selected.map { it to it.title.lowercase(Locale.ROOT) }.sortedBy { it.second }.map { it.first }
        "歌手名称" -> selected.map { it to it.artist.lowercase(Locale.ROOT) }.sortedBy { it.second }.map { it.first }
        "时长" -> selected.sortedByDescending { it.durationMs }
        else -> selected.sortedByDescending { it.createdAt }
    }
    return LibraryViewState(all, sorted, groups)
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryBrowserViewModel(repository: LibraryRepository, preferences: UserPreferences) : ViewModel() {
    private val options = MutableStateFlow(LibraryQuery())
    private val indexed = repository.getAllTracks().mapLatest { tracks ->
        withContext(Dispatchers.Default) { buildLibraryIndex(tracks) }
    }
    val state = combine(indexed, preferences.recentTrackIds, options.debounce(150)) { index, recent, query ->
        Triple(index, recent, query)
    }.mapLatest { (index, recent, query) -> withContext(Dispatchers.Default) { buildLibraryView(index, query, recent) } }
        .catch { error -> emit(LibraryViewState(error = error.message ?: "曲库暂时无法读取")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryViewState())
    fun browse(query: LibraryQuery) { options.value = query }
    class Factory(private val repository: LibraryRepository, private val preferences: UserPreferences) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryBrowserViewModel(repository, preferences) as T
    }
}
