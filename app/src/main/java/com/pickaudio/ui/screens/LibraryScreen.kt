package com.pickaudio.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pickaudio.PickAudioApplication
import com.pickaudio.data.model.Track
import com.pickaudio.data.repository.LibraryRepository
import com.pickaudio.data.repository.PlaylistRepository
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.playback.PlaybackCoordinator
import com.pickaudio.ui.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    libraryRepository: LibraryRepository, playlistRepository: PlaylistRepository,
    playbackCoordinator: PlaybackCoordinator, downloadCoordinator: DownloadCoordinator? = null,
    onNavigateToPlaylistDetail: (String) -> Unit, onNavigateToDownload: () -> Unit,
    onNavigateToSettings: () -> Unit, modifier: Modifier = Modifier, onNavigateToSource: () -> Unit = onNavigateToSettings
) {
    val context = LocalContext.current
    val app = context.applicationContext as PickAudioApplication
    val prefs = app.userPreferences
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val allTracks by libraryRepository.getAllTracks().collectAsState(initial = emptyList())
    val recentIds by prefs.recentTrackIds.collectAsState(initial = emptyList())
    val filterShort by prefs.filterShortAudio.collectAsState(initial = true)
    val importState by libraryRepository.importProgress.collectAsState()
    val current by playbackCoordinator.currentTrack.collectAsState()
    val playing by playbackCoordinator.isPlaying.collectAsState()
    val downloads by app.downloadCoordinator.getAllTasks().collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf("全部") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf("最近添加") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var browseMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var playlistTracks by remember { mutableStateOf<List<Track>?>(null) }
    var downloadTracks by remember { mutableStateOf<List<Track>?>(null) }
    var deleteTracks by remember { mutableStateOf<List<Track>?>(null) }
    var deleteFiles by remember { mutableStateOf(false) }
    var deleteDownload by remember { mutableStateOf<Track?>(null) }
    fun message(value: String) { scope.launch { snack.showSnackbar(value) } }
    fun select(id: String) { selecting = true; selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) libraryRepository.startFileImport(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { libraryRepository.startDirectoryImport(it) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) libraryRepository.startScan(filterShort) else message("未获得音频读取权限，仍可通过导入文件或文件夹添加歌曲")
    }
    fun scan() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED)
            libraryRepository.startScan(filterShort)
        else permission.launch(Manifest.permission.READ_MEDIA_AUDIO)
    }
    val matching = remember(allTracks, query, filter, view, recentIds) {
        allTracks.filter { track ->
            (query.isBlank() || listOf(track.title, track.artist, track.album, track.folderName).any { it.contains(query, true) }) &&
                when (filter) { "本地" -> track.localUri != null; "已下载" -> track.sourceType == "DOWNLOADED"; "在线" -> track.localUri == null && track.platform != null; else -> true } &&
                (view != "最近播放" || track.id in recentIds)
        }
    }
    fun groupName(track: Track): String = when (view) { "歌手" -> track.artist; "专辑" -> track.album; "文件夹" -> track.folderName.ifBlank { if (track.localUri != null) "未分类本地文件" else "在线或待关联文件" }; else -> "" }
    val groups = matching.groupBy(::groupName)
    val tracks = matching.filter { group == null || groupName(it) == group }.let { list ->
        if (view == "最近播放") list.sortedBy { recentIds.indexOf(it.id) }
        else when (sort) { "歌曲名称" -> list.sortedBy { it.title.lowercase() }; "歌手名称" -> list.sortedBy { it.artist.lowercase() }; "时长" -> list.sortedByDescending { it.durationMs }; else -> list.sortedByDescending { it.createdAt } }
    }
    val selected = allTracks.filter { it.id in selectedIds }
    Scaffold(topBar = {
        TopAppBar(title = { Text("音乐库", fontWeight = FontWeight.SemiBold) }, actions = {
            IconButton(onClick = onNavigateToDownload) {
                BadgedBox(badge = { val pending = downloads.count { it.status != "COMPLETED" }; if (pending > 0) Badge { Text(pending.toString()) } }) { Icon(Icons.Default.Download, "下载管理") }
            }
            IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, "设置") }
        })
    }, snackbarHost = { SnackbarHost(snack) }, modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "library_controls") {
              Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("搜索歌曲、歌手、专辑或文件夹", maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清除搜索") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    FilledTonalButton(onClick = { importMenu = true }, enabled = !importState.running) {
                        Icon(Icons.Default.Add, null); Text("导入音乐")
                    }
                    DropdownMenu(importMenu, { importMenu = false }) {
                        DropdownMenuItem(text = { Text("扫描手机歌曲") }, onClick = { importMenu = false; scan() })
                        DropdownMenuItem(text = { Text("选择音频文件") }, onClick = { importMenu = false; filePicker.launch(arrayOf("audio/*")) })
                        DropdownMenuItem(text = { Text("选择文件夹") }, onClick = { importMenu = false; folderPicker.launch(null) })
                    }
                }
                TextButton(onClick = { selecting = !selecting; selectedIds = emptySet() }) { Text(if (selecting) "完成" else "多选") }
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text("排序") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf("最近添加", "歌曲名称", "歌手名称", "时长").forEach { label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { sort = label; sortMenu = false }, trailingIcon = { if (sort == label) Icon(Icons.Default.Check, null) })
                        }
                    }
                }
            }
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    OutlinedButton(onClick = { browseMenu = true }) { Text(if (view == "全部") "全部音乐" else view); Icon(Icons.Default.ArrowDropDown, null) }
                    DropdownMenu(browseMenu, { browseMenu = false }) {
                        listOf("全部", "最近播放", "歌手", "专辑", "文件夹").forEach { value ->
                            DropdownMenuItem(text = { Text(if (value == "全部") "全部音乐" else value) }, onClick = { view = value; group = null; browseMenu = false }, trailingIcon = { if (view == value) Icon(Icons.Default.Check, null) })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { filterMenu = true }) { Text(if (filter == "全部") "全部来源" else filter); Icon(Icons.Default.ArrowDropDown, null) }
                    DropdownMenu(filterMenu, { filterMenu = false }) {
                        listOf("全部", "本地", "已下载", "在线").forEach { value ->
                            DropdownMenuItem(text = { Text(if (value == "全部") "全部来源" else value) }, onClick = { filter = value; filterMenu = false }, trailingIcon = { if (filter == value) Icon(Icons.Default.Check, null) })
                        }
                    }
                }
            }
            if (importState.label.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(importState.message ?: "${importState.label} · ${importState.processed}${importState.total?.let { " / $it" }.orEmpty()}")
                        Text("新增 ${importState.imported} · 跳过 ${importState.skipped} · 失败 ${importState.failed}", style = MaterialTheme.typography.bodySmall)
                        if (importState.running) {
                            if (importState.total != null && importState.total!! > 0) LinearProgressIndicator(progress = { importState.processed.toFloat() / importState.total!! }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        TextButton(onClick = { if (importState.running) libraryRepository.cancelImport() else libraryRepository.dismissImportResult() }) { Text(if (importState.running) "取消导入" else "收起") }
                    }
                }
            }
            if (selecting) FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { selectedIds = tracks.map { it.id }.toSet() }) { Text("全选 (${selectedIds.size})") }
                TextButton(onClick = { playlistTracks = selected }, enabled = selected.isNotEmpty()) { Text("加入歌单") }
                TextButton(onClick = { downloadTracks = selected }, enabled = selected.any { it.platform != null }) { Text("下载") }
                TextButton(onClick = { deleteTracks = selected; deleteFiles = false }, enabled = selected.isNotEmpty()) { Text("移出曲库") }
            }
            if (group != null) TextButton(onClick = { group = null }) { Icon(Icons.Default.ArrowBack, null); Text(group!!) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("共 ${tracks.size} 首", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tracks.isNotEmpty()) TextButton(onClick = { playbackCoordinator.setQueueAndPlay(tracks) }) { Text("播放全部") }
            }
              }
            }
                if (tracks.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LibraryMusic, null, modifier = Modifier.size(48.dp))
                        Text(if (allTracks.isEmpty()) "添加第一首音乐" else "没有匹配歌曲", style = MaterialTheme.typography.titleMedium)
                        Text(if (allTracks.isEmpty()) "扫描手机歌曲，或通过“导入音乐”选择文件和文件夹。" else "试试清除搜索或调整筛选条件。", modifier = Modifier.padding(vertical = 12.dp))
                        if (allTracks.isEmpty()) Button(onClick = ::scan, enabled = !importState.running) { Text("扫描手机歌曲") }
                        else TextButton(onClick = { query = ""; filter = "全部"; view = "全部"; group = null }) { Text("清除筛选") }
                    }
                }
                if (view in listOf("歌手", "专辑", "文件夹") && group == null) {
                    items(groups.keys.sorted(), key = { it }) { name ->
                        ListItem(headlineContent = { Text(name) }, supportingContent = { Text("${groups[name].orEmpty().size} 首") },
                            leadingContent = { Icon(if (view == "文件夹") Icons.Default.Folder else if (view == "专辑") Icons.Default.Album else Icons.Default.Person, null) },
                            modifier = Modifier.clickable { group = name })
                    }
                } else items(tracks, key = { it.id }) { track ->
                    val actions = mutableListOf(
                        TrackMenuAction("下一首播放") { playbackCoordinator.playNext(track); message("已加入下一首") },
                        TrackMenuAction("添加到队尾") { playbackCoordinator.addToQueue(track); message("已加入队尾") },
                        TrackMenuAction("加入歌单") { playlistTracks = listOf(track) }
                    )
                    if (track.platform != null) actions.add(TrackMenuAction("下载歌曲") { downloadTracks = listOf(track) })
                    if (track.sourceType == "DOWNLOADED") actions.add(TrackMenuAction("删除下载文件", true) { deleteDownload = track })
                    actions.add(TrackMenuAction("移出音乐库", true) { deleteTracks = listOf(track); deleteFiles = false })
                    MusicTrackRow(track, current?.id == track.id, playing, selecting, track.id in selectedIds,
                        onClick = { playbackCoordinator.setQueueAndPlay(tracks, tracks.indexOf(track)) }, onSelect = { select(track.id) },
                        onFavorite = { scope.launch { playlistRepository.toggleFavorite(track.id) } }, actions = actions)
                }
        }
    }
    playlistTracks?.let { PlaylistPickerDialog(it, playlistRepository, { playlistTracks = null }, { message("已加入「$it」") }) }
    downloadTracks?.let { DownloadQualityDialog(it, app.downloadCoordinator, app.sourceManager, prefs, { downloadTracks = null },
        onOpenSource = onNavigateToSource, onQueued = { message("已加入下载任务") }) }
    deleteTracks?.let { list ->
        AlertDialog(onDismissRequest = { deleteTracks = null }, title = { Text("移出音乐库") }, text = {
            Column {
                Text("移出这 ${list.size} 首歌曲及其歌单关联。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(deleteFiles, { deleteFiles = it })
                    Text("同时删除本地音频文件")
                }
                if (deleteFiles) Text("音频文件删除后无法通过撤销恢复。", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = {
            deleteTracks = null
            scope.launch {
                var failures = 0
                list.forEach { playbackCoordinator.removeTrackFromQueue(it.id); if (!libraryRepository.deleteTrack(it.id, deleteFiles)) failures++ }
                selectedIds = emptySet(); selecting = false
                message(if (failures == 0) "已移出音乐库" else "${failures} 首处理失败，请检查文件授权")
            }
        }) { Text("移出", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleteTracks = null }) { Text("取消") } })
    }
    deleteDownload?.let { track ->
        AlertDialog(onDismissRequest = { deleteDownload = null }, title = { Text("删除下载文件") }, text = { Text("删除《${track.title}》的本地下载，歌单和收藏保留。") },
            confirmButton = { TextButton(onClick = { deleteDownload = null; scope.launch { message(if (app.downloadCoordinator.deleteDownloadForTrack(track.id)) "下载文件已删除" else "未能删除，请检查文件授权") } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteDownload = null }) { Text("取消") } })
    }
}
