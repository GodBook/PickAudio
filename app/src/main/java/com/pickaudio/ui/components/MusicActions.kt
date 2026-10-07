package com.pickaudio.ui.components

import androidx.compose.ui.res.stringResource

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
import com.pickaudio.download.DownloadBatchReport
import kotlinx.coroutines.CancellationException
import com.pickaudio.online.VersionMatcher
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PlaybackQualityDialog(track: Track, sourceManager: LxSourceManager, current: String, actual: String?,
    onDismiss: () -> Unit, onChoose: (String, Boolean) -> Unit) {
    val sources by remember(sourceManager) { sourceManager.getAllSources() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val selections by remember(sourceManager) { sourceManager.getPlatformSelections() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var qualities by remember(track.id) { mutableStateOf<List<String>?>(null) }
    var saveDefault by remember { mutableStateOf(false) }
    LaunchedEffect(track.platform, sources, selections) { qualities = track.platform?.let { sourceManager.supportedQualities(it) }.orEmpty() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(com.pickaudio.R.string.ui_playerscreen_003)) }, text = {
        Column {
            actual?.let { Text("当前文件 · $it") }
            Text(stringResource(com.pickaudio.R.string.ui_musicactions_001), style = MaterialTheme.typography.bodySmall)
            if (qualities == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (qualities?.isEmpty() == true) Text(if (track.platform == null) "本地文件的编码由文件本身决定。" else "当前音源没有可选音质，请更换音源。")
            qualities.orEmpty().forEach { quality ->
                Row(Modifier.fillMaxWidth().clickable { onChoose(quality, saveDefault) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(quality == current, onClick = null)
                    Text(Quality.fromValue(quality).label)
                }
            }
            if (!qualities.isNullOrEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(saveDefault, { saveDefault = it }); Text(stringResource(com.pickaudio.R.string.ui_musicactions_002))
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(com.pickaudio.R.string.ui_musicactions_003)) } })
}

@Composable
fun PlaylistPickerDialog(tracks: List<Track>, repository: PlaylistRepository, onDismiss: () -> Unit, onAdded: (String) -> Unit = {}) {
    val playlists by repository.getAllPlaylists().collectAsStateWithLifecycle(initialValue = emptyList())
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun add(id: String, label: String) {
        if (busy) return
        busy = true
        scope.launch {
            try { val result = repository.addTracks(id, tracks); onAdded("「$label」${result.summary}"); onDismiss() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message; busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("加入歌单 · ${tracks.size} 首") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(com.pickaudio.R.string.ui_musicactions_004)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    try { val result = repository.createAndAddWithReport(name, tracks); onAdded("「${name.trim()}」${result.second.summary}"); onDismiss() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message; busy = false }
                }
            }) { Text(stringResource(com.pickaudio.R.string.ui_musicactions_005)) }
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(headlineContent = { Text(playlist.name) }, modifier = Modifier.clickable(enabled = !busy) { add(playlist.id, playlist.name) })
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(com.pickaudio.R.string.action_cancel)) } })
}

@Composable
fun DownloadQualityDialog(
    tracks: List<Track>, coordinator: DownloadCoordinator, sourceManager: LxSourceManager,
    preferences: UserPreferences, onDismiss: () -> Unit, onOpenSource: () -> Unit = {}, onQueued: (DownloadBatchReport) -> Unit = {}
) {
    val defaultQuality by preferences.defaultDownloadQuality.collectAsStateWithLifecycle(initialValue = Quality.Q320K)
    val online = remember(tracks) { tracks.filter { it.platform != null && it.platformSongId != null }.distinctBy { it.id } }
    val sources by sourceManager.getAllSources().collectAsStateWithLifecycle(initialValue = emptyList())
    val selections by sourceManager.getPlatformSelections().collectAsStateWithLifecycle(initialValue = emptyList())
    var qualities by remember { mutableStateOf<List<String>?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var rememberChoice by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<DownloadBatchReport?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(online, sources, selections, defaultQuality) {
        val supported = online.map { sourceManager.supportedQualities(it.platform!!) }.reduceOrNull { a, b -> a.intersect(b.toSet()).toList() }.orEmpty()
        qualities = supported
        selected = defaultQuality.value.takeIf { it in supported } ?: supported.firstOrNull()
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("下载音乐 · ${online.size} 首") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(com.pickaudio.R.string.ui_musicactions_006), style = MaterialTheme.typography.bodySmall)
            if (qualities == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (qualities?.isEmpty() == true) {
                Text(stringResource(com.pickaudio.R.string.ui_musicactions_007))
                TextButton(onClick = { onDismiss(); onOpenSource() }) { Text(stringResource(com.pickaudio.R.string.ui_searchscreen_009)) }
            }
            qualities.orEmpty().forEach { quality ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = quality }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == quality, onClick = { selected = quality }, enabled = !busy)
                    Text(Quality.fromValue(quality).label)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rememberChoice, { rememberChoice = it }, enabled = !busy)
                Text(stringResource(com.pickaudio.R.string.ui_musicactions_008), style = MaterialTheme.typography.bodyMedium)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            report?.let { result ->
                Text(result.summary)
                result.failures.take(5).forEach { Text("${it.track.title}：${it.reason}", color = MaterialTheme.colorScheme.error) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { Button(enabled = !busy && selected != null && online.isNotEmpty(), onClick = {
        busy = true
        scope.launch {
            try {
                val result = coordinator.enqueueBatch(report?.failures?.map { it.track } ?: tracks, selected!!)
                val combined = report?.let { previous -> result.copy(added = previous.added + result.added,
                    resumed = previous.resumed + result.resumed, existing = previous.existing + result.existing,
                    skipped = previous.skipped + result.skipped) } ?: result
                report = combined
                if (rememberChoice) preferences.setDefaultDownloadQuality(Quality.fromValue(selected!!))
                onQueued(combined)
                if (result.failures.isEmpty()) onDismiss()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }) { Text(if (report?.failures?.isNotEmpty() == true) "重试失败项" else "加入下载") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(com.pickaudio.R.string.ui_musicactions_003)) } })
}

@Composable
fun VersionChoiceDialog(original: Track?, candidates: List<SearchSongItem>, onConfirm: (SearchSongItem) -> Unit, onDismiss: () -> Unit, message: String? = null) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(com.pickaudio.R.string.ui_musicactions_009)) }, text = {
        Column {
            Text(message ?: stringResource(com.pickaudio.R.string.ui_musicactions_010), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(candidates, key = { "${it.platform}_${it.songId}" }) { item ->
                    val same = original != null && VersionMatcher.sameRecording(original.title, original.artist, original.durationMs, item)
                    ListItem(headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${item.artist} · ${item.album}\n${formatMusicTime(item.durationMs)} · ${Platform.fromId(item.platform).displayName}${if (same) " · 信息匹配" else " · 请核对版本"}") },
                        modifier = Modifier.clickable { onConfirm(item) })
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(com.pickaudio.R.string.ui_musicactions_011)) } })
}

fun formatMusicTime(ms: Long): String = "%d:%02d".format((ms / 60000).coerceAtLeast(0), (ms / 1000 % 60).coerceAtLeast(0))
