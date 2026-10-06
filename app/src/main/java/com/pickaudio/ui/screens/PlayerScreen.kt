package com.pickaudio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pickaudio.PickAudioApplication
import com.pickaudio.data.model.*
import com.pickaudio.data.repository.LoadedLyrics
import com.pickaudio.playback.PlaybackCoordinator
import com.pickaudio.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    coordinator: PlaybackCoordinator, onCollapse: () -> Unit, onToggleFavorite: (String) -> Unit,
    isFavorite: Boolean, modifier: Modifier = Modifier, onOpenSource: () -> Unit = {}, onClearQueue: () -> Unit = coordinator::clearQueue
) {
    val app = LocalContext.current.applicationContext as PickAudioApplication
    val scope = rememberCoroutineScope()
    val track by coordinator.currentTrack.collectAsState()
    val playing by coordinator.isPlaying.collectAsState()
    val progress by coordinator.currentPositionMs.collectAsState()
    val duration by coordinator.durationMs.collectAsState()
    val state by coordinator.uiState.collectAsState()
    val candidates by coordinator.versionCandidates.collectAsState()
    val mode by coordinator.playbackMode.collectAsState()
    val queue by coordinator.queue.collectAsState()
    val index by coordinator.currentIndex.collectAsState()
    val sleep by coordinator.sleepTimerRemainingMs.collectAsState()
    val lyricSize by app.userPreferences.lyricFontSize.collectAsState(initial = 18)
    val translation by app.userPreferences.showLyricTranslation.collectAsState(initial = true)
    val defaultQuality by app.userPreferences.defaultOnlineQuality.collectAsState(initial = Quality.Q128K)
    var lyricsActive by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showLyricOptions by remember { mutableStateOf(false) }
    var playlistTracks by remember { mutableStateOf<List<Track>?>(null) }
    var downloadTracks by remember { mutableStateOf<List<Track>?>(null) }
    var lyricData by remember(track?.id) { mutableStateOf(LoadedLyrics(emptyList(), 0, false)) }
    var lyricLoading by remember(track?.id) { mutableStateOf(false) }
    var lyricError by remember(track?.id) { mutableStateOf<String?>(null) }
    var retryLyrics by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val lrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = track?.id
        if (uri != null && id != null) scope.launch {
            try { app.lyricRepository.importLrc(id, uri); retryLyrics++; lyricsActive = true }
            catch (e: Exception) { snack.showSnackbar(e.message ?: "歌词导入失败") }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val oldId = track?.id
        if (uri != null && oldId != null) scope.launch {
            try {
                val id = app.libraryRepository.relinkTrack(oldId, uri)
                val replacement = app.libraryRepository.getAllTracks().first().find { it.id == id } ?: error("关联的歌曲尚未载入")
                coordinator.replaceCurrentTrack(replacement)
            } catch (e: Exception) { snack.showSnackbar(e.message ?: "关联文件失败") }
        }
    }
    LaunchedEffect(track?.id, retryLyrics) {
        val song = track ?: return@LaunchedEffect
        lyricLoading = true; lyricError = null
        try { lyricData = app.lyricRepository.load(song, refresh = retryLyrics > 0) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { lyricError = "歌词加载失败，请检查网络后重试" }
        finally { lyricLoading = false }
    }
    val song = track ?: return
    Scaffold(topBar = {
        TopAppBar(title = { Text("正在播放", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onCollapse) { Icon(Icons.Default.KeyboardArrowDown, "收起播放器") } },
            actions = {
                IconButton(onClick = { showTimer = true }) { Icon(Icons.Default.Timer, "睡眠定时", tint = if (sleep != null || coordinator.stopAfterCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多播放操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("加入歌单") }, onClick = { menu = false; playlistTracks = listOf(song) })
                        if (song.platform != null) DropdownMenuItem(text = { Text("下载歌曲") }, onClick = { menu = false; downloadTracks = listOf(song) })
                        DropdownMenuItem(text = { Text("播放音质") }, onClick = { menu = false; showQuality = true })
                        DropdownMenuItem(text = { Text("导入本地歌词") }, onClick = { menu = false; lrcPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) })
                        DropdownMenuItem(text = { Text("歌词字号与翻译") }, onClick = { menu = false; showLyricOptions = true })
                        DropdownMenuItem(text = { Text("切换音乐源") }, onClick = { menu = false; onOpenSource() })
                        PlaybackMode.entries.forEach { value ->
                            DropdownMenuItem(text = { Text(value.displayName) }, onClick = { coordinator.setPlaybackMode(value); menu = false },
                                trailingIcon = { if (mode == value) Icon(Icons.Default.Check, null) })
                        }
                    }
                }
            })
    }, snackbarHost = { SnackbarHost(snack) }, modifier = modifier.fillMaxSize()) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val compact = maxHeight < 620.dp || LocalDensity.current.fontScale > 1.3f
            val mediaHeight = if (compact) 200.dp else (maxHeight - 310.dp).coerceIn(260.dp, 440.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!lyricsActive, { lyricsActive = false }, label = { Text("封面") })
                    FilterChip(lyricsActive, { lyricsActive = true }, label = { Text("歌词") })
                }
                Box(Modifier.fillMaxWidth().height(mediaHeight).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    if (lyricsActive) {
                        when {
                            lyricLoading -> Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("正在加载歌词", Modifier.padding(12.dp)) }
                            lyricError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(lyricError!!, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { retryLyrics++ }) { Text("重试歌词") }
                                TextButton(onClick = { lrcPicker.launch(arrayOf("*/*")) }) { Text("导入本地 LRC") }
                            }
                            lyricData.lines.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("暂无同步歌词")
                                TextButton(onClick = { lrcPicker.launch(arrayOf("*/*")) }) { Text("导入本地 LRC") }
                                if (song.platform != null) TextButton(onClick = { retryLyrics++ }) { Text("重新获取") }
                            }
                            else -> SyncedLyricsView(lyricData.lines, progress, lyricData.offsetMs, coordinator::seekTo, { value ->
                                val bounded = value.coerceIn(-60000, 60000)
                                lyricData = lyricData.copy(offsetMs = bounded)
                                scope.launch { app.lyricRepository.saveOffset(song.id, bounded) }
                            }, fontSizeSp = lyricSize, showTranslation = translation)
                        }
                    } else {
                        Box(Modifier.size(mediaHeight).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { lyricsActive = true }, contentAlignment = Alignment.Center) {
                            if (!song.coverUri.isNullOrBlank()) AsyncImage(song.coverUri, song.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${song.artist} · ${song.album}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onToggleFavorite(song.id) }) { Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (isFavorite) "取消喜欢" else "加入我喜欢", tint = MaterialTheme.colorScheme.primary) }
                }
                Text(state.actualQuality ?: if (song.localUri != null) "本地播放" else state.requestedQuality?.let { "请求音质 · ${Quality.fromValue(it).label}" } ?: "在线音乐",
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.phase in listOf(PlaybackPhase.RESOLVING, PlaybackPhase.BUFFERING)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text(state.phase.label, style = MaterialTheme.typography.bodySmall)
                }
                state.message?.let { Text(it, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                if (state.phase == PlaybackPhase.ERROR || state.phase == PlaybackPhase.CHOOSE_VERSION) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = coordinator::retryCurrent) { Text("重试") }
                    TextButton(onClick = { showQuality = true }) { Text("选择音质") }
                    TextButton(onClick = onOpenSource) { Text("更换音乐源") }
                    if (song.platform == null) TextButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }) { Text("重新关联音频") }
                }
                var dragPosition by remember(song.id) { mutableStateOf<Float?>(null) }
                Slider(value = dragPosition ?: if (duration > 0) (progress.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragPosition = it }, onValueChangeFinished = { dragPosition?.let { coordinator.seekTo((it * duration).toLong()) }; dragPosition = null },
                    enabled = duration > 0 && state.phase !in listOf(PlaybackPhase.RESOLVING, PlaybackPhase.ERROR), modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatMusicTime(dragPosition?.let { (it * duration).toLong() } ?: progress), style = MaterialTheme.typography.labelMedium)
                    Text(formatMusicTime(duration), style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { coordinator.togglePlaybackMode() }) { Icon(when (mode) { PlaybackMode.SEQUENTIAL -> Icons.Default.FormatListNumbered; PlaybackMode.LIST_LOOP -> Icons.Default.Repeat; PlaybackMode.SINGLE_LOOP -> Icons.Default.RepeatOne; PlaybackMode.SHUFFLE -> Icons.Default.Shuffle }, mode.displayName) }
                    IconButton(onClick = coordinator::previous) { Icon(Icons.Default.SkipPrevious, "上一首", modifier = Modifier.size(32.dp)) }
                    FilledIconButton(onClick = coordinator::playOrPause, modifier = Modifier.size(64.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "播放", modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = coordinator::next) { Icon(Icons.Default.SkipNext, "下一首", modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, "播放队列") }
                }
                Text(mode.displayName + (sleep?.let { " · ${formatMusicTime(it)} 后停止" } ?: if (coordinator.stopAfterCurrentTrack) " · 播完本首停止" else ""), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 16.dp))
            }
        }
    }
    if (candidates.isNotEmpty()) VersionChoiceDialog(song, candidates, coordinator::confirmVersion, coordinator::dismissVersionCandidates)
    if (showQueue) QueueBottomSheet(queue, index, { coordinator.addOrPlayTrack(queue[it]) }, coordinator::removeQueueItem, onClearQueue,
        { showQueue = false }, coordinator::moveQueueItem, coordinator::undoClearQueue)
    if (showTimer) SleepTimerDialog(sleep, coordinator.stopAfterCurrentTrack, coordinator::setSleepTimer, { coordinator.cancelSleepTimer(); coordinator.stopAfterCurrentTrack = true }, coordinator::cancelSleepTimer, { showTimer = false })
    if (showQuality) AlertDialog(onDismissRequest = { showQuality = false }, title = { Text("播放音质") }, text = {
        Column { Quality.entries.forEach { quality -> Row(Modifier.fillMaxWidth().clickable {
            scope.launch { app.userPreferences.setDefaultOnlineQuality(quality); showQuality = false; coordinator.retryCurrent() }
        }, verticalAlignment = Alignment.CenterVertically) { RadioButton(defaultQuality == quality, onClick = null); Text(quality.label) } } }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { showQuality = false }) { Text("取消") } })
    if (showLyricOptions) AlertDialog(onDismissRequest = { showLyricOptions = false }, title = { Text("歌词显示") }, text = {
        Column {
            Text("字号 · $lyricSize")
            Slider(lyricSize.toFloat(), { scope.launch { app.userPreferences.setLyricFontSize(it.toInt()) } }, valueRange = 14f..28f, steps = 13)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("显示翻译", Modifier.weight(1f)); Switch(translation, { scope.launch { app.userPreferences.setShowLyricTranslation(it) } }) }
        }
    }, confirmButton = { TextButton(onClick = { showLyricOptions = false }) { Text("完成") } })
    playlistTracks?.let { PlaylistPickerDialog(it, app.playlistRepository, { playlistTracks = null }) }
    downloadTracks?.let { DownloadQualityDialog(it, app.downloadCoordinator, app.sourceManager, app.userPreferences, { downloadTracks = null }, onOpenSource) }
}
