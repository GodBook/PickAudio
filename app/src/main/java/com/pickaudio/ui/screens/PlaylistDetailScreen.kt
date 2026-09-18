package com.pickaudio.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.model.PlaybackMode
import com.pickaudio.data.model.Track
import com.pickaudio.data.repository.PlaylistRepository
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.playback.PlaybackCoordinator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    playlistName: String,
    playlistRepository: PlaylistRepository,
    playbackCoordinator: PlaybackCoordinator,
    downloadCoordinator: DownloadCoordinator? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracks by playlistRepository.getTracksForPlaylist(playlistId).collectAsState(initial = emptyList())
    val allPlaylists by playlistRepository.getAllPlaylists().collectAsState(initial = emptyList())
    val currentPlayingTrack by playbackCoordinator.currentTrack.collectAsState()
    val isPlaybackPlaying by playbackCoordinator.isPlaying.collectAsState()
    var trackForPlaylist by remember { mutableStateOf<Track?>(null) }
    var trackForDownload by remember { mutableStateOf<Track?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlistName, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Action header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "共 ${tracks.size} 首歌曲",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (tracks.isNotEmpty()) {
                    Row {
                        FilledTonalButton(
                            onClick = {
                                playbackCoordinator.setPlaybackMode(PlaybackMode.SEQUENTIAL)
                                playbackCoordinator.setQueueAndPlay(tracks, 0)
                                Toast.makeText(context, "已开启顺序播放", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.FormatListNumbered, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("顺序播放")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        FilledTonalButton(
                            onClick = {
                                playbackCoordinator.setPlaybackMode(PlaybackMode.SHUFFLE)
                                playbackCoordinator.setQueueAndPlay(tracks, 0)
                                Toast.makeText(context, "已开启随机播放", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("随机播放")
                        }
                    }
                }
            }

            HorizontalDivider()

            if (tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "歌单暂无歌曲",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    itemsIndexed(tracks) { index, track ->
                        var showMenu by remember { mutableStateOf(false) }
                        val isCurrent = (track.id == currentPlayingTrack?.id)

                        ListItem(
                            colors = ListItemDefaults.colors(
                                containerColor = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent
                            ),
                            headlineContent = {
                                Text(
                                    text = track.title,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = "${track.artist} · ${track.album}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!track.coverUri.isNullOrEmpty()) {
                                        AsyncImage(
                                            model = track.coverUri,
                                            contentDescription = track.title,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                    if (isCurrent) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.45f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isPlaybackPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "更多", modifier = Modifier.size(20.dp))
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("下一首播放") },
                                            onClick = {
                                                playbackCoordinator.playNext(track)
                                                showMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("添加到队尾") },
                                            onClick = {
                                                playbackCoordinator.addToQueue(track)
                                                showMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("加入歌单") },
                                            onClick = {
                                                trackForPlaylist = track
                                                showMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("单曲循环") },
                                            onClick = {
                                                playbackCoordinator.setPlaybackMode(PlaybackMode.SINGLE_LOOP)
                                                playbackCoordinator.setQueueAndPlay(listOf(track), 0)
                                                showMenu = false
                                                Toast.makeText(context, "已设为单曲循环", Toast.LENGTH_SHORT).show()
                                            },
                                            leadingIcon = { Icon(Icons.Default.RepeatOne, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("下载歌曲") },
                                            onClick = {
                                                showMenu = false
                                                val hasOnline = (track.platform != null && track.platformSongId != null) || track.id.startsWith("online_")
                                                if (hasOnline) {
                                                    trackForDownload = track
                                                } else {
                                                    Toast.makeText(context, "本地歌曲无需下载", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("删除下载") },
                                            onClick = {
                                                showMenu = false
                                                if (downloadCoordinator != null) {
                                                    scope.launch {
                                                        val ok = downloadCoordinator.deleteDownloadForTrack(track.id)
                                                        if (ok) {
                                                            Toast.makeText(context, "已删除本地下载", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(context, "暂无本地下载文件", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            },
                                            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) }
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("移出歌单", color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                showMenu = false
                                                scope.launch {
                                                    playlistRepository.removeTrackFromPlaylist(playlistId, track.id)
                                                    Toast.makeText(context, "已从歌单移出", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { playbackCoordinator.setQueueAndPlay(tracks, index) },
                                onLongClick = { showMenu = true }
                            )
                        )
                    }
                }
            }
        }
    }

    // Add to playlist dialog
    if (trackForPlaylist != null) {
        val t = trackForPlaylist!!
        AlertDialog(
            onDismissRequest = { trackForPlaylist = null },
            title = { Text("加入歌单") },
            text = {
                val candidatePlaylists = allPlaylists.filter { it.id != playlistId }
                if (candidatePlaylists.isEmpty()) {
                    Text("暂无可添加的其他歌单")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(candidatePlaylists) { pl ->
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        imageVector = if (pl.id == PickAudioDatabase.FAVORITE_PLAYLIST_ID) Icons.Default.Favorite else Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = if (pl.id == PickAudioDatabase.FAVORITE_PLAYLIST_ID) Color(0xFFFF4081) else MaterialTheme.colorScheme.primary
                                    )
                                },
                                headlineContent = { Text(pl.name) },
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        playlistRepository.addTrackToPlaylist(pl.id, t.id)
                                        Toast.makeText(context, "已加入歌单「${pl.name}」", Toast.LENGTH_SHORT).show()
                                    }
                                    trackForPlaylist = null
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { trackForPlaylist = null }) { Text("取消") }
            }
        )
    }

    // Download Quality Selection Dialog
    if (trackForDownload != null && downloadCoordinator != null) {
        val t = trackForDownload!!
        val platform = t.platform ?: if (t.id.startsWith("online_wy_")) "wy" else "tx"
        val songId = t.platformSongId ?: t.id.removePrefix("online_wy_").removePrefix("online_tx_")
        val qualities = listOf("128k", "320k", "flac")
        AlertDialog(
            onDismissRequest = { trackForDownload = null },
            title = { Text("选择下载音质") },
            text = {
                Column {
                    Text(text = "${t.title} - ${t.artist}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    qualities.forEach { q ->
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    downloadCoordinator.enqueueDownload(
                                        trackId = t.id,
                                        title = t.title,
                                        artist = t.artist,
                                        album = t.album,
                                        coverUri = t.coverUri,
                                        platform = platform,
                                        platformSongId = songId,
                                        quality = q
                                    )
                                    Toast.makeText(context, "已加入下载任务", Toast.LENGTH_SHORT).show()
                                }
                                trackForDownload = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text("下载 $q 音质")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { trackForDownload = null }) { Text("取消") }
            }
        )
    }
}
