package com.pickaudio.ui.screens

import androidx.compose.ui.res.stringResource

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pickaudio.PickAudioApplication
import com.pickaudio.data.db.DownloadTaskEntity
import com.pickaudio.data.model.*
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.download.DownloadCommand
import com.pickaudio.ui.components.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadScreen(downloadCoordinator: DownloadCoordinator, onBack: () -> Unit, modifier: Modifier = Modifier, onOpenSource: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as PickAudioApplication
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pageLimit by remember(tab) { mutableIntStateOf(100) }
    var loadRetry by remember { mutableIntStateOf(0) }
    var loadError by remember(tab, pageLimit, loadRetry) { mutableStateOf<String?>(null) }
    val loadedTasks by remember(tab, pageLimit, loadRetry) {
        val requestedTab = tab
        val requestedLimit = pageLimit
        downloadCoordinator.getTasksForTab(requestedTab, requestedLimit)
            .map<List<DownloadTaskEntity>, Triple<Int, Int, List<DownloadTaskEntity>>?> { Triple(requestedTab, requestedLimit, it) }
            .catch { loadError = "下载记录暂时无法读取，请重试"; emit(Triple(requestedTab, requestedLimit, emptyList())) }
    }.collectAsStateWithLifecycle(initialValue = null)
    val page = loadedTasks?.takeIf { it.first == tab && it.second == pageLimit }
    val counts by remember(loadRetry) { downloadCoordinator.taskCounts.catch { emit(emptyMap()) } }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val libraryIds by app.libraryRepository.libraryTrackIds.collectAsStateWithLifecycle(initialValue = emptyList())
    val wifiOnly by app.userPreferences.wifiOnlyDownload.collectAsStateWithLifecycle(initialValue = true)
    val candidates by downloadCoordinator.versionCandidates.collectAsStateWithLifecycle()
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    androidx.activity.compose.BackHandler(enabled = selecting && !com.pickaudio.LocalPlayerOverlayVisible.current) { selecting = false; selectedIds = emptySet() }
    LaunchedEffect(tab, pageLimit) { if (tab == 1) downloadCoordinator.refreshCompletedFiles(pageLimit) }
    var versionTask by remember { mutableStateOf<DownloadTaskEntity?>(null) }
    var qualityTrack by remember { mutableStateOf<Track?>(null) }
    var cancelIds by remember { mutableStateOf<List<String>?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sizes = listOf((DownloadCoordinator.ACTIVE_STATES + "PENDING").sumOf { counts[it] ?: 0 },
        counts["COMPLETED"] ?: 0, listOf("PAUSED", "FAILED", "CANCELLED").sumOf { counts[it] ?: 0 })
    val shown = page?.third.orEmpty()
    val selected = shown.filter { it.id in selectedIds }
    fun message(value: String) { scope.launch { snack.showSnackbar(value) } }
    var commandBusy by remember { mutableStateOf(false) }
    fun control(ids: List<String>, command: DownloadCommand) {
        if (commandBusy) return
        commandBusy = true
        scope.launch {
            try {
                val result = downloadCoordinator.controlBatch(ids, command)
                selectedIds = emptySet()
                commandBusy = false
                snack.showSnackbar(result.summary)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { snack.showSnackbar(e.message ?: "操作失败，请重试") }
            finally { commandBusy = false }
        }
    }
    fun track(task: DownloadTaskEntity) = Track(task.trackId, task.title, task.artist, task.album, task.durationMs,
        task.coverUri, localUri = task.targetUri, platform = task.platform, platformSongId = task.platformSongId, sourceType = if (task.status == "COMPLETED") "DOWNLOADED" else "")
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_019)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(com.pickaudio.R.string.action_back)) } },
            actions = { TextButton(onClick = { selecting = !selecting; selectedIds = emptySet() }) { Text(if (selecting) "完成" else "多选") } })
    }, snackbarHost = { SnackbarHost(snack) }, modifier = modifier.fillMaxSize()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(if (wifiOnly) "仅在 Wi-Fi 下载 · 文件保存在 Music/PickAudio" else "允许移动网络下载 · 文件保存在 Music/PickAudio",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            TabRow(tab) {
                listOf("进行中", "已完成", "需处理").forEachIndexed { index, label ->
                    Tab(tab == index, onClick = { tab = index; selectedIds = emptySet() }, text = { Text("$label (${sizes[index]})") })
                }
            }
            if (selecting) FlowRow(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { selectedIds = shown.map { it.id }.toSet() }) { Text("全选已加载 (${selectedIds.size})") }
                if (tab == 0) TextButton(onClick = { control(selected.map { it.id }, DownloadCommand.PAUSE) }, enabled = selected.isNotEmpty() && !commandBusy) { Text(stringResource(com.pickaudio.R.string.action_pause)) }
                if (tab == 2) TextButton(onClick = { control(selected.map { it.id }, DownloadCommand.RESUME) }, enabled = selected.isNotEmpty() && !commandBusy) { Text(stringResource(com.pickaudio.R.string.ui_downloadscreen_001)) }
                if (tab != 1) TextButton(onClick = { cancelIds = selected.map { it.id } }, enabled = selected.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.ui_downloadscreen_002)) }
                else TextButton(onClick = { app.playbackCoordinator.addTracksToQueue(selected.map(::track)); message("已加入播放队列 · ${selected.size} 首") }, enabled = selected.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.action_add_queue)) }
            }
            if (commandBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取下载记录…", Modifier.padding(16.dp)) }
                else if (loadError != null) item {
                    Text(loadError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    TextButton(onClick = { loadRetry++ }) { Text("重新读取下载记录") }
                }
                else if (shown.isEmpty()) item { Text(when(tab) { 0 -> "没有待下载任务"; 1 -> "下载完成的音乐会显示在这里"; else -> "暂时没有需要处理的下载" }, modifier = Modifier.padding(16.dp)) }
                items(shown, key = { it.id }) { task ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selecting) Checkbox(task.id in selectedIds, { selectedIds = if (task.id in selectedIds) selectedIds - task.id else selectedIds + task.id })
                                Text(task.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            }
                            Text("${task.artist} · ${task.album}", style = MaterialTheme.typography.bodyMedium)
                            val label = DownloadStatus.entries.find { it.name == task.status }?.label ?: "等待处理"
                            Text("$label · ${task.actualQuality ?: Quality.fromValue(task.targetQuality).label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            if (task.status == "PENDING") Text(if (wifiOnly) "等待 Wi-Fi 或系统调度，可暂停后继续" else "等待系统安排下载", style = MaterialTheme.typography.bodySmall)
                            if (task.status in DownloadCoordinator.ACTIVE_STATES) {
                                if (task.totalBytes > 0) LinearProgressIndicator(progress = { (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            if (task.status != "COMPLETED") {
                                Text("${formatDownloadBytes(task.downloadedBytes)} / ${if (task.totalBytes > 0) formatDownloadBytes(task.totalBytes) else "大小待确定"}", style = MaterialTheme.typography.bodySmall)
                                if (task.bytesPerSecond > 0 && task.status == "DOWNLOADING") Text("${formatDownloadBytes(task.bytesPerSecond)}/s${task.etaSeconds?.let { " · 约 ${it / 60} 分 ${it % 60} 秒" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                                task.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            } else Text("Music/PickAudio · ${formatDownloadBytes(task.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (task.status) {
                                    "COMPLETED" -> {
                                        FilledTonalButton(onClick = { app.playbackCoordinator.addOrPlayTrack(track(task)) }) { Text(stringResource(com.pickaudio.R.string.action_play)) }
                                        TextButton(enabled = task.trackId !in libraryIds, onClick = { scope.launch {
                                            try { app.libraryRepository.addTracks(listOf(track(task))); message("已加入曲库") }
                                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                            catch (e: Exception) { message(e.message ?: "加入曲库失败") }
                                        } }) { Text(if (task.trackId in libraryIds) "已在曲库" else "加入曲库") }
                                        TextButton(onClick = {
                                            try {
                                                val uri = Uri.parse(task.targetUri ?: error("文件地址缺失"))
                                                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri) ?: "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                context.startActivity(Intent.createChooser(intent, "打开音乐文件"))
                                            } catch (e: Exception) { message("无法打开文件，请检查文件是否仍在 Music/PickAudio") }
                                        }) { Text(stringResource(com.pickaudio.R.string.ui_downloadscreen_003)) }
                                    }
                                    "PAUSED", "FAILED" -> {
                                        FilledTonalButton(onClick = { control(listOf(task.id), DownloadCommand.RESUME) }, enabled = !commandBusy) { Text(if (task.status == "FAILED") "重试" else "继续") }
                                        TextButton(onClick = { qualityTrack = track(task) }) { Text(stringResource(com.pickaudio.R.string.ui_downloadscreen_004)) }
                                        TextButton(onClick = onOpenSource) { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_014)) }
                                    }
                                    "PENDING" -> {
                                        if (task.errorMessage != null) FilledTonalButton(onClick = { control(listOf(task.id), DownloadCommand.RESUME) }, enabled = !commandBusy) { Text(stringResource(com.pickaudio.R.string.ui_downloadscreen_005)) }
                                        TextButton(onClick = { control(listOf(task.id), DownloadCommand.PAUSE) }, enabled = !commandBusy) { Text(stringResource(com.pickaudio.R.string.action_pause)) }
                                    }
                                    else -> TextButton(onClick = { control(listOf(task.id), DownloadCommand.PAUSE) }, enabled = !commandBusy) { Text(stringResource(com.pickaudio.R.string.action_pause)) }
                                }
                                if (!candidates[task.id].isNullOrEmpty()) TextButton(onClick = { versionTask = task }) { Text(stringResource(com.pickaudio.R.string.ui_downloadscreen_006)) }
                                if (task.status != "COMPLETED") TextButton(onClick = { cancelIds = listOf(task.id) }) { Text(stringResource(com.pickaudio.R.string.ui_downloadscreen_002)) }
                            }
                        }
                    }
                }
                if (shown.size < sizes[tab]) item { TextButton(onClick = { pageLimit += 100 }) { Text("加载更多（已显示 ${shown.size} / ${sizes[tab]}）") } }
            }
        }
    }
    cancelIds?.let { ids ->
        AlertDialog(onDismissRequest = { cancelIds = null }, title = { Text(stringResource(com.pickaudio.R.string.ui_downloadscreen_007)) }, text = { Text("取消这 ${ids.size} 个任务并移除未完成文件。") },
            confirmButton = { TextButton(onClick = { control(ids, DownloadCommand.CANCEL); cancelIds = null }, enabled = !commandBusy) { Text(stringResource(com.pickaudio.R.string.ui_downloadscreen_002)) } },
            dismissButton = { TextButton(onClick = { cancelIds = null }) { Text(stringResource(com.pickaudio.R.string.ui_downloadscreen_008)) } })
    }
    qualityTrack?.let { DownloadQualityDialog(listOf(it), downloadCoordinator, app.sourceManager, app.userPreferences, { qualityTrack = null }, onOpenSource) }
    versionTask?.let { task ->
        VersionChoiceDialog(track(task), candidates[task.id].orEmpty(), { item -> scope.launch {
            try { downloadCoordinator.confirmVersion(task.id, item); versionTask = null }
            catch (e: Exception) { message(e.message ?: "无法加入下载") }
        } }, { versionTask = null; downloadCoordinator.dismissCandidates(task.id) })
    }
}

fun formatDownloadBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
