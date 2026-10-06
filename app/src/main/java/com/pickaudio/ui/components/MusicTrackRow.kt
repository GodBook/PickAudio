package com.pickaudio.ui.components

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pickaudio.data.model.Platform
import com.pickaudio.data.model.Track
import kotlinx.coroutines.launch
import kotlin.math.abs

data class TrackMenuAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicTrackRow(
    track: Track, isCurrent: Boolean = false, isPlaying: Boolean = false,
    selecting: Boolean = false, selected: Boolean = false, onClick: () -> Unit,
    onSelect: () -> Unit = {}, onFavorite: (() -> Unit)? = null,
    actions: List<TrackMenuAction> = emptyList(), trailing: (@Composable () -> Unit)? = null
) {
    var menu by remember(track.id) { mutableStateOf(false) }
    val rowState = stringResource(if (selecting) {
        if (selected) com.pickaudio.R.string.selection_selected else com.pickaudio.R.string.selection_unselected
    } else if (isPlaying) com.pickaudio.R.string.playback_playing else com.pickaudio.R.string.playback_current_paused)
    val origin = if (track.localUri != null) { if (track.sourceType == "DOWNLOADED") "已下载" else "本地" }
        else track.platform?.let { Platform.fromId(it).displayName } ?: "待关联文件"
    Surface(color = if (selected || isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selecting) onSelect() else onClick() }, onLongClick = onSelect
        ).semantics {
            if (selecting) { this.selected = selected; stateDescription = rowState }
            else if (isCurrent) stateDescription = rowState
        }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            if (selecting) Checkbox(selected, { onSelect() })
            else Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (!track.coverUri.isNullOrEmpty()) AsyncImage(track.coverUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.weight(1f).padding(top = 6.dp))
                    if (!selecting) {
                        if (onFavorite != null) IconButton(onClick = onFavorite) {
                            Icon(if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (track.isFavorite) "取消喜欢" else "加入我喜欢",
                                tint = if (track.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        trailing?.invoke()
                        if (actions.isNotEmpty()) Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "${track.title}的更多操作") }
                            DropdownMenu(menu, { menu = false }) {
                                actions.forEach { action -> DropdownMenuItem(text = { Text(action.label, color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }, onClick = { menu = false; action.onClick() }) }
                            }
                        }
                    }
                }
                Text("${track.artist} · ${track.album}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$origin · ${formatMusicTime(track.durationMs)}${if (isCurrent) if (isPlaying) " · 正在播放" else " · 当前歌曲" else ""}",
                    style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                track.repairReason?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
fun ReorderHandle(key: String, index: Int, count: Int, listState: LazyListState, onMove: (Int, Int) -> Unit, onFinish: () -> Unit) {
    val currentIndex by rememberUpdatedState(index)
    val currentCount by rememberUpdatedState(count)
    val move by rememberUpdatedState(onMove)
    val finish by rememberUpdatedState(onFinish)
    val scope = rememberCoroutineScope()
    Icon(Icons.Default.DragHandle, contentDescription = stringResource(com.pickaudio.R.string.ui_musictrackrow_001), modifier = Modifier.size(48.dp).padding(12.dp)
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction("向上移动") { if (currentIndex > 0) { move(currentIndex, currentIndex - 1); finish(); true } else false },
                CustomAccessibilityAction("向下移动") { if (currentIndex + 1 < currentCount) { move(currentIndex, currentIndex + 1); finish(); true } else false }
            )
        }.pointerInput(key) {
            var accumulated = 0f
            detectDragGestures(onDragStart = { accumulated = 0f }, onDragEnd = { finish() }, onDragCancel = { finish() }) { change, drag ->
                change.consume()
                accumulated += drag.y
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                val height = (info?.size ?: 80).coerceAtLeast(48).toFloat()
                if (abs(accumulated) > height * 0.6f) {
                    val from = currentIndex
                    val to = (from + if (accumulated > 0) 1 else -1).coerceIn(0, currentCount - 1)
                    if (from != to) { move(from, to); accumulated = 0f }
                    val visible = listState.layoutInfo.visibleItemsInfo
                    if (to >= (visible.lastOrNull()?.index ?: to) || to <= (visible.firstOrNull()?.index ?: to)) scope.launch { listState.animateScrollToItem(to) }
                }
            }
        })
}
