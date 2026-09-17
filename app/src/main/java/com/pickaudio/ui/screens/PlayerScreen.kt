package com.pickaudio.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pickaudio.data.model.PlaybackMode
import com.pickaudio.data.model.Track
import com.pickaudio.online.LyricLine
import com.pickaudio.online.LyricParser
import com.pickaudio.online.NetEaseSearchAdapter
import com.pickaudio.online.QqMusicSearchAdapter
import com.pickaudio.playback.PlaybackCoordinator
import com.pickaudio.ui.components.QueueBottomSheet
import com.pickaudio.ui.components.SleepTimerDialog
import com.pickaudio.ui.components.SyncedLyricsView
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    coordinator: PlaybackCoordinator,
    onCollapse: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    isFavorite: Boolean,
    modifier: Modifier = Modifier
) {
    val currentTrack by coordinator.currentTrack.collectAsState()
    val isPlaying by coordinator.isPlaying.collectAsState()
    val progressMs by coordinator.currentPositionMs.collectAsState()
    val durationMs by coordinator.durationMs.collectAsState()
    val mode by coordinator.playbackMode.collectAsState()
    val queue by coordinator.queue.collectAsState()
    val currentIndex by coordinator.currentIndex.collectAsState()
    val sleepRemainingMs by coordinator.sleepTimerRemainingMs.collectAsState()

    var showLyrics by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricOffsetMs by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    // Fetch lyrics when track changes
    LaunchedEffect(currentTrack?.id) {
        val t = currentTrack
        if (t != null) {
            lyrics = emptyList()
            if (t.platform != null && t.platformSongId != null) {
                try {
                    val pair = if (t.platform == "wy") {
                        NetEaseSearchAdapter.getLyric(t.platformSongId)
                    } else {
                        QqMusicSearchAdapter.getLyric(t.platformSongId)
                    }
                    lyrics = LyricParser.parse(pair.first, pair.second)
                } catch (e: Exception) {
                    lyrics = emptyList()
                }
            }
        }
    }

    if (currentTrack == null) return

    val track = currentTrack!!

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "收起")
                    }
                },
                actions = {
                    IconButton(onClick = { showSleepTimerDialog = true }) {
                        Icon(
                            imageVector = if (sleepRemainingMs != null || coordinator.stopAfterCurrentTrack) Icons.Default.Timer else Icons.Default.Schedule,
                            contentDescription = "睡眠定时",
                            tint = if (sleepRemainingMs != null || coordinator.stopAfterCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Center Area: Vinyl Cover or Synced Lyrics
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { showLyrics = !showLyrics },
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(targetState = showLyrics, label = "CoverLyricsSwitch") { lyricsActive ->
                    if (lyricsActive) {
                        SyncedLyricsView(
                            lyrics = lyrics,
                            currentPositionMs = progressMs,
                            offsetMs = lyricOffsetMs,
                            onSeekTo = { coordinator.seekTo(it) },
                            onOffsetChange = { lyricOffsetMs = it }
                        )
                    } else {
                        // Vinyl Cover
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!track.coverUri.isNullOrEmpty()) {
                                AsyncImage(
                                    model = track.coverUri,
                                    contentDescription = track.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(96.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Track info and Favorite Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${track.artist} · ${track.album}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = { onToggleFavorite(track.id) }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (isFavorite) Color(0xFFFF4081) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Progress Slider
            var sliderPosition by remember { mutableFloatStateOf(0f) }
            var isUserDragging by remember { mutableStateOf(false) }

            val currentFraction = if (durationMs > 0) (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

            Slider(
                value = if (isUserDragging) sliderPosition else currentFraction,
                onValueChange = {
                    isUserDragging = true
                    sliderPosition = it
                },
                onValueChangeFinished = {
                    isUserDragging = false
                    coordinator.seekTo((sliderPosition * durationMs).toLong())
                },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMs(if (isUserDragging) (sliderPosition * durationMs).toLong() else progressMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatMs(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Playback Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Playback Mode Icon
                IconButton(onClick = { coordinator.togglePlaybackMode() }) {
                    val icon = when (mode) {
                        PlaybackMode.SEQUENTIAL -> Icons.Default.FormatListNumbered
                        PlaybackMode.LIST_LOOP -> Icons.Default.Repeat
                        PlaybackMode.SINGLE_LOOP -> Icons.Default.RepeatOne
                        PlaybackMode.SHUFFLE -> Icons.Default.Shuffle
                    }
                    Icon(imageVector = icon, contentDescription = mode.displayName)
                }

                // Previous (Smart)
                IconButton(onClick = { coordinator.previous() }) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "上一首",
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Play / Pause (Big Button)
                FilledIconButton(
                    onClick = { coordinator.playOrPause() },
                    modifier = Modifier.size(68.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }

                // Next
                IconButton(onClick = { coordinator.next() }) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Queue Bottom Sheet
                IconButton(onClick = { showQueueSheet = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "播放队列"
                    )
                }
            }
        }
    }

    if (showQueueSheet) {
        QueueBottomSheet(
            queue = queue,
            currentIndex = currentIndex,
            onTrackClick = {
                coordinator.setQueueAndPlay(queue, it)
                showQueueSheet = false
            },
            onRemoveItem = { coordinator.removeQueueItem(it) },
            onClearQueue = { coordinator.clearQueue() },
            onDismiss = { showQueueSheet = false }
        )
    }

    if (showSleepTimerDialog) {
        SleepTimerDialog(
            currentRemainingMs = sleepRemainingMs,
            stopAfterCurrent = coordinator.stopAfterCurrentTrack,
            onSetMinutes = { coordinator.setSleepTimer(it) },
            onSetStopAfterCurrent = { coordinator.stopAfterCurrentTrack = true },
            onCancelTimer = { coordinator.cancelSleepTimer() },
            onDismiss = { showSleepTimerDialog = false }
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
