package com.pickaudio.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pickaudio.PickAudioApplication
import com.pickaudio.data.db.DownloadTaskEntity
import com.pickaudio.data.model.*
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.ui.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadScreen(downloadCoordinator: DownloadCoordinator, onBack: () -> Unit, modifier: Modifier = Modifier, onOpenSource: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as PickAudioApplication
    val tasks by downloadCoordinator.getAllTasks().collectAsState(initial = emptyList())
    val wifiOnly by app.userPreferences.wifiOnlyDownload.collectAsState(initial = true)
    val candidates by downloadCoordinator.versionCandidates.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var versionTask by remember { mutableStateOf<DownloadTaskEntity?>(null) }
    var qualityTrack by remember { mutableStateOf<Track?>(null) }
    var cancelIds by remember { mutableStateOf<List<String>?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lists = listOf(
        tasks.filter { it.status in DownloadCoordinator.ACTIVE_STATES || it.status == "PENDING" },
        tasks.filter { it.status == "COMPLETED" },
        tasks.filter { it.status in listOf("PAUSED", "FAILED", "CANCELLED") }
    )
    val shown = lists[tab]
    val selected = shown.filter { it.id in selectedIds }
    fun message(value: String) { scope.launch { snack.showSnackbar(value) } }
    fun track(task: DownloadTaskEntity) = Track(task.trackId, task.title, task.artist, task.album, task.durationMs,
        task.coverUri, localUri = task.targetUri, platform = task.platform, platformSongId = task.platformSongId, sourceType = if (task.status == "COMPLETED") "DOWNLOADED" else "")
    Scaffold(topBar = {
        TopAppBar(title = { Text("下载管理") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = { TextButton(onClick = { selecting = !selecting; selectedIds = emptySet() }) { Text(if (selecting) "完成" else "多选") } })
    }, snackbarHost = { SnackbarHost(snack) }, modifier = modifier.fillMaxSize()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(if (wifiOnly) "仅在 Wi-Fi 下载 · 文件保存在 Music/PickAudio" else "允许移动网络下载 · 文件保存在 Music/PickAudio",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            TabRow(tab) {
                listOf("进行中", "已完成", "需处理").forEachIndexed { index, label ->
                    Tab(tab == index, onClick = { tab = index; selectedIds = emptySet() }, text = { Text("$label (${lists[index].size})") })
                }
            }
            if (selecting) FlowRow(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { selectedIds = shown.map { it.id }.toSet() }) { Text("全选 (${selectedIds.size})") }
                if (tab == 0) TextButton(onClick = { downloadCoordinator.pauseAll(selected.map { it.id }) }, enabled = selected.isNotEmpty()) { Text("暂停") }
                if (tab == 2) TextButton(onClick = { downloadCoordinator.resumeAll(selected.map { it.id }); message("已加入等待队列") }, enabled = selected.isNotEmpty()) { Text("重试/继续") }
                if (tab != 1) TextButton(onClick = { cancelIds = selected.map { it.id } }, enabled = selected.isNotEmpty()) { Text("取消任务") }
                else TextButton(onClick = { selected.forEach { app.playbackCoordinator.addToQueue(track(it)) }; message("已加入播放队列") }, enabled = selected.isNotEmpty()) { Text("加入队列") }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shown.isEmpty()) item { Text(when(tab) { 0 -> "没有待下载任务"; 1 -> "下载完成的音乐会显示在这里"; else -> "暂时没有需要处理的下载" }, modifier = Modifier.padding(16.dp)) }
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
                                        FilledTonalButton(onClick = { app.playbackCoordinator.addOrPlayTrack(track(task)) }) { Text("播放") }
                                        TextButton(onClick = {
                                            try {
                                                val uri = Uri.parse(task.targetUri ?: error("文件地址缺失"))
                                                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri) ?: "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                context.startActivity(Intent.createChooser(intent, "打开音乐文件"))
                                            } catch (e: Exception) { message("无法打开文件，请检查文件是否仍在 Music/PickAudio") }
                                        }) { Text("查看文件") }
                                    }
                                    "PAUSED", "FAILED" -> {
                                        FilledTonalButton(onClick = { downloadCoordinator.resumeTask(task.id) }) { Text(if (task.status == "FAILED") "重试" else "继续") }
                                        TextButton(onClick = { qualityTrack = track(task) }) { Text("选择其他音质") }
                                        TextButton(onClick = onOpenSource) { Text("更换音乐源") }
                                    }
                                    else -> TextButton(onClick = { downloadCoordinator.pauseTask(task.id) }) { Text("暂停") }
                                }
                                if (!candidates[task.id].isNullOrEmpty()) TextButton(onClick = { versionTask = task }) { Text("确认其他版本") }
                                if (task.status != "COMPLETED") TextButton(onClick = { cancelIds = listOf(task.id) }) { Text("取消任务") }
                            }
                        }
                    }
                }
            }
        }
    }
    cancelIds?.let { ids ->
        AlertDialog(onDismissRequest = { cancelIds = null }, title = { Text("取消下载") }, text = { Text("取消这 ${ids.size} 个任务并移除未完成文件。") },
            confirmButton = { TextButton(onClick = { downloadCoordinator.cancelAll(ids); cancelIds = null; selectedIds = emptySet() }) { Text("取消任务") } },
            dismissButton = { TextButton(onClick = { cancelIds = null }) { Text("保留任务") } })
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
