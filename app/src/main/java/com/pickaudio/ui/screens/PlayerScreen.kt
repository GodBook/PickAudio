package com.pickaudio.ui.screens

import androidx.compose.ui.res.stringResource

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
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
    val track by coordinator.currentTrack.collectAsStateWithLifecycle()
    val playing by coordinator.isPlaying.collectAsStateWithLifecycle()
    val progress by coordinator.currentPositionMs.collectAsStateWithLifecycle()
    val duration by coordinator.durationMs.collectAsStateWithLifecycle()
    val state by coordinator.uiState.collectAsStateWithLifecycle()
    val candidates by coordinator.versionCandidates.collectAsStateWithLifecycle()
    val mode by coordinator.playbackMode.collectAsStateWithLifecycle()
    val queue by coordinator.queue.collectAsStateWithLifecycle()
    val queueEntries by coordinator.queueEntries.collectAsStateWithLifecycle()
    val index by coordinator.currentIndex.collectAsStateWithLifecycle()
    val sleep by coordinator.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val lyricSize by app.userPreferences.lyricFontSize.collectAsStateWithLifecycle(initialValue = 18)
    val translation by app.userPreferences.showLyricTranslation.collectAsStateWithLifecycle(initialValue = true)
    val defaultQuality by app.userPreferences.defaultOnlineQuality.collectAsStateWithLifecycle(initialValue = Quality.Q128K)
    val libraryIds by app.libraryRepository.libraryTrackIds.collectAsStateWithLifecycle(initialValue = emptyList())
    var lyricsActive by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = lyricsActive) { lyricsActive = false }
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
    var retryLyrics by remember(track?.id) { mutableIntStateOf(0) }
    var pendingLyricTrackId by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val lrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingLyricTrackId
        if (uri != null && id != null) scope.launch {
            try {
                app.lyricRepository.importLrc(id, uri)
                if (track?.id == id) { retryLyrics++; lyricsActive = true }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { snack.showSnackbar(e.message ?: "歌词导入失败") }
        }
    }
    fun importLyrics() { pendingLyricTrackId = track?.id; lrcPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }
    fun addToLibrary(song: Track) {
        scope.launch {
            try {
                val count = app.libraryRepository.addTracks(listOf(song))
                snack.showSnackbar(if (count > 0) "已加入曲库" else "已在曲库中")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { snack.showSnackbar(e.message ?: "加入曲库失败，请重试") }
        }
    }
    val relinkFile = rememberRelinkFileAction(app.libraryRepository,
        { scope.launch { snack.showSnackbar("文件已关联，可点击播放") } },
        { message -> scope.launch { snack.showSnackbar(message) } })
    LaunchedEffect(track?.id, retryLyrics) {
        val song = track ?: return@LaunchedEffect
        lyricLoading = true; lyricError = null
        try { lyricData = app.lyricRepository.load(song, refresh = retryLyrics > 0) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { lyricError = "歌词加载失败，请检查网络后重试" }
        finally { lyricLoading = false }
    }
    val song = track ?: return
    val inLibrary = song.id in libraryIds
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_001), style = MaterialTheme.typography.titleMedium) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = { IconButton(onClick = onCollapse) { Icon(Icons.Default.KeyboardArrowDown, stringResource(com.pickaudio.R.string.ui_playerscreen_018)) } },
            actions = {
                IconButton(onClick = { showTimer = true }) { Icon(Icons.Default.Timer, stringResource(com.pickaudio.R.string.ui_playerscreen_019), tint = if (sleep != null || coordinator.stopAfterCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(com.pickaudio.R.string.ui_playerscreen_020)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(enabled = !inLibrary, text = { Text(if (inLibrary) "已在曲库" else "加入曲库") },
                            onClick = { menu = false; addToLibrary(song) })
                        DropdownMenuItem(text = { Text(stringResource(com.pickaudio.R.string.action_add_playlist)) }, onClick = { menu = false; playlistTracks = listOf(song) })
                        if (song.platform != null) DropdownMenuItem(text = { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_002)) }, onClick = { menu = false; downloadTracks = listOf(song) })
                        DropdownMenuItem(text = { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_003)) }, onClick = { menu = false; showQuality = true })
                        DropdownMenuItem(text = { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_004)) }, onClick = { menu = false; importLyrics() })
                        DropdownMenuItem(text = { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_005)) }, onClick = { menu = false; showLyricOptions = true })
                        DropdownMenuItem(text = { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_006)) }, onClick = { menu = false; onOpenSource() })
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
            val mediaHeight = if (compact) (maxHeight - 430.dp).coerceIn(120.dp, 180.dp) else (maxHeight - 460.dp).coerceIn(160.dp, 340.dp)
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
              Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                TabRow(if (lyricsActive) 1 else 0, modifier = Modifier.widthIn(max = 220.dp).padding(bottom = 16.dp),
                    containerColor = MaterialTheme.colorScheme.background) {
                    Tab(!lyricsActive, { lyricsActive = false }, text = { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_007)) })
                    Tab(lyricsActive, { lyricsActive = true }, text = { Text(stringResource(com.pickaudio.R.string.lyrics_title)) })
                }
                Box(Modifier.fillMaxWidth().height(mediaHeight).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    if (lyricsActive) {
                        when {
                            lyricLoading -> Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text(stringResource(com.pickaudio.R.string.ui_playerscreen_008), Modifier.padding(12.dp)) }
                            lyricError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(lyricError!!, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { retryLyrics++ }) { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_009)) }
                                TextButton(onClick = ::importLyrics) { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_010)) }
                            }
                            lyricData.lines.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(com.pickaudio.R.string.ui_playerscreen_011))
                                TextButton(onClick = ::importLyrics) { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_010)) }
                                if (song.platform != null) TextButton(onClick = { retryLyrics++ }) { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_012)) }
                            }
                            else -> SyncedLyricsView(lyricData.lines, progress, lyricData.offsetMs, coordinator::seekTo, { value ->
                                val bounded = value.coerceIn(-60000, 60000)
                                lyricData = lyricData.copy(offsetMs = bounded)
                                app.lyricRepository.scheduleOffset(song.id, bounded)
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.actualQuality ?: if (song.localUri != null) "本地播放" else state.requestedQuality?.let { "请求音质 · ${Quality.fromValue(it).label}" } ?: "在线音乐",
                        modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { addToLibrary(song) }, enabled = !inLibrary) {
                        Icon(if (inLibrary) Icons.Default.Check else Icons.Default.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text(if (inLibrary) "已在曲库" else "加入曲库")
                    }
                }
                lyricData.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.message != null && state.phase !in listOf(PlaybackPhase.ERROR, PlaybackPhase.CHOOSE_VERSION))
                    Text(state.message!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.phase in listOf(PlaybackPhase.RESOLVING, PlaybackPhase.BUFFERING)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text(state.phase.label, style = MaterialTheme.typography.bodySmall)
                }
                if (state.phase == PlaybackPhase.ERROR || state.phase == PlaybackPhase.CHOOSE_VERSION) Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(12.dp)) {
                        Text(state.message ?: state.phase.label, style = MaterialTheme.typography.bodyMedium,
                            color = if (state.phase == PlaybackPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = coordinator::retryCurrent) { Text(stringResource(com.pickaudio.R.string.action_retry)) }
                            TextButton(onClick = onOpenSource) { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_014)) }
                            if (song.platform == null) TextButton(onClick = { relinkFile(song, null) }) { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_015)) }
                        }
                    }
                }
              }
                var dragPosition by remember(song.id) { mutableStateOf<Float?>(null) }
                val progressLabel = stringResource(com.pickaudio.R.string.playback_progress)
                Slider(value = dragPosition ?: if (duration > 0) (progress.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragPosition = it }, onValueChangeFinished = { dragPosition?.let { coordinator.seekTo((it * duration).toLong()) }; dragPosition = null },
                    enabled = duration > 0 && state.phase !in listOf(PlaybackPhase.RESOLVING, PlaybackPhase.ERROR),
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = progressLabel
                        stateDescription = "${formatMusicTime(progress)} / ${formatMusicTime(duration)}"
                    })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatMusicTime(dragPosition?.let { (it * duration).toLong() } ?: progress), style = MaterialTheme.typography.labelMedium)
                    Text(formatMusicTime(duration), style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { coordinator.togglePlaybackMode() }) { Icon(when (mode) { PlaybackMode.SEQUENTIAL -> Icons.Default.FormatListNumbered; PlaybackMode.LIST_LOOP -> Icons.Default.Repeat; PlaybackMode.SINGLE_LOOP -> Icons.Default.RepeatOne; PlaybackMode.SHUFFLE -> Icons.Default.Shuffle }, mode.displayName) }
                    IconButton(onClick = coordinator::previous) { Icon(Icons.Default.SkipPrevious, stringResource(com.pickaudio.R.string.action_previous), modifier = Modifier.size(32.dp)) }
                    FilledIconButton(onClick = coordinator::playOrPause, modifier = Modifier.size(64.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) com.pickaudio.R.string.action_pause else com.pickaudio.R.string.action_play), modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = coordinator::next) { Icon(Icons.Default.SkipNext, stringResource(com.pickaudio.R.string.action_next), modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(com.pickaudio.R.string.playback_queue)) }
                }
                Text(mode.displayName + (sleep?.let { " · ${formatMusicTime(it)} 后停止" } ?: if (coordinator.stopAfterCurrentTrack) " · 播完本首停止" else ""), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 16.dp))
            }
        }
    }
    if (candidates.isNotEmpty()) VersionChoiceDialog(song, candidates, coordinator::confirmVersion, coordinator::dismissVersionCandidates, state.message)
    if (showQueue) QueueBottomSheet(queue, index, coordinator::playQueueItemFromUi, coordinator::removeQueueItem, onClearQueue,
        { showQueue = false }, coordinator::moveQueueItem, coordinator::undoClearQueue, queueEntries.map { it.id })
    if (showTimer) SleepTimerDialog(sleep, coordinator.stopAfterCurrentTrack, coordinator::setSleepTimer, { coordinator.cancelSleepTimer(); coordinator.stopAfterCurrentTrack = true }, coordinator::cancelSleepTimer, { showTimer = false })
    if (showQuality) PlaybackQualityDialog(song, app.sourceManager, state.requestedQuality ?: defaultQuality.value,
        state.actualQuality, { showQuality = false }) { quality, saveDefault ->
        scope.launch {
            if (saveDefault) app.userPreferences.setDefaultOnlineQuality(Quality.fromValue(quality))
            showQuality = false
            coordinator.changeQuality(quality)
        }
    }
    if (showLyricOptions) AlertDialog(onDismissRequest = { showLyricOptions = false }, title = { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_016)) }, text = {
        Column {
            Text("字号 · $lyricSize")
            Slider(lyricSize.toFloat(), { scope.launch { app.userPreferences.setLyricFontSize(it.toInt()) } }, valueRange = 14f..28f, steps = 13)
            Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_017), Modifier.weight(1f)); Switch(translation, { scope.launch { app.userPreferences.setShowLyricTranslation(it) } }) }
        }
    }, confirmButton = { TextButton(onClick = { showLyricOptions = false }) { Text(stringResource(com.pickaudio.R.string.action_done)) } })
    playlistTracks?.let { PlaylistPickerDialog(it, app.playlistRepository, { playlistTracks = null }, { result -> scope.launch { snack.showSnackbar(result) } }) }
    downloadTracks?.let { DownloadQualityDialog(it, app.downloadCoordinator, app.sourceManager, app.userPreferences, { downloadTracks = null }, onOpenSource,
        { result -> scope.launch { snack.showSnackbar(result.summary) } }) }
}
