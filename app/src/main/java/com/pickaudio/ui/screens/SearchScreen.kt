package com.pickaudio.ui.screens

import androidx.compose.ui.res.stringResource

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pickaudio.data.model.*
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.data.repository.PlaylistRepository
import com.pickaudio.data.repository.LibraryRepository
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
    sourceManager: LxSourceManager, searchStateManager: SearchStateManager, libraryRepository: LibraryRepository,
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
    val libraryIds by libraryRepository.libraryTrackIds.collectAsStateWithLifecycle(initialValue = emptyList())
    val libraryIdSet = remember(libraryIds) { libraryIds.toSet() }
    val favoriteIds = remember(favorites) { favorites.toSet() }
    fun canonical(item: SearchSongItem): Track {
        val track = item.toTrack()
        val id = onlineIds[item.platform to item.songId] ?: track.id
        return track.copy(id = id, isFavorite = id in favoriteIds, isInLibrary = id in libraryIdSet)
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
    fun addToLibrary(tracks: List<Track>) {
        scope.launch {
            try {
                val added = libraryRepository.addTracks(tracks)
                snack.showSnackbar(if (added > 0) "已加入曲库 · $added 首" else "已在曲库中")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { snack.showSnackbar(e.message ?: "加入曲库失败，请重试") }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(com.pickaudio.R.string.ui_searchscreen_001), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), actions = {
            TextButton(onClick = { selecting = !selecting; selectedIds = emptySet() }) { Text(if (selecting) "完成" else "多选") }
            IconButton(onClick = onNavigateToDownload) { Icon(Icons.Default.Download, contentDescription = stringResource(com.pickaudio.R.string.ui_libraryscreen_019)) }
            IconButton(onClick = onNavigateToSourceManager) { Icon(Icons.Default.Tune, contentDescription = stringResource(com.pickaudio.R.string.ui_searchscreen_009)) }
        })
    }, snackbarHost = { SnackbarHost(snack) }, modifier = modifier.fillMaxSize()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            MusicSearchField(searchStateManager.query, { searchStateManager.query = it },
                stringResource(com.pickaudio.R.string.ui_searchscreen_002),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                onClear = { searchStateManager.clear() }, onSearch = { search(searchStateManager.query) })
            TabRow(selectedTabIndex = Platform.entries.indexOf(searchStateManager.selectedPlatform),
                containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.padding(horizontal = 20.dp)) {
                Platform.entries.forEach { platform ->
                    Tab(selected = searchStateManager.selectedPlatform == platform, onClick = {
                        searchStateManager.selectPlatform(platform)
                        if (searchStateManager.query.isNotBlank() && searchStateManager.query.trim() != searchStateManager.submittedQuery)
                            search(searchStateManager.query)
                    }, text = { Text(platform.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            if (selecting) {
                SelectionActions(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    TextButton(onClick = { selectedIds = allTracks.map { it.id }.toSet() }) { Text("全选 (${selectedIds.size})") }
                    TextButton(onClick = { playlistTracks = selected }, enabled = selected.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.action_add_playlist)) }
                    TextButton(onClick = { addToLibrary(selected) }, enabled = selected.isNotEmpty()) { Text("加入曲库") }
                    TextButton(onClick = { downloadTracks = selected }, enabled = selected.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.action_download)) }
                    TextButton(onClick = { selected.forEach { playbackCoordinator.addToQueue(it) }; message("已加入队尾") }, enabled = selected.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.action_add_queue)) }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (searchStateManager.submittedQuery.isEmpty()) {
                    item {
                        Column(Modifier.padding(20.dp)) {
                            Text("试听后，留下你喜欢的音乐", style = MaterialTheme.typography.titleMedium)
                            Text("点击歌曲试听，点击 ＋ 加入曲库。", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(stringResource(com.pickaudio.R.string.ui_searchscreen_003), style = MaterialTheme.typography.titleSmall)
                                if (history.isNotEmpty()) TextButton(onClick = { scope.launch { userPreferences.clearSearchHistory() } }) { Text(stringResource(com.pickaudio.R.string.ui_searchscreen_005)) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                history.forEach { value ->
                                    InputChip(false, onClick = { search(value) }, label = { Text(value) }, trailingIcon = {
                                        IconButton(onClick = { scope.launch { userPreferences.removeSearchHistory(value) } }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, stringResource(com.pickaudio.R.string.ui_searchscreen_010), modifier = Modifier.size(16.dp)) }
                                    })
                                }
                            }
                            if (history.isEmpty()) Text(stringResource(com.pickaudio.R.string.ui_searchscreen_004), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                searchStateManager.platformStates.filterKeys { searchStateManager.selectedPlatform == Platform.ALL || it == searchStateManager.selectedPlatform.id }.forEach { (platform, state) ->
                    item(key = "header_${platform}") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(Platform.fromId(platform).displayName, style = MaterialTheme.typography.titleSmall)
                            Text("${state.items.size} 首", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(if (searchStateManager.selectedPlatform == Platform.ALL) state.items.take(5) else state.items, key = { "${it.platform}_${it.songId}" }) { item ->
                        val track = canonical(item)
                        MusicTrackRow(track, isCurrent = current?.id == track.id, isPlaying = playing,
                            selecting = selecting, selected = track.id in selectedIds, onSelect = { select(track.id) },
                            onClick = { playbackCoordinator.addOrPlayTrack(track); onOpenPlayer() },
                            onAddToLibrary = { addToLibrary(listOf(track)) },
                            actions = listOf(
                                TrackMenuAction(if (track.isInLibrary) "已在曲库" else "加入曲库", enabled = !track.isInLibrary) { addToLibrary(listOf(track)) },
                                TrackMenuAction(if (track.isFavorite) "取消喜欢" else "加入我喜欢") { scope.launch {
                                    if (track.isFavorite) { playlistRepository.toggleFavorite(track.id); message("已取消喜欢") }
                                    else { playlistRepository.addTracks(com.pickaudio.data.db.PickAudioDatabase.FAVORITE_PLAYLIST_ID, listOf(track)); message("已加入我喜欢") }
                                } },
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
