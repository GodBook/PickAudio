package com.pickaudio.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pickaudio.data.db.PlaylistEntity
import com.pickaudio.data.model.Platform
import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.data.model.Track
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.data.repository.PlaylistRepository
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.online.NetEaseSearchAdapter
import com.pickaudio.online.QqMusicSearchAdapter
import com.pickaudio.playback.PlaybackCoordinator
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    userPreferences: UserPreferences,
    playbackCoordinator: PlaybackCoordinator,
    downloadCoordinator: DownloadCoordinator,
    playlistRepository: PlaylistRepository,
    sourceManager: LxSourceManager,
    onNavigateToSourceManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    var query by remember { mutableStateOf("") }
    var selectedPlatform by remember { mutableStateOf(Platform.ALL) }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchSongItem>>(emptyList()) }

    val history by userPreferences.searchHistory.collectAsState(initial = emptyList())
    val playlists by playlistRepository.getAllPlaylists().collectAsState(initial = emptyList())

    // Dialog state
    var songForDownload by remember { mutableStateOf<SearchSongItem?>(null) }
    var songForPlaylist by remember { mutableStateOf<SearchSongItem?>(null) }
    var showNoSourceDialog by remember { mutableStateOf(false) }

    fun performSearch(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return
        query = trimmed
        keyboardController?.hide()
        scope.launch {
            userPreferences.addSearchHistory(trimmed)
            isSearching = true
            searchResults = emptyList()
            try {
                when (selectedPlatform) {
                    Platform.NETEASE -> {
                        searchResults = NetEaseSearchAdapter.search(trimmed)
                    }
                    Platform.QQ -> {
                        searchResults = QqMusicSearchAdapter.search(trimmed)
                    }
                    Platform.ALL -> {
                        val wyDeferred = async { try { NetEaseSearchAdapter.search(trimmed) } catch (e: Exception) { emptyList() } }
                        val txDeferred = async { try { QqMusicSearchAdapter.search(trimmed) } catch (e: Exception) { emptyList() } }
                        val wyList = wyDeferred.await()
                        val txList = txDeferred.await()

                        // Interleave results
                        val combined = mutableListOf<SearchSongItem>()
                        val maxLen = maxOf(wyList.size, txList.size)
                        for (i in 0 until maxLen) {
                            if (i < wyList.size) combined.add(wyList[i])
                            if (i < txList.size) combined.add(txList[i])
                        }
                        searchResults = combined
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isSearching = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("在线搜索", fontWeight = FontWeight.Bold) }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Input Field
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("输入歌曲、歌手或专辑名") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "清除")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { performSearch(query) }),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Platform Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Platform.entries.forEach { p ->
                    FilterChip(
                        selected = selectedPlatform == p,
                        onClick = {
                            selectedPlatform = p
                            if (query.isNotBlank()) performSearch(query)
                        },
                        label = { Text(p.displayName) }
                    )
                }
            }

            // Search History Chips (when no results yet)
            if (searchResults.isEmpty() && !isSearching && history.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "搜索历史",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { scope.launch { userPreferences.clearSearchHistory() } }) {
                            Text("清空", fontSize = 12.sp)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        history.forEach { item ->
                            InputChip(
                                selected = false,
                                onClick = { performSearch(item) },
                                label = { Text(item) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "删除",
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable {
                                                scope.launch { userPreferences.removeSearchHistory(item) }
                                            }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Loading indicator
            if (isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // Results List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(searchResults) { item ->
                    var showMenu by remember { mutableStateOf(false) }

                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item.title,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                // Platform badge
                                Surface(
                                    color = if (item.platform == "wy") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = if (item.platform == "wy") "网易" else "QQ",
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        },
                        supportingContent = {
                            Text(
                                text = "${item.artist} · ${item.album}",
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
                                if (!item.coverUrl.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = item.coverUrl,
                                        contentDescription = item.title,
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
                            }
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("在线播放") },
                                        onClick = {
                                            showMenu = false
                                            playOnlineTrack(item, playbackCoordinator, { showNoSourceDialog = true })
                                        },
                                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("下载") },
                                        onClick = {
                                            showMenu = false
                                            songForDownload = item
                                        },
                                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("加入歌单") },
                                        onClick = {
                                            showMenu = false
                                            songForPlaylist = item
                                        },
                                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable {
                            playOnlineTrack(item, playbackCoordinator, { showNoSourceDialog = true })
                        }
                    )
                }
            }
        }
    }

    // Download Quality Selection Dialog
    if (songForDownload != null) {
        val s = songForDownload!!
        AlertDialog(
            onDismissRequest = { songForDownload = null },
            title = { Text("选择下载音质") },
            text = {
                Column {
                    Text(text = "${s.title} - ${s.artist}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    s.availableQualities.forEach { q ->
                        FilledTonalButton(
                            onClick = {
                                val trackId = "online_${s.platform}_${s.songId}"
                                scope.launch {
                                    downloadCoordinator.enqueueDownload(
                                        trackId = trackId,
                                        title = s.title,
                                        artist = s.artist,
                                        album = s.album,
                                        coverUri = s.coverUrl,
                                        platform = s.platform,
                                        platformSongId = s.songId,
                                        quality = q
                                    )
                                    Toast.makeText(context, "已加入下载任务", Toast.LENGTH_SHORT).show()
                                }
                                songForDownload = null
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
                TextButton(onClick = { songForDownload = null }) { Text("取消") }
            }
        )
    }

    // Add To Playlist Dialog
    if (songForPlaylist != null) {
        val s = songForPlaylist!!
        AlertDialog(
            onDismissRequest = { songForPlaylist = null },
            title = { Text("加入歌单") },
            text = {
                val userPlaylists = playlists.filter { !it.isSystem }
                if (userPlaylists.isEmpty()) {
                    Text("暂无自建歌单，请先创建歌单")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(userPlaylists) { pl ->
                            ListItem(
                                headlineContent = { Text(pl.name) },
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        try {
                                            val trackId = "online_${s.platform}_${s.songId}"
                                            val track = com.pickaudio.data.model.Track(
                                                id = trackId,
                                                title = s.title,
                                                artist = s.artist,
                                                album = s.album,
                                                durationMs = s.durationMs,
                                                coverUri = s.coverUrl,
                                                platform = s.platform,
                                                platformSongId = s.songId
                                            )
                                            playlistRepository.ensureTrackAndAddToPlaylist(pl.id, track)
                                            Toast.makeText(context, "已添加到歌单「${pl.name}」", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    songForPlaylist = null
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { songForPlaylist = null }) { Text("取消") }
            }
        )
    }

    // Guide Dialog when no source configured
    if (showNoSourceDialog) {
        AlertDialog(
            onDismissRequest = { showNoSourceDialog = false },
            title = { Text("未配置音乐源") },
            text = { Text("在线播放与下载需要先导入并启用兼容的 LX 自定义音源脚本。是否前往音乐源管理？") },
            confirmButton = {
                Button(onClick = {
                    showNoSourceDialog = false
                    onNavigateToSourceManager()
                }) {
                    Text("前往配置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoSourceDialog = false }) { Text("稍后") }
            }
        )
    }
}

private fun playOnlineTrack(
    item: SearchSongItem,
    coordinator: PlaybackCoordinator,
    onNoSource: () -> Unit
) {
    val trackId = "online_${item.platform}_${item.songId}"
    val track = Track(
        id = trackId,
        title = item.title,
        artist = item.artist,
        album = item.album,
        durationMs = item.durationMs,
        coverUri = item.coverUrl,
        platform = item.platform,
        platformSongId = item.songId
    )
    coordinator.setQueueAndPlay(listOf(track), 0)
}
