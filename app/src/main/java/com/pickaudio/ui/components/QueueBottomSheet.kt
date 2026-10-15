package com.pickaudio.ui.components

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pickaudio.data.model.Track
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    queue: List<Track>, currentIndex: Int, onTrackClick: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit, onClearQueue: () -> Unit, onDismiss: () -> Unit,
    onMove: (Int, Int) -> Unit = { _, _ -> }, onUndoClear: () -> Unit = {},
    entryIds: List<Long> = emptyList(),
    onReorder: ((List<Long>, List<Long>) -> Boolean)? = null
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val sourceEntries = remember(queue, entryIds) { queue.mapIndexed { index, track -> (entryIds.getOrNull(index) ?: index.toLong()) to track } }
    var preview by remember { mutableStateOf(sourceEntries) }
    var baseIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var dragging by remember { mutableStateOf(false) }
    val shown = if (dragging) preview else sourceEntries
    val currentId = entryIds.getOrNull(currentIndex)
    fun finishDrag() {
        if (!dragging) return
        val accepted = onReorder?.invoke(preview.map { it.first }, baseIds) ?: true
        dragging = false
        if (!accepted) scope.launch { snackbar.showSnackbar("队列已变化，请重新排序") }
    }
    LaunchedEffect(Unit) { if (currentIndex in queue.indices) state.scrollToItem(currentIndex) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("播放队列 · ${queue.size} 首", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClearQueue, enabled = queue.isNotEmpty()) { Text(stringResource(com.pickaudio.R.string.ui_queuebottomsheet_001)) }
            }
            SnackbarHost(snackbar)
            if (queue.isEmpty()) Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = onUndoClear) { Text(stringResource(com.pickaudio.R.string.ui_queuebottomsheet_002)) }
            } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = state) {
                itemsIndexed(shown, key = { _, entry -> entry.first.toString() }) { index, (id, track) ->
                    MusicTrackRow(track, isCurrent = if (currentId == null) index == currentIndex else id == currentId,
                        onClick = { entryIds.indexOf(id).takeIf { it >= 0 }?.let(onTrackClick) },
                        actions = listOf(TrackMenuAction("移出队列") { entryIds.indexOf(id).takeIf { it >= 0 }?.let(onRemoveItem) }),
                        trailing = { ReorderHandle(id.toString(), index, shown.size, state, { from, to ->
                            if (onReorder == null) onMove(from, to)
                            else {
                                if (!dragging) { preview = sourceEntries; baseIds = sourceEntries.map { it.first }; dragging = true }
                                preview = preview.toMutableList().apply { add(to, removeAt(from)) }
                            }
                        }, ::finishDrag) })
                }
            }
        }
    }
}
