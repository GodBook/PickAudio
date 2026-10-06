package com.pickaudio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pickaudio.PickAudioApplication
import com.pickaudio.backup.*
import com.pickaudio.data.model.*
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.data.repository.*
import com.pickaudio.ui.components.UpdateDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    userPreferences: UserPreferences, backupManager: BackupManager,
    appUpdateManager: com.pickaudio.update.AppUpdateManager = (LocalContext.current.applicationContext as PickAudioApplication).appUpdateManager,
    onNavigateToSourceManager: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier
) {
    val app = LocalContext.current.applicationContext as PickAudioApplication
    val scope = rememberCoroutineScope()
    val theme by userPreferences.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val filterShort by userPreferences.filterShortAudio.collectAsState(initial = true)
    val onlineQuality by userPreferences.defaultOnlineQuality.collectAsState(initial = Quality.Q128K)
    val downloadQuality by userPreferences.defaultDownloadQuality.collectAsState(initial = Quality.Q320K)
    val wifiOnly by userPreferences.wifiOnlyDownload.collectAsState(initial = true)
    val lyricSize by userPreferences.lyricFontSize.collectAsState(initial = 18)
    val translation by userPreferences.showLyricTranslation.collectAsState(initial = true)
    val updateStatus by appUpdateManager.status.collectAsState()
    val caches = remember { CacheRepository(app, app.database, app.downloadCoordinator) }
    var usage by remember { mutableStateOf(CacheUsage()) }
    var cacheBusy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<BackupManifest?>(null) }
    var restoreSettings by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf<RestoreReport?>(null) }
    var relinkId by remember { mutableStateOf<String?>(null) }
    fun refresh() { scope.launch { usage = caches.usage() } }
    LaunchedEffect(Unit) { usage = caches.usage() }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true; status = "正在导出备份"
            try {
                val result = backupManager.export(uri)
                status = "已导出 ${result.playlists} 个歌单、${result.favorites} 个收藏、${result.tracks} 首歌曲记录和 ${result.lyrics} 份歌词，以及偏好设置。音频文件需另行保存。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = "导出失败：${e.message}" }
            finally { busy = false }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; status = "正在检查备份"
            try { preview = backupManager.inspect(uri); restoreSettings = preview?.settings != null; status = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = "备份无法恢复：${e.message}" }
            finally { busy = false }
        }
    }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = relinkId
        if (uri != null && id != null) scope.launch {
            try {
                app.libraryRepository.relinkTrack(id, uri)
                report = report?.copy(missingLocalTracks = report!!.missingLocalTracks.filter { it.id != id })
                status = "音频文件已关联，原有歌单和收藏已保留"
            } catch (e: Exception) { status = "关联失败：${e.message}" }
            relinkId = null
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("设置") }, navigationIcon = { IconButton(onClick = onBack, enabled = !busy && !cacheBusy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } })
    }, modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text("外观", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThemeMode.entries.forEach { mode ->
                    FilterChip(theme == mode, { scope.launch { userPreferences.setThemeMode(mode) } }, label = { Text(mode.label) })
                } }
            }
            item {
                HorizontalDivider(); Text("音乐与下载", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                SettingSwitch("过滤 30 秒以下短音频", "仅影响扫描手机媒体库；手动导入的音频会保留。", filterShort) { scope.launch { userPreferences.setFilterShortAudio(it) } }
                QualitySetting("默认在线播放音质", onlineQuality) { scope.launch { userPreferences.setDefaultOnlineQuality(it) } }
                QualitySetting("默认下载音质", downloadQuality) { scope.launch { userPreferences.setDefaultDownloadQuality(it) } }
                Text("可用音质由当前音乐源决定；不支持的档位会明确提示。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingSwitch("仅 Wi-Fi 下载", "关闭后允许使用移动网络，新任务会遵循此设置。", wifiOnly) { scope.launch { userPreferences.setWifiOnlyDownload(it) } }
                ListItem(headlineContent = { Text("音乐源管理") }, supportingContent = { Text("平台绑定、支持音质与兼容性测试") }, modifier = Modifier.clickable(onClick = onNavigateToSourceManager))
            }
            item {
                HorizontalDivider(); Text("歌词", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text("字号 · $lyricSize")
                Slider(lyricSize.toFloat(), { scope.launch { userPreferences.setLyricFontSize(it.toInt()) } }, valueRange = 14f..28f, steps = 13)
                SettingSwitch("显示歌词翻译", "有翻译时在原文下方显示。", translation) { scope.launch { userPreferences.setShowLyricTranslation(it) } }
            }
            item {
                HorizontalDivider(); Text("存储与缓存", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text("清理缓存保留下载歌曲、歌单、收藏和手动歌词。暂停的下载进度也会保留。", style = MaterialTheme.typography.bodySmall)
                CacheRow("播放音频缓存", formatDownloadBytes(usage.audio), cacheBusy, usage.audio > 0) {
                    scope.launch {
                        cacheBusy = true
                        try { app.playbackCoordinator.clearPlaybackCache(); usage = caches.usage(); status = "音频缓存已清理" }
                        catch (e: Exception) { status = "清理失败：${e.message}" }
                        finally { cacheBusy = false }
                    }
                }
                CacheRow("封面缓存", formatDownloadBytes(usage.covers), cacheBusy, usage.covers > 0) {
                    scope.launch { cacheBusy = true; try { caches.clearCovers(); usage = caches.usage(); status = "封面缓存已清理" } finally { cacheBusy = false } }
                }
                CacheRow("下载临时文件", "${formatDownloadBytes(usage.partials)} · 可清理 ${formatDownloadBytes(usage.unusedPartials)}", cacheBusy, usage.unusedPartials > 0) {
                    scope.launch { cacheBusy = true; try { caches.clearUnusedParts(); usage = caches.usage(); status = "无任务的临时文件已清理" } finally { cacheBusy = false } }
                }
                TextButton(onClick = ::refresh, enabled = !cacheBusy) { Text("刷新空间统计") }
            }
            item {
                HorizontalDivider(); Text("备份与恢复", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text("包含歌单、收藏、歌曲记录、歌词与校准、偏好设置和音源描述。\n不包含音频文件与音源脚本；换手机后可重新关联音频并导入原脚本。", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exportPicker.launch("PickAudio_Backup_${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())}.zip") }, enabled = !busy) { Text("导出备份") }
                    OutlinedButton(onClick = { importPicker.launch(arrayOf("application/zip", "*/*")) }, enabled = !busy) { Text("恢复备份") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, modifier = Modifier.padding(top = 12.dp)) }
            }
            item {
                HorizontalDivider(); Text("版本与更新", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text("拾音 v${appUpdateManager.currentVersionName}")
                TextButton(onClick = { scope.launch { appUpdateManager.checkForUpdates() } }) { Text("检查更新") }
                Text("本地与在线音乐播放器 · 无账号", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    preview?.let { manifest ->
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("确认恢复备份") }, text = {
            Column {
                Text("备份时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(manifest.exportedAt))}")
                Text("${manifest.playlists.size} 个歌单 · ${manifest.favorites.size} 个收藏\n${manifest.tracks.size} 首歌曲记录 · ${manifest.lyrics.size} 份歌词")
                Text("同一歌单增量合并，重复恢复不会复制歌单。原有音频文件保留。", modifier = Modifier.padding(top = 12.dp))
                if (manifest.settings != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(restoreSettings, { restoreSettings = it }, enabled = !busy); Text("恢复偏好设置") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                try { report = backupManager.restore(manifest, restoreSettings); preview = null; status = "备份恢复完成" }
                catch (e: Exception) { status = "恢复失败，原有数据保留：${e.message}"; preview = null }
                finally { busy = false }
            }
        }) { Text("恢复并合并") } }, dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text("取消") } })
    }
    report?.let { result ->
        AlertDialog(onDismissRequest = { report = null }, title = { Text("恢复结果") }, text = {
            Column {
                Text("新增 ${result.addedTracks} 首，合并 ${result.mergedTracks} 首\n新增 ${result.newPlaylists} 个歌单，合并 ${result.mergedPlaylists} 个歌单\n收藏 ${result.favorites} 条 · 歌词 ${result.lyrics} 份\n偏好设置：${if (result.settingsRestored) "已恢复" else "保留本机设置"}")
                if (result.missingSources.isNotEmpty()) Text("需要重新导入音源：${result.missingSources.joinToString()}", modifier = Modifier.padding(top = 12.dp))
                if (result.missingLocalTracks.isNotEmpty()) {
                    Text("待关联音频 · ${result.missingLocalTracks.size} 首", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(result.missingLocalTracks, key = { it.id }) { song ->
                            ListItem(headlineContent = { Text(song.title) }, supportingContent = { Text(song.artist) },
                                trailingContent = { TextButton(onClick = { relinkId = song.id; relinkPicker.launch(arrayOf("audio/*")) }) { Text("关联文件") } })
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { report = null }) { Text("完成") } })
    }
    UpdateDialog(updateStatus, appUpdateManager.currentVersionName, { appUpdateManager.dismissUpdate() },
        { info -> scope.launch { appUpdateManager.startDownload(info) } }, appUpdateManager::cancelDownload, appUpdateManager::installApk,
        appUpdateManager::openBrowserReleasePage, { scope.launch { appUpdateManager.checkForUpdates() } })
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun QualitySetting(title: String, selected: Quality, onSelect: (Quality) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title)
        Box {
            TextButton(onClick = { expanded = true }) { Text(selected.label) }
            DropdownMenu(expanded, { expanded = false }) {
                Quality.entries.forEach { quality -> DropdownMenuItem(text = { Text(quality.label) }, onClick = { onSelect(quality); expanded = false }) }
            }
        }
    }
}

@Composable
private fun CacheRow(title: String, size: String, busy: Boolean, available: Boolean, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title); Text(size, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onClear, enabled = !busy && available) { Text("清理") }
    }
}
