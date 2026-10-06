package com.pickaudio.ui.screens

import androidx.compose.ui.res.stringResource

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pickaudio.data.model.*
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.data.repository.*
import com.pickaudio.ui.components.UpdateDialog
import com.pickaudio.ui.components.rememberRelinkFileAction
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
    val theme by userPreferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val filterShort by userPreferences.filterShortAudio.collectAsStateWithLifecycle(initialValue = true)
    val onlineQuality by userPreferences.defaultOnlineQuality.collectAsStateWithLifecycle(initialValue = Quality.Q128K)
    val downloadQuality by userPreferences.defaultDownloadQuality.collectAsStateWithLifecycle(initialValue = Quality.Q320K)
    val wifiOnly by userPreferences.wifiOnlyDownload.collectAsStateWithLifecycle(initialValue = true)
    val lyricSize by userPreferences.lyricFontSize.collectAsStateWithLifecycle(initialValue = 18)
    val translation by userPreferences.showLyricTranslation.collectAsStateWithLifecycle(initialValue = true)
    val cacheMegabytes by userPreferences.audioCacheMegabytes.collectAsStateWithLifecycle(initialValue = 256)
    val metrics by com.pickaudio.playback.PlaybackDiagnostics.metrics.collectAsStateWithLifecycle()
    val updateStatus by appUpdateManager.status.collectAsStateWithLifecycle()
    val caches = remember { CacheRepository(app, app.database, app.downloadCoordinator) }
    var usage by remember { mutableStateOf(CacheUsage()) }
    var cacheBusy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<BackupManifest?>(null) }
    var restoreSettings by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf<RestoreReport?>(null) }
    val pendingRestores by backupManager.pendingSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val recoveryStatus by backupManager.recoveryStatus.collectAsStateWithLifecycle()
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
    val diagnosticsPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try { com.pickaudio.playback.DiagnosticReport.export(app, app.database, userPreferences, uri); status = "诊断文件已导出" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = "导出诊断失败：${e.message}" }
        }
    }
    val relinkFile = rememberRelinkFileAction(app.libraryRepository, { id ->
        report = report?.let { result -> result.copy(missingLocalTracks = result.missingLocalTracks.filter { it.id != id }) }
        status = "音频文件已关联，原有歌单和收藏已保留"
    }, { status = it })
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(com.pickaudio.R.string.settings_title)) }, navigationIcon = { IconButton(onClick = onBack, enabled = !busy && !cacheBusy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(com.pickaudio.R.string.action_back)) } })
    }, modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_001), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThemeMode.entries.forEach { mode ->
                    FilterChip(theme == mode, { scope.launch { userPreferences.setThemeMode(mode) } }, label = { Text(mode.label) })
                } }
            }
            item {
                HorizontalDivider(); Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_002), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                SettingSwitch("过滤 30 秒以下短音频", "仅影响扫描手机媒体库；手动导入的音频会保留。", filterShort) { scope.launch { userPreferences.setFilterShortAudio(it) } }
                QualitySetting("默认在线播放音质", onlineQuality) { scope.launch { userPreferences.setDefaultOnlineQuality(it) } }
                QualitySetting("默认下载音质", downloadQuality) { scope.launch { userPreferences.setDefaultDownloadQuality(it) } }
                Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_003), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingSwitch("仅 Wi-Fi 下载", "关闭后允许使用移动网络，新任务会遵循此设置。", wifiOnly) { scope.launch { userPreferences.setWifiOnlyDownload(it) } }
                ListItem(headlineContent = { Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_004)) }, supportingContent = { Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_005)) }, modifier = Modifier.clickable(onClick = onNavigateToSourceManager))
            }
            item {
                HorizontalDivider(); Text(stringResource(com.pickaudio.R.string.lyrics_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text("字号 · $lyricSize")
                Slider(lyricSize.toFloat(), { scope.launch { userPreferences.setLyricFontSize(it.toInt()) } }, valueRange = 14f..28f, steps = 13)
                SettingSwitch("显示歌词翻译", "有翻译时在原文下方显示。", translation) { scope.launch { userPreferences.setShowLyricTranslation(it) } }
            }
            item {
                HorizontalDivider(); Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_006), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_007), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_008))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(64, 256, 1024).forEach { size ->
                        FilterChip(cacheMegabytes == size, { scope.launch { userPreferences.setAudioCacheMegabytes(size) } },
                            label = { Text(if (size == 1024) "1 GiB" else "$size MiB") })
                    }
                }
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
                TextButton(onClick = ::refresh, enabled = !cacheBusy) { Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_009)) }
                TextButton(onClick = { showLicenses = true }) { Text(stringResource(com.pickaudio.R.string.third_party_title)) }
                TextButton(onClick = { diagnosticsPicker.launch("PickAudio-diagnostics.json") }) { Text(stringResource(com.pickaudio.R.string.diagnostics_export)) }
                Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_010), style = MaterialTheme.typography.titleSmall)
                Text("播放 ${metrics.plays} 次 · 最近首播 ${metrics.firstAudioMs?.let { "$it ms" } ?: "未测量"}\n缓冲 ${metrics.buffers} 次 · 解析失败 ${metrics.resolveFailures} 次 · 音频缓存字节占比 ${metrics.cachePercent}%")
                Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_011), style = MaterialTheme.typography.bodySmall)
            }
            item {
                HorizontalDivider(); Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_012), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_013), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exportPicker.launch("PickAudio_Backup_${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())}.zip") }, enabled = !busy) { Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_014)) }
                    OutlinedButton(onClick = { importPicker.launch(arrayOf("application/zip", "*/*")) }, enabled = !busy) { Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_015)) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, modifier = Modifier.padding(top = 12.dp)) }
                recoveryStatus?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                if (pendingRestores.isNotEmpty()) {
                    Text("有 ${pendingRestores.size} 次备份恢复尚未完成", modifier = Modifier.padding(top = 8.dp))
                    pendingRestores.forEach { session ->
                        session.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                try { report = backupManager.resumeSession(session.id); status = "备份恢复已完成" }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { status = "恢复待继续：${e.message}" }
                                finally { busy = false }
                            }
                        }) { Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_016)) }
                    }
                }
            }
            item {
                HorizontalDivider(); Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_017), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text("拾音 v${appUpdateManager.currentVersionName}")
                TextButton(onClick = { scope.launch { appUpdateManager.checkForUpdates() } }) { Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_018)) }
                Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_019), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    preview?.let { manifest ->
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_020)) }, text = {
            Column {
                Text("备份时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(manifest.exportedAt))}")
                Text("${manifest.playlists.size} 个歌单 · ${manifest.favorites.size} 个收藏\n${manifest.tracks.size} 首歌曲记录 · ${manifest.lyrics.size} 份歌词")
                Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_021), modifier = Modifier.padding(top = 12.dp))
                if (manifest.settings != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(restoreSettings, { restoreSettings = it }, enabled = !busy); Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_022)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                try { report = backupManager.restore(manifest, restoreSettings); preview = null; status = "备份恢复完成" }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { status = "恢复未完成，请从待恢复任务继续：${e.message}"; preview = null }
                finally { busy = false }
            }
        }) { Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_023)) } }, dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text(stringResource(com.pickaudio.R.string.action_cancel)) } })
    }
    report?.let { result ->
        AlertDialog(onDismissRequest = { report = null }, title = { Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_024)) }, text = {
            Column {
                Text("新增 ${result.addedTracks} 首，合并 ${result.mergedTracks} 首\n新增 ${result.newPlaylists} 个歌单，合并 ${result.mergedPlaylists} 个歌单\n收藏 ${result.favorites} 条 · 歌词 ${result.lyrics} 份\n偏好设置：${if (result.settingsRestored) "已恢复" else "保留本机设置"}")
                if (result.missingSources.isNotEmpty()) Text("需要重新导入音源：${result.missingSources.joinToString()}", modifier = Modifier.padding(top = 12.dp))
                if (result.missingLocalTracks.isNotEmpty()) {
                    Text("待关联音频 · ${result.missingLocalTracks.size} 首", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(result.missingLocalTracks, key = { it.id }) { song ->
                            ListItem(headlineContent = { Text(song.title) }, supportingContent = { Text(song.artist) },
                                trailingContent = { TextButton(onClick = { relinkFile(Track(song.id, song.title, song.artist, song.album, song.durationMs), null) }) { Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_025)) } })
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { report = null }) { Text(stringResource(com.pickaudio.R.string.action_done)) } })
    }
    if (showLicenses) com.pickaudio.ui.components.ThirdPartyDialog { showLicenses = false }
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
        TextButton(onClick = onClear, enabled = !busy && available) { Text(stringResource(com.pickaudio.R.string.ui_settingsscreen_026)) }
    }
}
