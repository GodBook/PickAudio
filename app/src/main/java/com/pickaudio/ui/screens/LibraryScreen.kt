package com.pickaudio.ui.screens

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    libraryRepository: LibraryRepository, playlistRepository: PlaylistRepository,
    playbackCoordinator: PlaybackCoordinator, downloadCoordinator: DownloadCoordinator? = null,
    onNavigateToPlaylistDetail: (String) -> Unit, onNavigateToDownload: () -> Unit,
    onNavigateToSettings: () -> Unit, modifier: Modifier = Modifier, onNavigateToSource: () -> Unit = onNavigateToSettings,
    onNavigateToSearch: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as PickAudioApplication
    val prefs = app.userPreferences
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val browser: LibraryBrowserViewModel = viewModel(factory = remember(libraryRepository, prefs) { LibraryBrowserViewModel.Factory(libraryRepository, prefs) })
    val browserState by browser.state.collectAsStateWithLifecycle()
    val allTracks = browserState.allTracks
    val filterShort by prefs.filterShortAudio.collectAsStateWithLifecycle(initialValue = true)
    val importState by libraryRepository.importProgress.collectAsStateWithLifecycle()
    val current by playbackCoordinator.currentTrack.collectAsStateWithLifecycle()
    val playing by playbackCoordinator.isPlaying.collectAsStateWithLifecycle()
    val pendingDownloads by app.downloadCoordinator.pendingCount.collectAsStateWithLifecycle(initialValue = 0)
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
    var candidateTracks by remember { mutableStateOf<List<Track>?>(null) }
    androidx.activity.compose.BackHandler(enabled = (selecting || group != null) && !com.pickaudio.LocalPlayerOverlayVisible.current) {
        if (selecting) { selecting = false; selectedIds = emptySet() } else group = null
    }
    fun message(value: String) { scope.launch { snack.showSnackbar(value) } }
    val relinkFile = rememberRelinkFileAction(libraryRepository, { message("文件已关联，歌曲编号和原有歌单保留") }, ::message)
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
    LaunchedEffect(query, filter, view, group, sort) { browser.browse(LibraryQuery(query, view, group, sort, filter)) }
    val groups = browserState.groups
    val tracks = browserState.tracks
    val selected = remember(allTracks, selectedIds) { allTracks.filter { it.id in selectedIds } }
    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_001), fontWeight = FontWeight.SemiBold)
                if (LocalDensity.current.fontScale <= 1.3f) Text("${allTracks.size} 首主动添加的音乐", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), actions = {
            IconButton(onClick = onNavigateToDownload) {
                BadgedBox(badge = { if (pendingDownloads > 0) Badge { Text(pendingDownloads.toString()) } }) { Icon(Icons.Default.Download, stringResource(com.pickaudio.R.string.ui_libraryscreen_019)) }
            }
            IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, stringResource(com.pickaudio.R.string.settings_title)) }
        })
    }, snackbarHost = { SnackbarHost(snack) }, modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "library_controls") {
              Column(Modifier.fillMaxWidth()) {
            MusicSearchField(query, { query = it }, stringResource(com.pickaudio.R.string.ui_libraryscreen_002),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Box {
                    FilledTonalButton(onClick = { importMenu = true }, enabled = !importState.running) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_003))
                    }
                    DropdownMenu(importMenu, { importMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_004)) }, onClick = { importMenu = false; scan() })
                        DropdownMenuItem(text = { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_005)) }, onClick = { importMenu = false; filePicker.launch(arrayOf("audio/*")) })
                        DropdownMenuItem(text = { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_006)) }, onClick = { importMenu = false; folderPicker.launch(null) })
                    }
                }
                TextButton(onClick = { selecting = !selecting; selectedIds = emptySet() }) { Text(if (selecting) "完成" else "多选") }
            }
            if (allTracks.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { browseMenu = true }) { Text(if (view == "全部") "全部音乐" else view, maxLines = 1); Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) }
                    DropdownMenu(browseMenu, { browseMenu = false }) {
                        listOf("全部", "最近播放", "歌手", "专辑", "文件夹").forEach { value ->
                            DropdownMenuItem(text = { Text(if (value == "全部") "全部音乐" else value) }, onClick = { view = value; group = null; browseMenu = false }, trailingIcon = { if (view == value) Icon(Icons.Default.Check, null) })
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { filterMenu = true }) { Text(if (filter == "全部") "全部来源" else filter, maxLines = 1); Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) }
                    DropdownMenu(filterMenu, { filterMenu = false }) {
                        listOf("全部", "本地", "已下载", "在线", "待修复").forEach { value ->
                            DropdownMenuItem(text = { Text(if (value == "全部") "全部来源" else value) }, onClick = { filter = value; filterMenu = false }, trailingIcon = { if (filter == value) Icon(Icons.Default.Check, null) })
                        }
                    }
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "排序 · $sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf("最近添加", "歌曲名称", "歌手名称", "时长").forEach { label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { sort = label; sortMenu = false }, trailingIcon = { if (sort == label) Icon(Icons.Default.Check, null) })
                        }
                    }
                }
            }
            if (filter == "待修复") FlowRow(Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { scope.launch { libraryRepository.refreshAvailability(); message("文件可用性已检查") } }) { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_008)) }
                TextButton(onClick = { folderPicker.launch(null) }) { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_009)) }
                TextButton(onClick = { candidateTracks = tracks.filter { it.repairReason != null } }) { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_010)) }
            }
            if (importState.label.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
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
            if (selecting) SelectionActions(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                TextButton(onClick = { selectedIds = tracks.map { it.id }.toSet() }) { Text("全选 (${selectedIds.size})") }
                TextButton(onClick = { playlistTracks = selected }, enabled = selected.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.action_add_playlist)) }
                TextButton(onClick = { downloadTracks = selected }, enabled = selected.any { it.platform != null }) { Text(stringResource(com.pickaudio.R.string.action_download)) }
                TextButton(onClick = { deleteTracks = selected; deleteFiles = false }, enabled = selected.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_011)) }
            }
            if (group != null) TextButton(onClick = { group = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text(groups.find { it.key == group }?.label ?: "返回分类") }
            browserState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (allTracks.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("共 ${tracks.size} 首", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tracks.isNotEmpty()) TextButton(onClick = { playbackCoordinator.setQueueAndPlay(tracks) }) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_012))
                }
            }
              }
            }
                if (tracks.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(80.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.LibraryMusic, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary) }
                        }
                        Text(if (allTracks.isEmpty()) "留住你想听的音乐" else "没有匹配歌曲", style = MaterialTheme.typography.titleLarge)
                        Text(if (allTracks.isEmpty()) "在搜索结果中点击“加入曲库”，\n或导入手机里的音频文件。" else "试试清除搜索或调整筛选条件。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 280.dp).padding(bottom = 8.dp))
                        if (allTracks.isEmpty()) {
                            Button(onClick = onNavigateToSearch) { Icon(Icons.Default.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("搜索并添加音乐") }
                            TextButton(onClick = ::scan, enabled = !importState.running) { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_004)) }
                        }
                        else TextButton(onClick = { query = ""; filter = "全部"; view = "全部"; group = null }) { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_013)) }
                    }
                }
                if (view in listOf("歌手", "专辑", "文件夹") && group == null) {
                    items(groups, key = { it.key }) { category ->
                        ListItem(headlineContent = { Text(category.label) }, supportingContent = { Text("${category.count} 首 · ${category.preview}") },
                            leadingContent = { Icon(if (view == "文件夹") Icons.Default.Folder else if (view == "专辑") Icons.Default.Album else Icons.Default.Person, null) },
                            modifier = Modifier.clickable { group = category.key })
                    }
                } else items(tracks, key = { it.id }) { track ->
                    val actions = mutableListOf(
                        TrackMenuAction("下一首播放") { playbackCoordinator.playNext(track); message("已加入下一首") },
                        TrackMenuAction("添加到队尾") { playbackCoordinator.addToQueue(track); message("已加入队尾") },
                        TrackMenuAction("加入歌单") { playlistTracks = listOf(track) }
                    )
                    if (track.platform != null) actions.add(TrackMenuAction("下载歌曲") { downloadTracks = listOf(track) })
                    if (track.repairReason != null) actions.add(TrackMenuAction("关联音频文件") { relinkFile(track, null) })
                    if (track.sourceType == "DOWNLOADED") actions.add(TrackMenuAction("删除下载文件", true) { deleteDownload = track })
                    actions.add(TrackMenuAction("移出音乐库", true) { deleteTracks = listOf(track); deleteFiles = false })
                    MusicTrackRow(track, current?.id == track.id, playing, selecting, track.id in selectedIds,
                        onClick = { playbackCoordinator.setQueueAndPlay(tracks, tracks.indexOf(track)) }, onSelect = { select(track.id) },
                        onFavorite = { scope.launch { playlistRepository.toggleFavorite(track.id) } }, actions = actions)
                }
        }
    }
    playlistTracks?.let { PlaylistPickerDialog(it, playlistRepository, { playlistTracks = null }, ::message) }
    downloadTracks?.let { DownloadQualityDialog(it, app.downloadCoordinator, app.sourceManager, prefs, { downloadTracks = null },
        onOpenSource = onNavigateToSource, onQueued = { message(it.summary) }) }
    deleteTracks?.let { list ->
        AlertDialog(onDismissRequest = { deleteTracks = null }, title = { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_014)) }, text = {
            Column {
                Text("将这 ${list.size} 首歌曲移出曲库，歌单、喜欢和播放队列保留。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(deleteFiles, { deleteFiles = it })
                    Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_015))
                }
                if (deleteFiles) Text("同时删除歌曲记录及其歌单关联。" + stringResource(com.pickaudio.R.string.ui_libraryscreen_016), color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = {
            deleteTracks = null
            scope.launch {
                if (!deleteFiles) {
                    libraryRepository.removeTracks(list.map { it.id })
                    selectedIds = emptySet(); selecting = false
                    message("已移出曲库，歌单和喜欢保留")
                    return@launch
                }
                var failures = 0
                var removedFiles = 0
                var alreadyMissing = 0
                val reasons = mutableListOf<String>()
                list.forEach {
                    val result = libraryRepository.deleteTrackWithReport(it.id, deleteFiles)
                    if (!result.recordRemoved) failures++
                    removedFiles += result.deletedFiles
                    alreadyMissing += result.missingFiles
                    result.failedFiles.firstOrNull()?.reason?.let(reasons::add)
                    result.error?.let(reasons::add)
                }
                selectedIds = emptySet(); selecting = false
                message(if (failures == 0) "已移出音乐库" + if (deleteFiles) "，删除 $removedFiles 个文件，$alreadyMissing 个已不存在" else ""
                    else "${failures} 首记录保留以便重试。${reasons.firstOrNull() ?: "请检查文件授权"}")
            }
        }) { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_017), color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleteTracks = null }) { Text(stringResource(com.pickaudio.R.string.action_cancel)) } })
    }
    deleteDownload?.let { track ->
        AlertDialog(onDismissRequest = { deleteDownload = null }, title = { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_018)) }, text = { Text("删除《${track.title}》的本地下载，歌单和收藏保留。") },
            confirmButton = { TextButton(onClick = { deleteDownload = null; scope.launch { message(if (app.downloadCoordinator.deleteDownloadForTrack(track.id)) "下载文件已删除" else "未能删除，请检查文件授权") } }) { Text(stringResource(com.pickaudio.R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleteDownload = null }) { Text(stringResource(com.pickaudio.R.string.action_cancel)) } })
    }
    candidateTracks?.let { RelinkCandidatesDialog(it, libraryRepository, { candidateTracks = null }) }
}
