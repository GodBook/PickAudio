package com.pickaudio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pickaudio.data.model.*
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.data.repository.PlaylistRepository
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.online.VersionMatcher
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.launch

@Composable
fun PlaylistPickerDialog(tracks: List<Track>, repository: PlaylistRepository, onDismiss: () -> Unit, onAdded: (String) -> Unit = {}) {
    val playlists by repository.getAllPlaylists().collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun add(id: String, label: String) {
        if (busy) return
        busy = true
        scope.launch {
            try { repository.addTracks(id, tracks); onAdded(label); onDismiss() }
            catch (e: Exception) { error = e.message; busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("加入歌单 · ${tracks.size} 首") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("新建歌单名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    try { repository.createAndAddTracks(name, tracks); onAdded(name.trim()); onDismiss() }
                    catch (e: Exception) { error = e.message; busy = false }
                }
            }) { Text("新建并加入") }
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(headlineContent = { Text(playlist.name) }, modifier = Modifier.clickable(enabled = !busy) { add(playlist.id, playlist.name) })
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable
fun DownloadQualityDialog(
    tracks: List<Track>, coordinator: DownloadCoordinator, sourceManager: LxSourceManager,
    preferences: UserPreferences, onDismiss: () -> Unit, onOpenSource: () -> Unit = {}, onQueued: () -> Unit = {}
) {
    val defaultQuality by preferences.defaultDownloadQuality.collectAsState(initial = Quality.Q320K)
    val online = remember(tracks) { tracks.filter { it.platform != null && it.platformSongId != null }.distinctBy { it.id } }
    val sources by sourceManager.getAllSources().collectAsState(initial = emptyList())
    val selections by sourceManager.getPlatformSelections().collectAsState(initial = emptyList())
    var qualities by remember { mutableStateOf<List<String>?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var rememberChoice by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(online, sources, selections, defaultQuality) {
        val supported = online.map { sourceManager.supportedQualities(it.platform!!) }.reduceOrNull { a, b -> a.intersect(b.toSet()).toList() }.orEmpty()
        qualities = supported
        selected = defaultQuality.value.takeIf { it in supported } ?: supported.firstOrNull()
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("下载音乐 · ${online.size} 首") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("文件保存到 Music/PickAudio，音质以实际校验结果为准。", style = MaterialTheme.typography.bodySmall)
            if (qualities == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (qualities?.isEmpty() == true) {
                Text("当前音源没有可用于这些歌曲的共同音质，请按平台分别下载或配置音乐源。")
                TextButton(onClick = { onDismiss(); onOpenSource() }) { Text("配置音乐源") }
            }
            qualities.orEmpty().forEach { quality ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = quality }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == quality, onClick = { selected = quality }, enabled = !busy)
                    Text(Quality.fromValue(quality).label)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rememberChoice, { rememberChoice = it }, enabled = !busy)
                Text("记住此音质", style = MaterialTheme.typography.bodyMedium)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { Button(enabled = !busy && selected != null && online.isNotEmpty(), onClick = {
        busy = true
        scope.launch {
            try {
                online.forEach { track -> coordinator.enqueueDownload(track.id, track.title, track.artist, track.album, track.coverUri,
                    track.platform!!, track.platformSongId!!, selected, track.durationMs) }
                if (rememberChoice) preferences.setDefaultDownloadQuality(Quality.fromValue(selected!!))
                onQueued(); onDismiss()
            } catch (e: Exception) { error = e.message; busy = false }
        }
    }) { Text("加入下载") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable
fun VersionChoiceDialog(original: Track?, candidates: List<SearchSongItem>, onConfirm: (SearchSongItem) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("确认其他平台的歌曲版本") }, text = {
        Column {
            Text("原版本暂时无法获取。请选择要播放或下载的版本，歌曲信息和歌词将一起切换。")
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(candidates, key = { "${it.platform}_${it.songId}" }) { item ->
                    val same = original != null && VersionMatcher.sameRecording(original.title, original.artist, original.durationMs, item)
                    ListItem(headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${item.artist} · ${item.album}\n${formatMusicTime(item.durationMs)} · ${Platform.fromId(item.platform).displayName}${if (same) " · 信息匹配" else " · 请核对版本"}") },
                        modifier = Modifier.clickable { onConfirm(item) })
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("保留原版本") } })
}

fun formatMusicTime(ms: Long): String = "%d:%02d".format((ms / 60000).coerceAtLeast(0), (ms / 1000 % 60).coerceAtLeast(0))
