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
    entryIds: List<Long> = emptyList()
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
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
                itemsIndexed(queue, key = { index, _ -> (entryIds.getOrNull(index) ?: index.toLong()).toString() }) { index, track ->
                    MusicTrackRow(track, isCurrent = index == currentIndex, onClick = { onTrackClick(index) },
                        actions = listOf(TrackMenuAction("移出队列") { onRemoveItem(index) }),
                        trailing = { ReorderHandle((entryIds.getOrNull(index) ?: index.toLong()).toString(), index, queue.size, state, onMove, {}) })
                }
            }
        }
    }
}
