package com.pickaudio.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.model.Track
import com.pickaudio.data.repository.LibraryRepository
import com.pickaudio.data.repository.PlaylistRepository
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.playback.PlaybackCoordinator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    libraryRepository: LibraryRepository,
    playlistRepository: PlaylistRepository,
    playbackCoordinator: PlaybackCoordinator,
    downloadCoordinator: DownloadCoordinator? = null,
    onNavigateToPlaylistDetail: (String) -> Unit,
    onNavigateToDownload: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    val tracksFlow = remember(searchQuery) {
        if (searchQuery.isBlank()) libraryRepository.getAllTracks() else libraryRepository.searchTracks(searchQuery)
    }
    val tracks by tracksFlow.collectAsState(initial = emptyList())
    val allPlaylists by playlistRepository.getAllPlaylists().collectAsState(initial = emptyList())
    val currentPlayingTrack by playbackCoordinator.currentTrack.collectAsState()
    val isPlaybackPlaying by playbackCoordinator.isPlaying.collectAsState()

    var trackToDelete by remember { mutableStateOf<Track?>(null) }
    var deleteLocalFile by remember { mutableStateOf(false) }
    var trackForPlaylist by remember { mutableStateOf<Track?>(null) }
    var trackForDownload by remember { mutableStateOf<Track?>(null) }

    // SAF Pickers
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                var imported = 0
                uris.forEach { if (libraryRepository.importSafFile(it)) imported++ }
                Toast.makeText(context, "成功导入 $imported 首歌曲", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                val count = libraryRepository.importSafDirectory(uri)
                Toast.makeText(context, "从目录导入 $count 首歌曲", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Permission launcher for scanning MediaStore
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scope.launch {
                val count = libraryRepository.scanMediaStore(filterShortAudio = true)
                Toast.makeText(context, "扫描完成，导入 $count 首新歌曲", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "未获得音频读取权限，您仍可通过选择文件导入", Toast.LENGTH_LONG).show()
        }
    }

    fun triggerScan() {
        val permission = Manifest.permission.READ_MEDIA_AUDIO
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            scope.launch {
                val count = libraryRepository.scanMediaStore(filterShortAudio = true)
                Toast.makeText(context, "扫描完成，导入 $count 首新歌曲", Toast.LENGTH_SHORT).show()
            }
        } else {
            permissionLauncher.launch(permission)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地曲库", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToDownload) {
                        Icon(Icons.Default.Download, contentDescription = "下载管理")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = { triggerScan() }) {
                        Icon(Icons.Default.Sync, contentDescription = "扫描歌曲")
                    }
                    IconButton(onClick = { filePicker.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Default.AudioFile, contentDescription = "选择音频文件")
                    }
                    IconButton(onClick = { treePicker.launch(null) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "选择文件夹")
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
            // Search Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索歌曲、歌手、专辑") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Header summary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "全部歌曲 (${tracks.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                if (tracks.isNotEmpty()) {
                    TextButton(onClick = { playbackCoordinator.setQueueAndPlay(tracks, 0) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("播放全部")
                    }
                }
            }

            if (tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.LibraryMusic,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "未找到匹配的本地歌曲" else "曲库暂无歌曲",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { triggerScan() }) {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("立即扫描手机歌曲")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    itemsIndexed(tracks) { index, track ->
                        TrackRowItem(
                            track = track,
                            isCurrentTrack = (track.id == currentPlayingTrack?.id),
                            isPlaying = isPlaybackPlaying,
                            onClick = { playbackCoordinator.setQueueAndPlay(tracks, index) },
                            onPlayNext = { playbackCoordinator.playNext(track) },
                            onAddToQueue = { playbackCoordinator.addToQueue(track) },
                            onToggleFavorite = {
                                scope.launch {
                                    val isFav = playlistRepository.toggleFavorite(track.id)
                                    Toast.makeText(context, if (isFav) "已添加至我喜欢" else "已取消喜欢", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onAddToPlaylist = {
                                trackForPlaylist = track
                            },
                            onDownloadTrack = {
                                val hasOnline = (track.platform != null && track.platformSongId != null) || track.id.startsWith("online_")
                                if (hasOnline) {
                                    trackForDownload = track
                                } else {
                                    Toast.makeText(context, "本地歌曲无需下载", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDeleteDownload = {
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
                            onDelete = {
                                trackToDelete = track
                                deleteLocalFile = false
                            }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (trackToDelete != null) {
        val track = trackToDelete!!
        AlertDialog(
            onDismissRequest = {
                trackToDelete = null
                deleteLocalFile = false
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("删除歌曲") },
            text = {
                Column {
                    Text("确定要从曲库中删除《${track.title}》吗？")
                    if (track.localUri != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { deleteLocalFile = !deleteLocalFile }
                        ) {
                            Checkbox(
                                checked = deleteLocalFile,
                                onCheckedChange = { deleteLocalFile = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "同时删除本地源文件",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = track
                        val shouldDeleteFile = deleteLocalFile
                        trackToDelete = null
                        deleteLocalFile = false
                        scope.launch {
                            playbackCoordinator.removeTrackFromQueue(toDelete.id)
                            val success = libraryRepository.deleteTrack(toDelete.id, shouldDeleteFile)
                            if (success) {
                                Toast.makeText(context, "已从曲库中删除", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    trackToDelete = null
                    deleteLocalFile = false
                }) {
                    Text("取消")
                }
            }
        )
    }

    // Add to playlist dialog
    if (trackForPlaylist != null) {
        val t = trackForPlaylist!!
        AlertDialog(
            onDismissRequest = { trackForPlaylist = null },
            title = { Text("加入歌单") },
            text = {
                if (allPlaylists.isEmpty()) {
                    Text("暂无歌单")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(allPlaylists) { pl ->
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRowItem(
    track: Track,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownloadTrack: () -> Unit,
    onDeleteDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrentTrack: Boolean = false,
    isPlaying: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (isCurrentTrack) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent
        ),
        headlineContent = {
            Text(
                text = track.title,
                fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClick() },
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
                            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(24.dp))
                        }
                    }
                }
                if (isCurrentTrack) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (track.isFavorite) Color(0xFFFF4081) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

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
                                onPlayNext()
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("添加到队尾") },
                            onClick = {
                                onAddToQueue()
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("加入歌单") },
                            onClick = {
                                showMenu = false
                                onAddToPlaylist()
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("下载歌曲") },
                            onClick = {
                                showMenu = false
                                onDownloadTrack()
                            },
                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("删除下载") },
                            onClick = {
                                showMenu = false
                                onDeleteDownload()
                            },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        },
        modifier = modifier.combinedClickable(
            onClick = { onClick() },
            onLongClick = { showMenu = true }
        )
    )
}
