package com.pickaudio.ui.screens

import androidx.compose.ui.res.stringResource

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val history by userPreferences.searchHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val current by playbackCoordinator.currentTrack.collectAsStateWithLifecycle()
    val playing by playbackCoordinator.isPlaying.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selecting by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = selecting && !com.pickaudio.LocalPlayerOverlayVisible.current) { selecting = false; selectedIds = emptySet() }
    var playlistTracks by remember { mutableStateOf<List<Track>?>(null) }
    var downloadTracks by remember { mutableStateOf<List<Track>?>(null) }
    val favorites by playlistRepository.favoriteIds.collectAsStateWithLifecycle(initialValue = emptyList())
    val onlineIds by playlistRepository.onlineIds.collectAsStateWithLifecycle(initialValue = emptyMap())
    val favoriteIds = remember(favorites) { favorites.toSet() }
    fun canonical(item: SearchSongItem): Track {
        val track = item.toTrack()
        val id = onlineIds[item.platform to item.songId] ?: track.id
        return track.copy(id = id, isFavorite = id in favoriteIds)
    }
    val allTracks = searchStateManager.searchResults.map(::canonical)
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
        TopAppBar(title = { Text(stringResource(com.pickaudio.R.string.ui_searchscreen_001), fontWeight = FontWeight.SemiBold) }, actions = {
            TextButton(onClick = { selecting = !selecting; selectedIds = emptySet() }) { Text(if (selecting) "完成" else "多选") }
            IconButton(onClick = onNavigateToDownload) { Icon(Icons.Default.Download, contentDescription = stringResource(com.pickaudio.R.string.ui_libraryscreen_019)) }
            IconButton(onClick = onNavigateToSourceManager) { Icon(Icons.Default.Tune, contentDescription = stringResource(com.pickaudio.R.string.ui_searchscreen_009)) }
        })
    }, snackbarHost = { SnackbarHost(snack) }, modifier = modifier.fillMaxSize()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchStateManager.query, onValueChange = { searchStateManager.query = it },
                placeholder = { Text(stringResource(com.pickaudio.R.string.ui_searchscreen_002), maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (searchStateManager.query.isNotEmpty()) IconButton(onClick = { searchStateManager.clear() }) { Icon(Icons.Default.Close, stringResource(com.pickaudio.R.string.ui_libraryscreen_020)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(searchStateManager.query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Platform.entries.forEach { platform ->
                    FilterChip(selected = searchStateManager.selectedPlatform == platform, onClick = {
                        searchStateManager.selectPlatform(platform)
                        if (searchStateManager.query.isNotBlank() && searchStateManager.query.trim() != searchStateManager.submittedQuery)
                            search(searchStateManager.query)
                    }, label = { Text(platform.displayName) })
                }
                TextButton(onClick = { search(searchStateManager.query) }, enabled = searchStateManager.query.isNotBlank()) { Text(stringResource(com.pickaudio.R.string.ui_mainactivity_003)) }
            }
            if (selecting) {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { selectedIds = allTracks.map { it.id }.toSet() }) { Text("全选 (${selectedIds.size})") }
                    TextButton(onClick = { playlistTracks = selected }, enabled = selected.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.action_add_playlist)) }
                    TextButton(onClick = { downloadTracks = selected }, enabled = selected.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.action_download)) }
                    TextButton(onClick = { selected.forEach { playbackCoordinator.addToQueue(it) }; message("已加入队尾") }, enabled = selected.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.action_add_queue)) }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (searchStateManager.submittedQuery.isEmpty()) {
                    item {
                        Column(Modifier.padding(20.dp)) {
                            Text(stringResource(com.pickaudio.R.string.ui_searchscreen_003), style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                history.forEach { value ->
                                    InputChip(false, onClick = { search(value) }, label = { Text(value) }, trailingIcon = {
                                        IconButton(onClick = { scope.launch { userPreferences.removeSearchHistory(value) } }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, stringResource(com.pickaudio.R.string.ui_searchscreen_010)) }
                                    })
                                }
                            }
                            if (history.isEmpty()) Text(stringResource(com.pickaudio.R.string.ui_searchscreen_004), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else TextButton(onClick = { scope.launch { userPreferences.clearSearchHistory() } }) { Text(stringResource(com.pickaudio.R.string.ui_searchscreen_005)) }
                        }
                    }
                }
                searchStateManager.platformStates.filterKeys { searchStateManager.selectedPlatform == Platform.ALL || it == searchStateManager.selectedPlatform.id }.forEach { (platform, state) ->
                    item(key = "header_${platform}") {
                        Text("${Platform.fromId(platform).displayName} · ${state.items.size} 首", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
                    }
                    items(if (searchStateManager.selectedPlatform == Platform.ALL) state.items.take(5) else state.items, key = { "${it.platform}_${it.songId}" }) { item ->
                        val track = canonical(item)
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
                            if (searchStateManager.selectedPlatform == Platform.ALL && state.items.size > 5)
                                TextButton(onClick = { searchStateManager.selectPlatform(Platform.fromId(platform)) }) {
                                    Text("查看${Platform.fromId(platform).displayName}全部 ${state.items.size} 首")
                                }
                            if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在加载${Platform.fromId(platform).displayName}…") }
                            else if (state.error != null) {
                                Text(state.error, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { searchStateManager.loadMore(platform) }) { Text(stringResource(com.pickaudio.R.string.ui_searchscreen_006)) }
                            } else if (state.items.isEmpty()) Text(stringResource(com.pickaudio.R.string.ui_searchscreen_007))
                            else if (state.hasMore) OutlinedButton(onClick = { searchStateManager.loadMore(platform) }) { Text("加载更多${Platform.fromId(platform).displayName}结果") }
                            else Text(stringResource(com.pickaudio.R.string.ui_searchscreen_008), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    playlistTracks?.let { PlaylistPickerDialog(it, playlistRepository, { playlistTracks = null }, ::message) }
    downloadTracks?.let { DownloadQualityDialog(it, downloadCoordinator, sourceManager, userPreferences, { downloadTracks = null }, onNavigateToSourceManager, { message(it.summary) }) }
}
