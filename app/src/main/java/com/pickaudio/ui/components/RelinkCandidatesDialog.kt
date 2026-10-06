package com.pickaudio.ui.components

import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pickaudio.data.model.Track
import com.pickaudio.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun RelinkCandidatesDialog(targets: List<Track>, repository: LibraryRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<Map<String, List<RelinkCandidate>>?>(null) }
    var done by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<Triple<Track, RelinkCandidate, String>?>(null) }
    LaunchedEffect(targets) {
        try { candidates = repository.findRelinkCandidates(targets.map { it.id }) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
    }
    fun apply(target: Track, candidate: RelinkCandidate, transfer: Boolean) {
        busy = true; error = null
        scope.launch {
            try { repository.relinkTrack(target.id, Uri.parse(candidate.uri), transfer); done = done + target.id }
            catch (e: RelinkConflictException) { conflict = Triple(target, candidate, e.otherTitle) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "关联失败" }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(com.pickaudio.R.string.ui_libraryscreen_010)) }, text = {
        Column {
            Text(stringResource(com.pickaudio.R.string.ui_relinkcandidatesdialog_001))
            if (candidates == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(targets, key = { it.id }) { target ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(target.title, style = MaterialTheme.typography.titleSmall)
                        if (target.id in done) Text(stringResource(com.pickaudio.R.string.ui_relinkcandidatesdialog_002)) else {
                            val choices = candidates?.get(target.id).orEmpty()
                            if (candidates != null && choices.isEmpty()) Text(stringResource(com.pickaudio.R.string.ui_relinkcandidatesdialog_003))
                            choices.forEach { candidate ->
                                Text("${candidate.title} · ${candidate.evidence}")
                                OutlinedButton(enabled = !busy, onClick = { apply(target, candidate, false) }) { Text(stringResource(com.pickaudio.R.string.ui_relinkcandidatesdialog_004)) }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(com.pickaudio.R.string.action_done)) } })
    conflict?.let { (target, candidate, title) ->
        AlertDialog(onDismissRequest = { conflict = null }, title = { Text(stringResource(com.pickaudio.R.string.ui_relinkcandidatesdialog_005)) },
            text = { Text("文件已属于《$title》。转移后两首歌曲记录及歌单均保留，原歌曲将需要重新关联文件。") },
            confirmButton = { TextButton(onClick = { conflict = null; apply(target, candidate, true) }) { Text(stringResource(com.pickaudio.R.string.ui_relinkcandidatesdialog_006)) } },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text(stringResource(com.pickaudio.R.string.action_cancel)) } })
    }
}
