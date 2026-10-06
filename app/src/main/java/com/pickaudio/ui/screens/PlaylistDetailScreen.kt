package com.pickaudio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pickaudio.PickAudioApplication
import com.pickaudio.data.model.*
import com.pickaudio.data.repository.PlaylistRepository
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.playback.PlaybackCoordinator
import com.pickaudio.ui.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: String, playlistName: String, playlistRepository: PlaylistRepository,
    playbackCoordinator: PlaybackCoordinator, downloadCoordinator: DownloadCoordinator? = null,
    onBack: () -> Unit, modifier: Modifier = Modifier, onOpenSource: () -> Unit = {}
) {
    val app = LocalContext.current.applicationContext as PickAudioApplication
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val storedTracks by remember(playlistId) { playlistRepository.getTracksForPlaylist(playlistId) }.collectAsState(initial = emptyList())
    val playlists by playlistRepository.getAllPlaylists().collectAsState(initial = emptyList())
    val current by playbackCoordinator.currentTrack.collectAsState()
    val playing by playbackCoordinator.isPlaying.collectAsState()
    var ordered by remember(playlistId) { mutableStateOf<List<Track>>(emptyList()) }
    var reordering by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var addTracks by remember { mutableStateOf<List<Track>?>(null) }
    var downloadTracks by remember { mutableStateOf<List<Track>?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(storedTracks) { if (!reordering) ordered = storedTracks }
    val tracks = ordered.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
    val selected = tracks.filter { it.id in selectedIds }
    fun select(id: String) { selecting = true; selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun remove(list: List<Track>) {
        scope.launch {
            playlistRepository.removeTracks(playlistId, list.map { it.id })
            selectedIds = emptySet()
            val result = snack.showSnackbar("已移除 ${list.size} 首，音频文件保留", "撤销")
            if (result == SnackbarResult.ActionPerformed) playlistRepository.addTracks(playlistId, list)
        }
    }
    fun finishOrder() {
        val ids = ordered.map { it.id }
        scope.launch { playlistRepository.reorderTracks(playlistId, ids); reordering = false }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(playlists.find { it.id == playlistId }?.name ?: playlistName) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = { TextButton(onClick = { selecting = !selecting; selectedIds = emptySet() }) { Text(if (selecting) "完成" else "多选") } })
    }, snackbarHost = { SnackbarHost(snack) }, modifier = modifier.fillMaxSize()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("在歌单中搜索") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { playbackCoordinator.setPlaybackMode(PlaybackMode.SEQUENTIAL); playbackCoordinator.setQueueAndPlay(tracks) }, enabled = tracks.isNotEmpty()) { Text("播放全部") }
                OutlinedButton(onClick = { playbackCoordinator.setPlaybackMode(PlaybackMode.SHUFFLE); playbackCoordinator.setQueueAndPlay(tracks.shuffled()) }, enabled = tracks.isNotEmpty()) { Text("随机播放") }
                Text("${tracks.size} 首 · 长按多选，拖动右侧排序", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 12.dp))
            }
            if (selecting) FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { selectedIds = tracks.map { it.id }.toSet() }) { Text("全选 (${selectedIds.size})") }
                TextButton(onClick = { addTracks = selected }, enabled = selected.isNotEmpty()) { Text("加入歌单") }
                TextButton(onClick = { downloadTracks = selected }, enabled = selected.any { it.platform != null }) { Text("下载") }
                TextButton(onClick = { remove(selected) }, enabled = selected.isNotEmpty()) { Text("移除") }
            }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                if (tracks.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (query.isBlank()) "歌单暂无歌曲" else "没有匹配歌曲")
                        Text("可在音乐库或搜索结果中选择“加入歌单”。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                itemsIndexed(tracks, key = { _, item -> item.id }) { index, track ->
                    val actions = mutableListOf(
                        TrackMenuAction("下一首播放") { playbackCoordinator.playNext(track) },
                        TrackMenuAction("添加到队尾") { playbackCoordinator.addToQueue(track) },
                        TrackMenuAction("加入其他歌单") { addTracks = listOf(track) },
                        TrackMenuAction("单曲循环") { playbackCoordinator.setPlaybackMode(PlaybackMode.SINGLE_LOOP); playbackCoordinator.addOrPlayTrack(track) }
                    )
                    if (track.platform != null) actions.add(TrackMenuAction("下载歌曲") { downloadTracks = listOf(track) })
                    actions.add(TrackMenuAction("从歌单移除", true) { remove(listOf(track)) })
                    MusicTrackRow(track, current?.id == track.id, playing, selecting, track.id in selectedIds,
                        onClick = { playbackCoordinator.setQueueAndPlay(tracks, index) }, onSelect = { select(track.id) }, actions = actions,
                        trailing = { if (query.isBlank()) ReorderHandle(track.id, index, tracks.size, listState, { from, to ->
                            reordering = true
                            ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
                        }, ::finishOrder) })
                }
            }
        }
    }
    addTracks?.let { PlaylistPickerDialog(it, playlistRepository, { addTracks = null }) }
    downloadTracks?.let { DownloadQualityDialog(it, app.downloadCoordinator, app.sourceManager, app.userPreferences, { downloadTracks = null }, onOpenSource) }
}
