package com.pickaudio.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.pickaudio.data.model.Track
import com.pickaudio.data.repository.LibraryRepository
import com.pickaudio.data.repository.RelinkConflictException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Capture the target when the picker opens, and require a visible choice before transferring ownership. */
@Composable
fun rememberRelinkFileAction(repository: LibraryRepository, onLinked: (String) -> Unit, onError: (String) -> Unit): (Track, Uri?) -> Unit {
    val scope = rememberCoroutineScope()
    val linked by rememberUpdatedState(onLinked)
    val error by rememberUpdatedState(onError)
    var target by remember { mutableStateOf<Track?>(null) }
    var chosen by remember { mutableStateOf<Uri?>(null) }
    var conflict by remember { mutableStateOf<RelinkConflictException?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun associate(id: String, uri: Uri, transfer: Boolean) {
        busy = true
        scope.launch {
            try { repository.relinkTrack(id, uri, transfer); conflict = null; linked(id) }
            catch (e: CancellationException) { throw e }
            catch (e: RelinkConflictException) { conflict = e; chosen = uri }
            catch (e: Exception) { error("关联失败：${e.message}") }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = target?.id
        if (uri != null && id != null) associate(id, uri, false)
    }
    conflict?.let { existing ->
        AlertDialog(onDismissRequest = { if (!busy) conflict = null }, title = { Text("确认转移文件关联") },
            text = { Text("此文件已用于《${existing.otherTitle}》。继续后将关联到《${target?.title}》；原歌曲记录、歌单和队列保留，原歌曲可以重新关联文件。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { target?.id?.let { id -> chosen?.let { associate(id, it, true) } } }) { Text("转移关联") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { conflict = null }) { Text("取消") } })
    }
    return { track, uri ->
        if (!busy) {
            target = track
            if (uri == null) picker.launch(arrayOf("audio/*")) else associate(track.id, uri, false)
        }
    }
}
