package com.pickaudio.ui.screens

import androidx.compose.ui.res.stringResource

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pickaudio.data.db.PlaylistEntity
import com.pickaudio.data.repository.PlaylistRepository
import com.pickaudio.ui.components.ReorderHandle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    playlistRepository: PlaylistRepository, onPlaylistClick: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val playlists by playlistRepository.getAllPlaylists().collectAsStateWithLifecycle(initialValue = emptyList())
    val summaries by playlistRepository.getPlaylistSummaries().collectAsStateWithLifecycle(initialValue = emptyMap())
    val state = rememberLazyListState()
    val snack = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var ordered by remember { mutableStateOf<List<PlaylistEntity>>(emptyList()) }
    var dragging by remember { mutableStateOf(false) }
    var savingOrder by remember { mutableStateOf(false) }
    var baseOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var edit by remember { mutableStateOf<PlaylistEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PlaylistEntity?>(null) }
    LaunchedEffect(playlists, dragging) { if (!dragging) ordered = playlists.filter { !it.isSystem } }
    val system = playlists.filter { it.isSystem }
    val shown = (system + ordered).filter { query.isBlank() || it.name.contains(query, true) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(com.pickaudio.R.string.ui_mainactivity_002)) }, actions = {
            TextButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null); Text(stringResource(com.pickaudio.R.string.ui_playlistsscreen_001)) }
        })
    }, snackbarHost = { SnackbarHost(snack) }, modifier = modifier.fillMaxSize()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(com.pickaudio.R.string.ui_playlistsscreen_002)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
            LazyColumn(Modifier.weight(1f), state = state) {
                itemsIndexed(shown, key = { _, playlist -> playlist.id }) { _, playlist ->
                    var menu by remember(playlist.id) { mutableStateOf(false) }
                    val summary = summaries[playlist.id]
                    ListItem(headlineContent = { Text(playlist.name) },
                        supportingContent = { Text("${summary?.first ?: 0} 首 · ${if (playlist.isSystem) "收藏歌单" else "自建歌单"}") },
                        leadingContent = {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                val cover = summary?.second
                                if (cover != null) AsyncImage(cover, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Icon(if (playlist.isSystem) Icons.Default.Favorite else Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        trailingContent = {
                            if (!playlist.isSystem) Row(verticalAlignment = Alignment.CenterVertically) {
                                if (query.isBlank() && !savingOrder) ReorderHandle(playlist.id, ordered.indexOf(playlist), ordered.size, state, { from, to ->
                                    if (!dragging) baseOrder = playlists.filter { !it.isSystem }.map { it.id }
                                    dragging = true
                                    ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
                                }, {
                                    val ids = ordered.map { it.id }
                                    savingOrder = true
                                    scope.launch {
                                        try { playlistRepository.reorderPlaylists(ids, baseOrder) }
                                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                        catch (e: Exception) { snack.showSnackbar("排序未保存：${e.message}") }
                                        finally { dragging = false; savingOrder = false }
                                    }
                                })
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(com.pickaudio.R.string.ui_playlistsscreen_007)) }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(com.pickaudio.R.string.ui_playlistsscreen_003)) }, onClick = { edit = playlist; menu = false })
                                        DropdownMenuItem(text = { Text(stringResource(com.pickaudio.R.string.ui_playlistsscreen_004)) }, onClick = { deleting = playlist; menu = false })
                                    }
                                }
                            }
                        }, modifier = Modifier.clickable { onPlaylistClick(playlist.id, playlist.name) })
                }
                if (shown.isEmpty()) item { Text(stringResource(com.pickaudio.R.string.ui_playlistsscreen_005), modifier = Modifier.padding(24.dp)) }
            }
        }
    }
    if (creating || edit != null) {
        var name by remember(edit, creating) { mutableStateOf(edit?.name.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { creating = false; edit = null }, title = { Text(if (creating) "新建歌单" else "重命名歌单") },
            text = { Column { OutlinedTextField(name, { name = it }, label = { Text(stringResource(com.pickaudio.R.string.ui_playlistsscreen_006)) }, singleLine = true); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { Button(enabled = name.isNotBlank() && !busy, onClick = {
                val id = edit?.id
                busy = true
                scope.launch {
                    try {
                        if (id == null) playlistRepository.createPlaylist(name) else playlistRepository.renamePlaylist(id, name)
                        creating = false; edit = null
                    } catch (e: Exception) { error = e.message; busy = false }
                }
            }) { Text(if (creating) "创建" else "保存") } },
            dismissButton = { TextButton(onClick = { creating = false; edit = null }, enabled = !busy) { Text(stringResource(com.pickaudio.R.string.action_cancel)) } })
    }
    deleting?.let { playlist ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(com.pickaudio.R.string.ui_playlistsscreen_004)) }, text = { Text("删除「${playlist.name}」的歌单结构，音频文件和收藏保留。") },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch { playlistRepository.deletePlaylist(playlist.id); snack.showSnackbar("歌单已删除") } }) { Text(stringResource(com.pickaudio.R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(com.pickaudio.R.string.action_cancel)) } })
    }
}
