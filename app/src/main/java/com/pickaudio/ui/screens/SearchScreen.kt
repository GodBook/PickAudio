package com.pickaudio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pickaudio.data.model.*
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.data.repository.PlaylistRepository
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.playback.PlaybackCoordinator
import com.pickaudio.source.LxSourceManager
import com.pickaudio.ui.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    userPreferences: UserPreferences, playbackCoordinator: PlaybackCoordinator,
    downloadCoordinator: DownloadCoordinator, playlistRepository: PlaylistRepository,
    sourceManager: LxSourceManager, searchStateManager: SearchStateManager,
    onOpenPlayer: () -> Unit = {}, onNavigateToSourceManager: () -> Unit, modifier: Modifier = Modifier, onNavigateToDownload: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val history by userPreferences.searchHistory.collectAsState(initial = emptyList())
    val current by playbackCoordinator.currentTrack.collectAsState()
    val playing by playbackCoordinator.isPlaying.collectAsState()
    val snack = remember { SnackbarHostState() }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selecting by remember { mutableStateOf(false) }
    var playlistTracks by remember { mutableStateOf<List<Track>?>(null) }
    var downloadTracks by remember { mutableStateOf<List<Track>?>(null) }
    val favorites by playlistRepository.getFavoriteTracks().collectAsState(initial = emptyList())
    val favoriteIds = favorites.map { it.id }.toSet()
    val allTracks = searchStateManager.searchResults.map { it.toTrack().copy(isFavorite = it.toTrack().id in favoriteIds) }
    val selected = allTracks.filter { it.id in selectedIds }
    fun select(id: String) { selecting = true; selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun search(keyword: String) {
        if (keyword.isBlank()) return
        selectedIds = emptySet(); selecting = false
        keyboard?.hide()
        searchStateManager.submit(keyword)
        scope.launch { userPreferences.addSearchHistory(keyword.trim()) }
    }
    fun message(text: String) { scope.launch { snack.showSnackbar(text) } }

    Scaffold(topBar = {
        TopAppBar(title = { Text("在线搜索", fontWeight = FontWeight.SemiBold) }, actions = {
            TextButton(onClick = { selecting = !selecting; selectedIds = emptySet() }) { Text(if (selecting) "完成" else "多选") }
            IconButton(onClick = onNavigateToDownload) { Icon(Icons.Default.Download, contentDescription = "下载管理") }
            IconButton(onClick = onNavigateToSourceManager) { Icon(Icons.Default.Tune, contentDescription = "配置音乐源") }
        })
    }, snackbarHost = { SnackbarHost(snack) }, modifier = modifier.fillMaxSize()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchStateManager.query, onValueChange = { searchStateManager.query = it },
                placeholder = { Text("搜索歌曲、歌手或专辑", maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (searchStateManager.query.isNotEmpty()) IconButton(onClick = { searchStateManager.clear() }) { Icon(Icons.Default.Close, "清除搜索") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(searchStateManager.query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Platform.entries.forEach { platform ->
                    FilterChip(selected = searchStateManager.selectedPlatform == platform, onClick = {
                        searchStateManager.selectedPlatform = platform
                        if (searchStateManager.query.isNotBlank()) search(searchStateManager.query)
                    }, label = { Text(platform.displayName) })
                }
                TextButton(onClick = { search(searchStateManager.query) }, enabled = searchStateManager.query.isNotBlank()) { Text("搜索") }
            }
            if (selecting) {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { selectedIds = allTracks.map { it.id }.toSet() }) { Text("全选 (${selectedIds.size})") }
                    TextButton(onClick = { playlistTracks = selected }, enabled = selected.isNotEmpty()) { Text("加入歌单") }
                    TextButton(onClick = { downloadTracks = selected }, enabled = selected.isNotEmpty()) { Text("下载") }
                    TextButton(onClick = { selected.forEach { playbackCoordinator.addToQueue(it) }; message("已加入队尾") }, enabled = selected.isNotEmpty()) { Text("加入队列") }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (searchStateManager.submittedQuery.isEmpty()) {
                    item {
                        Column(Modifier.padding(20.dp)) {
                            Text("搜索历史", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                history.forEach { value ->
                                    InputChip(false, onClick = { search(value) }, label = { Text(value) }, trailingIcon = {
                                        IconButton(onClick = { scope.launch { userPreferences.removeSearchHistory(value) } }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "删除历史") }
                                    })
                                }
                            }
                            if (history.isEmpty()) Text("输入歌名后搜索，两个平台会分别显示结果。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else TextButton(onClick = { scope.launch { userPreferences.clearSearchHistory() } }) { Text("清空历史") }
                        }
                    }
                }
                searchStateManager.platformStates.forEach { (platform, state) ->
                    item(key = "header_${platform}") {
                        Text("${Platform.fromId(platform).displayName} · ${state.items.size} 首", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
                    }
                    items(state.items, key = { "${it.platform}_${it.songId}" }) { item ->
                        val track = item.toTrack().copy(isFavorite = item.toTrack().id in favoriteIds)
                        MusicTrackRow(track, isCurrent = current?.id == track.id, isPlaying = playing,
                            selecting = selecting, selected = track.id in selectedIds, onSelect = { select(track.id) },
                            onClick = { playbackCoordinator.addOrPlayTrack(track); onOpenPlayer() },
                            onFavorite = { scope.launch {
                                if (track.isFavorite) { playlistRepository.toggleFavorite(track.id); message("已取消喜欢") }
                                else { playlistRepository.addTracks(com.pickaudio.data.db.PickAudioDatabase.FAVORITE_PLAYLIST_ID, listOf(track)); message("已加入我喜欢") }
                            } },
                            actions = listOf(
                                TrackMenuAction("下一首播放") { playbackCoordinator.playNext(track); message("已加入下一首") },
                                TrackMenuAction("添加到队尾") { playbackCoordinator.addToQueue(track); message("已加入队尾") },
                                TrackMenuAction("加入歌单") { playlistTracks = listOf(track) },
                                TrackMenuAction("下载歌曲") { downloadTracks = listOf(track) }
                            ))
                    }
                    item(key = "status_${platform}") {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在加载${Platform.fromId(platform).displayName}…") }
                            else if (state.error != null) {
                                Text(state.error, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { searchStateManager.loadMore(platform) }) { Text("重试此平台") }
                            } else if (state.items.isEmpty()) Text("没有找到匹配歌曲，可尝试更短的歌名或歌手名。")
                            else if (state.hasMore) OutlinedButton(onClick = { searchStateManager.loadMore(platform) }) { Text("加载更多${Platform.fromId(platform).displayName}结果") }
                            else Text("已显示全部结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    playlistTracks?.let { PlaylistPickerDialog(it, playlistRepository, { playlistTracks = null }, { message("已加入「$it」") }) }
    downloadTracks?.let { DownloadQualityDialog(it, downloadCoordinator, sourceManager, userPreferences, { downloadTracks = null }, onNavigateToSourceManager, { message("已加入下载，进度可在下载管理查看") }) }
}
