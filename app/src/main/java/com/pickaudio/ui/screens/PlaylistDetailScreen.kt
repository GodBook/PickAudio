package com.pickaudio.ui.screens

import androidx.compose.ui.res.stringResource

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val storedTracks by remember(playlistId) { playlistRepository.getTracksForPlaylist(playlistId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlists by playlistRepository.getAllPlaylists().collectAsStateWithLifecycle(initialValue = emptyList())
    val current by playbackCoordinator.currentTrack.collectAsStateWithLifecycle()
    val playing by playbackCoordinator.isPlaying.collectAsStateWithLifecycle()
    var ordered by remember(playlistId) { mutableStateOf<List<Track>>(emptyList()) }
    var reordering by remember { mutableStateOf(false) }
    var savingOrder by remember { mutableStateOf(false) }
    var baseOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    androidx.activity.compose.BackHandler(enabled = selecting && !com.pickaudio.LocalPlayerOverlayVisible.current) { selecting = false; selectedIds = emptySet() }
    var addTracks by remember { mutableStateOf<List<Track>?>(null) }
    var downloadTracks by remember { mutableStateOf<List<Track>?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(storedTracks, reordering) { if (!reordering) ordered = storedTracks }
    val tracks = ordered.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
    val selected = tracks.filter { it.id in selectedIds }
    fun select(id: String) { selecting = true; selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun remove(list: List<Track>) {
        scope.launch {
            val removed = playlistRepository.removeWithUndo(playlistId, list.map { it.id })
            selectedIds = emptySet()
            val result = snack.showSnackbar("已移除 ${list.size} 首，音频文件保留", "撤销")
            if (result == SnackbarResult.ActionPerformed) {
                try { snack.showSnackbar("已恢复 ${playlistRepository.restoreRemoved(removed)} 首，原位置和添加时间保留") }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { snack.showSnackbar("撤销失败：${e.message}") }
            }
        }
    }
    fun finishOrder() {
        if (!reordering || savingOrder) return
        val ids = ordered.map { it.id }
        savingOrder = true
        scope.launch {
            try { playlistRepository.reorderTracks(playlistId, ids, baseOrder) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { snack.showSnackbar("排序未保存：${e.message}") }
            finally { reordering = false; savingOrder = false }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(playlists.find { it.id == playlistId }?.name ?: playlistName) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(com.pickaudio.R.string.action_back)) } },
            actions = { TextButton(onClick = { selecting = !selecting; selectedIds = emptySet() }) { Text(if (selecting) "完成" else "多选") } })
    }, snackbarHost = { SnackbarHost(snack) }, modifier = modifier.fillMaxSize()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            MusicSearchField(query, { query = it }, stringResource(com.pickaudio.R.string.ui_playlistdetailscreen_001), modifier = Modifier.padding(20.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { playbackCoordinator.setPlaybackMode(PlaybackMode.SEQUENTIAL); playbackCoordinator.setQueueAndPlay(tracks) }, enabled = tracks.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_012)) }
                OutlinedButton(onClick = { playbackCoordinator.setPlaybackMode(PlaybackMode.SHUFFLE); playbackCoordinator.setQueueAndPlay(tracks.shuffled()) }, enabled = tracks.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.ui_playlistdetailscreen_002)) }
                Text("${tracks.size} 首 · 长按多选，拖动右侧排序", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 12.dp))
            }
            if (selecting) FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { selectedIds = tracks.map { it.id }.toSet() }) { Text("全选 (${selectedIds.size})") }
                TextButton(onClick = { addTracks = selected }, enabled = selected.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.action_add_playlist)) }
                TextButton(onClick = { downloadTracks = selected }, enabled = selected.any { it.platform != null }) { Text(stringResource(com.pickaudio.R.string.action_download)) }
                TextButton(onClick = { remove(selected) }, enabled = selected.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.action_remove)) }
            }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                if (tracks.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (query.isBlank()) "歌单暂无歌曲" else "没有匹配歌曲")
                        Text(stringResource(com.pickaudio.R.string.ui_playlistdetailscreen_003), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                itemsIndexed(tracks, key = { _, item -> item.id }) { index, track ->
                    val actions = mutableListOf(
                        TrackMenuAction(if (track.isInLibrary) "已在曲库" else "加入曲库", enabled = !track.isInLibrary) { scope.launch {
                            try { app.libraryRepository.addTracks(listOf(track)); snack.showSnackbar("已加入曲库") }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { snack.showSnackbar(e.message ?: "加入曲库失败") }
                        } },
                        TrackMenuAction("下一首播放") { playbackCoordinator.playNext(track) },
                        TrackMenuAction("添加到队尾") { playbackCoordinator.addToQueue(track) },
                        TrackMenuAction("加入其他歌单") { addTracks = listOf(track) },
                        TrackMenuAction("单曲循环") { playbackCoordinator.setPlaybackMode(PlaybackMode.SINGLE_LOOP); playbackCoordinator.addOrPlayTrack(track) }
                    )
                    if (track.platform != null) actions.add(TrackMenuAction("下载歌曲") { downloadTracks = listOf(track) })
                    actions.add(TrackMenuAction("从歌单移除", true) { remove(listOf(track)) })
                    MusicTrackRow(track, current?.id == track.id, playing, selecting, track.id in selectedIds,
                        onClick = { playbackCoordinator.setQueueAndPlay(tracks, index) }, onSelect = { select(track.id) }, actions = actions,
                        trailing = { if (query.isBlank() && !savingOrder) ReorderHandle(track.id, index, tracks.size, listState, { from, to ->
                            if (!reordering) baseOrder = storedTracks.map { it.id }
                            reordering = true
                            ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
                        }, ::finishOrder) })
                }
            }
        }
    }
    addTracks?.let { PlaylistPickerDialog(it, playlistRepository, { addTracks = null }, { result -> scope.launch { snack.showSnackbar(result) } }) }
    downloadTracks?.let { DownloadQualityDialog(it, app.downloadCoordinator, app.sourceManager, app.userPreferences, { downloadTracks = null }, onOpenSource,
        { result -> scope.launch { snack.showSnackbar(result.summary) } }) }
}
