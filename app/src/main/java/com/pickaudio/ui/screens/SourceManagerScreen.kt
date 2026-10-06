package com.pickaudio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pickaudio.data.db.SourceScriptEntity
import com.pickaudio.data.model.*
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SourceManagerScreen(sourceManager: LxSourceManager, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by sourceManager.getAllSources().collectAsState(initial = emptyList())
    val selections by sourceManager.getPlatformSelections().collectAsState(initial = emptyList())
    val health by sourceManager.sourceHealth.collectAsState()
    var urlDialog by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf<SourceScriptEntity?>(null) }
    var deleting by remember { mutableStateOf<SourceScriptEntity?>(null) }
    fun import(operation: suspend () -> SourceScriptEntity) {
        busy = true; error = null
        importJob = scope.launch {
            try { imported = operation(); urlDialog = false }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "导入失败，请检查脚本或链接" }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) import {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readNBytes(5 * 1024 * 1024 + 1) } } ?: error("无法读取脚本")
            require(bytes.size <= 5 * 1024 * 1024) { "脚本超过 5MB" }
            sourceManager.importSourceFromCode(String(bytes, Charsets.UTF_8))
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("音乐源管理") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } })
    }, modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("在线音乐来源", style = MaterialTheme.typography.titleMedium)
                Text("内置音源提供标准与高品质。使用自己的 LX 移动版脚本，可扩展平台或音质；每个平台只能选择支持它的音源。", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("application/javascript", "text/javascript", "*/*")) }, enabled = !busy) { Text("导入本地脚本") }
                    OutlinedButton(onClick = { urlDialog = true; error = null }, enabled = !busy) { Text("导入链接") }
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在读取并验证脚本…")
                    TextButton(onClick = { importJob?.cancel(); busy = false }) { Text("取消导入") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            listOf("wy", "tx").forEach { platform ->
                item(key = "platform_$platform") {
                    val compatible = sources.filter { it.isEnabled && sourceManager.capabilitiesForSource(it)[platform]?.actions?.contains("musicUrl") == true }
                    PlatformSelectorRow(Platform.fromId(platform).displayName, compatible,
                        selections.find { it.platform == platform }?.sourceId) { id ->
                        scope.launch { try { sourceManager.selectSourceForPlatform(platform, id) } catch (e: Exception) { error = e.message } }
                    }
                }
            }
            item { HorizontalDivider(); Text("已导入 · ${sources.size} 个音源", style = MaterialTheme.typography.titleMedium) }
            items(sources, key = { it.id }) { source ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium)
                    Text("v${source.version} · ${source.author.ifBlank { "作者未注明" }}", style = MaterialTheme.typography.bodySmall)
                    Text(source.description, style = MaterialTheme.typography.bodyMedium)
                    sourceManager.capabilitiesForSource(source).values.filter { it.platform in listOf("wy", "tx") }.forEach { capability ->
                        Text("${Platform.fromId(capability.platform).displayName} · ${capability.qualities.joinToString { Quality.fromValue(it).label }}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(health[source.id] ?: "尚未测试", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch { sourceManager.testSource(source) } }, enabled = health[source.id] != "正在测试") { Text("测试兼容性") }
                        if (source.id != "builtin_aggregate") TextButton(onClick = { deleting = source }) { Text("删除音源", color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (urlDialog) AlertDialog(onDismissRequest = { importJob?.cancel(); urlDialog = false }, title = { Text("导入音乐源链接") }, text = {
        Column { OutlinedTextField(url, { url = it }, label = { Text("HTTPS 脚本链接") }, singleLine = true); error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }, confirmButton = { Button(onClick = { import { sourceManager.importSourceFromUrl(url.trim()) } }, enabled = !busy && url.trim().startsWith("https://")) { Text("导入并验证") } },
        dismissButton = { TextButton(onClick = { importJob?.cancel(); urlDialog = false }) { Text("取消") } })
    imported?.let { source ->
        val supported = sourceManager.capabilitiesForSource(source).keys.filter { it in listOf("wy", "tx") }
        var chosen by remember(source.id) { mutableStateOf(supported.toSet()) }
        AlertDialog(onDismissRequest = { imported = null }, title = { Text("音源已导入") }, text = {
            Column {
                Text("${source.name} · v${source.version}")
                Text("选择使用此音源的平台：")
                supported.forEach { platform ->
                    Row { Checkbox(platform in chosen, { checked -> chosen = if (checked) chosen + platform else chosen - platform }); Text(Platform.fromId(platform).displayName, modifier = Modifier.padding(top = 12.dp)) }
                }
                if (supported.isEmpty()) Text("此脚本没有声明支持网易云或 QQ 音乐，暂时无法用于当前搜索结果。")
            }
        }, confirmButton = { Button(enabled = chosen.isNotEmpty(), onClick = {
            scope.launch { chosen.forEach { sourceManager.selectSourceForPlatform(it, source.id) }; imported = null }
        }) { Text("使用此音源") } }, dismissButton = { TextButton(onClick = { imported = null }) { Text("稍后配置") } })
    }
    deleting?.let { source ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除音乐源") }, text = { Text("删除「${source.name}」后，使用它的平台需要重新选择音源。") },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch { sourceManager.deleteSource(source.id) } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
fun PlatformSelectorRow(platformName: String, sources: List<SourceScriptEntity>, selectedSourceId: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(platformName, style = MaterialTheme.typography.titleSmall)
        Box(Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(sources.find { it.id == selectedSourceId }?.name ?: "未配置 · 点击选择") }
            DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem(text = { Text("暂不使用在线音源") }, onClick = { onSelect(null); expanded = false })
                sources.forEach { source -> DropdownMenuItem(text = { Text(source.name) }, onClick = { onSelect(source.id); expanded = false }) }
            }
        }
        if (sources.isEmpty()) Text("请导入支持此平台的音乐源。", style = MaterialTheme.typography.bodySmall)
    }
}
