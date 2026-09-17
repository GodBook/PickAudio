package com.pickaudio.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pickaudio.backup.BackupManager
import com.pickaudio.data.model.Quality
import com.pickaudio.data.model.ThemeMode
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.ui.components.UpdateDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    userPreferences: UserPreferences,
    backupManager: BackupManager,
    appUpdateManager: com.pickaudio.update.AppUpdateManager = (LocalContext.current.applicationContext as com.pickaudio.PickAudioApplication).appUpdateManager,
    onNavigateToSourceManager: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val themeMode by userPreferences.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val filterShortAudio by userPreferences.filterShortAudio.collectAsState(initial = true)
    val defaultOnlineQuality by userPreferences.defaultOnlineQuality.collectAsState(initial = Quality.Q128K)
    val defaultDownloadQuality by userPreferences.defaultDownloadQuality.collectAsState(initial = Quality.Q320K)
    val updateStatus by appUpdateManager.status.collectAsState()

    var cacheSizeStr by remember { mutableStateOf("计算中...") }

    fun refreshCacheSize() {
        scope.launch {
            val size = withContext(Dispatchers.IO) {
                calculateDirSize(context.cacheDir)
            }
            val mb = size / (1024.0 * 1024.0)
            cacheSizeStr = "%.1f MB".format(mb)
        }
    }

    LaunchedEffect(Unit) {
        refreshCacheSize()
    }

    // Export Backup Picker
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = backupManager.exportBackup(uri)
                if (ok) {
                    Toast.makeText(context, "备份导出成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "备份导出失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Import Backup Picker
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = backupManager.importBackup(uri)
                if (ok) {
                    Toast.makeText(context, "备份恢复合并完成", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "备份文件无效或损坏", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Theme Setting
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("外观主题", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = themeMode == mode,
                                    onClick = { scope.launch { userPreferences.setThemeMode(mode) } },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                    }
                }
            }

            // Scan Rules
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("曲库扫描规则", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("过滤 30 秒以下短音频", fontWeight = FontWeight.Medium)
                                Text("自动扫描系统媒体库时忽略铃声等短音频", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = filterShortAudio,
                                onCheckedChange = { scope.launch { userPreferences.setFilterShortAudio(it) } }
                            )
                        }
                    }
                }
            }

            // Default Qualities
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("音质偏好", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Online default
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("默认在线播放音质", fontWeight = FontWeight.Medium)
                            Text(defaultOnlineQuality.label, color = MaterialTheme.colorScheme.primary)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Download default
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("默认下载音质", fontWeight = FontWeight.Medium)
                            Text(defaultDownloadQuality.label, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Music Source Shortcut
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSourceManager() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("音乐源管理", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("管理并配置 LX 自定义源脚本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }

            // Cache Management
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("缓存管理", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("临时缓存空间占用: $cacheSizeStr", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        context.cacheDir.deleteRecursively()
                                    }
                                    refreshCacheSize()
                                    Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("清理缓存")
                            }
                        }
                    }
                }
            }

            // Backup & Restore
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("数据备份与恢复", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "备份歌单、收藏及设置到 ZIP 压缩包；恢复时自动安全合并，不覆盖现有歌曲文件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                exportLauncher.launch("PickAudio_Backup_${System.currentTimeMillis()}.zip")
                            }) {
                                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("导出备份")
                            }
                            OutlinedButton(onClick = {
                                importLauncher.launch(arrayOf("application/zip", "*/*"))
                            }) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("恢复备份")
                            }
                        }
                    }
                }
            }

            // Version & Update
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("版本与更新", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("当前版本: v${appUpdateManager.currentVersionName}", fontWeight = FontWeight.Medium)
                                Text(
                                    "托管于 GitHub (GodBook/PickAudio)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        appUpdateManager.checkForUpdates()
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("检查更新")
                            }
                        }
                    }
                }
            }

            // About
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "拾音 PickAudio v${appUpdateManager.currentVersionName}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "仅供个人及少量好友使用 · 无账号设计 · 开源播放器",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    UpdateDialog(
        status = updateStatus,
        currentVersion = appUpdateManager.currentVersionName,
        onDismiss = { appUpdateManager.dismissUpdate() },
        onStartDownload = { info ->
            scope.launch {
                appUpdateManager.startDownload(info)
            }
        },
        onCancelDownload = { appUpdateManager.cancelDownload() },
        onInstall = { file -> appUpdateManager.installApk(file) },
        onOpenBrowser = { url -> appUpdateManager.openBrowserReleasePage(url) },
        onRetry = {
            scope.launch {
                appUpdateManager.checkForUpdates()
            }
        }
    )
}

private fun calculateDirSize(dir: File): Long {
    var size = 0L
    val files = dir.listFiles() ?: return 0L
    for (f in files) {
        size += if (f.isDirectory) calculateDirSize(f) else f.length()
    }
    return size
}
