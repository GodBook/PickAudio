package com.pickaudio.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickaudio.data.db.SourceScriptEntity
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManagerScreen(
    sourceManager: LxSourceManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sources by sourceManager.getAllSources().collectAsState(initial = emptyList())
    val selections by sourceManager.getPlatformSelections().collectAsState(initial = emptyList())

    val wySelectedSourceId = selections.find { it.platform == "wy" }?.sourceId
    val txSelectedSourceId = selections.find { it.platform == "tx" }?.sourceId

    var showUrlDialog by remember { mutableStateOf(false) }
    var sourceToDelete by remember { mutableStateOf<SourceScriptEntity?>(null) }

    // SAF file picker for local JS script
    val scriptPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val content = context.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw IllegalStateException("读取文件失败")
                    val entity = sourceManager.importSourceFromCode(content)
                    Toast.makeText(context, "成功导入音乐源: ${entity.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音乐源管理", fontWeight = FontWeight.Bold) },
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
            // Import Actions Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "导入自定义源",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "仅支持符合 LX 移动版规范的单脚本文件。脚本大小限 5MB 内。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scriptPicker.launch(arrayOf("text/javascript", "application/javascript", "*/*")) }) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("导入本地 JS")
                            }
                            OutlinedButton(onClick = { showUrlDialog = true }) {
                                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("网络链接")
                            }
                        }
                    }
                }
            }

            // Platform Mapping Card
            if (sources.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "平台活动音源绑定",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // NetEase Source Selector
                            PlatformSelectorRow(
                                platformName = "网易云音乐",
                                sources = sources,
                                selectedSourceId = wySelectedSourceId,
                                onSelect = { id ->
                                    scope.launch { sourceManager.selectSourceForPlatform("wy", id) }
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // QQ Music Source Selector
                            PlatformSelectorRow(
                                platformName = "QQ 音乐",
                                sources = sources,
                                selectedSourceId = txSelectedSourceId,
                                onSelect = { id ->
                                    scope.launch { sourceManager.selectSourceForPlatform("tx", id) }
                                }
                            )
                        }
                    }
                }
            }

            // Sources List Header
            item {
                Text(
                    text = "已导入脚本 (${sources.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            if (sources.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "尚未导入任何自定义源脚本",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(sources, key = { it.id }) { s ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = s.name,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "版本: ${s.version} ${if (s.author.isNotEmpty()) "· 作者: ${s.author}" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(onClick = { sourceToDelete = s }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                                }
                            }

                            if (s.description.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = s.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Import URL Dialog
    if (showUrlDialog) {
        var urlInput by remember { mutableStateOf("") }
        var isImporting by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isImporting) showUrlDialog = false },
            title = { Text("导入单脚本链接") },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = { Text("https://example.com/source.js") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isImporting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在下载并校验脚本...", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isImporting && urlInput.isNotBlank(),
                    onClick = {
                        isImporting = true
                        scope.launch {
                            try {
                                val entity = sourceManager.importSourceFromUrl(urlInput.trim())
                                Toast.makeText(context, "成功导入音乐源: ${entity.name}", Toast.LENGTH_SHORT).show()
                                showUrlDialog = false
                            } catch (e: Exception) {
                                Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                isImporting = false
                            }
                        }
                    }
                ) {
                    Text("导入")
                }
            },
            dismissButton = {
                TextButton(enabled = !isImporting, onClick = { showUrlDialog = false }) { Text("取消") }
            }
        )
    }

    // Delete Dialog
    if (sourceToDelete != null) {
        AlertDialog(
            onDismissRequest = { sourceToDelete = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除音源「${sourceToDelete!!.name}」吗？") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        scope.launch { sourceManager.deleteSource(sourceToDelete!!.id) }
                        sourceToDelete = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { sourceToDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
fun PlatformSelectorRow(
    platformName: String,
    sources: List<SourceScriptEntity>,
    selectedSourceId: String?,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentSource = sources.find { it.id == selectedSourceId }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = platformName, fontWeight = FontWeight.Medium)

        Box {
            FilledTonalButton(onClick = { expanded = true }) {
                Text(currentSource?.name ?: "未选择")
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("不使用 (未配置)") },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
                sources.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.name) },
                        onClick = {
                            onSelect(s.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
