package com.pickaudio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickaudio.online.LyricLine
import com.pickaudio.online.LyricParser
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncedLyricsView(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    offsetMs: Long,
    onSeekTo: (Long) -> Unit,
    onOffsetChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    fontSizeSp: Int = 18,
    showTranslation: Boolean = true
) {
    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无歌词",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        return
    }

    val activeIndex = remember(currentPositionMs, offsetMs, lyrics) {
        LyricParser.findActiveIndex(lyrics, currentPositionMs, offsetMs)
    }

    val listState = rememberLazyListState()
    var isUserScrolling by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var touchRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> { dragging = true; isUserScrolling = true; touchRevision++ }
                is DragInteraction.Stop, is DragInteraction.Cancel -> { dragging = false; touchRevision++ }
            }
        }
    }

    // Resume auto scroll 5 seconds after user touches
    LaunchedEffect(isUserScrolling, dragging, touchRevision) {
        if (isUserScrolling && !dragging) {
            delay(5000)
            while (listState.isScrollInProgress) delay(100)
            isUserScrolling = false
        }
    }

    // Auto scroll to active index
    LaunchedEffect(activeIndex, isUserScrolling) {
        if (!isUserScrolling && activeIndex in lyrics.indices) {
            var layout = snapshotFlow { listState.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
            if (layout.visibleItemsInfo.none { it.index == activeIndex }) {
                listState.scrollToItem(activeIndex)
                layout = snapshotFlow { listState.layoutInfo }.first { info -> info.visibleItemsInfo.any { it.index == activeIndex } }
            }
            val item = layout.visibleItemsInfo.first { it.index == activeIndex }
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
            listState.animateScrollBy(item.offset + item.size / 2f - center)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(vertical = (maxHeight / 4).coerceIn(8.dp, 96.dp), horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(lyrics) { index, line ->
                val isActive = index == activeIndex
                val textColor = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                val fontSize = if (isActive) fontSizeSp.sp else (fontSizeSp - 2).sp
                val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                        .clickable { onSeekTo(line.timeMs + offsetMs) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = line.text,
                        color = textColor,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        textAlign = TextAlign.Center,
                        lineHeight = (fontSizeSp + 8).sp
                    )
                    if (showTranslation && !line.translation.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = line.translation!!,
                            color = textColor,
                            fontSize = (fontSizeSp - 3).sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        }

        if (isUserScrolling) TextButton(onClick = { isUserScrolling = false }) { Text("回到当前歌词") }
        // Offset Calibration Controls (±100ms)
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "校准: ${offsetMs}ms",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = { onOffsetChange(offsetMs - 100) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "-100ms", modifier = Modifier.size(16.dp))
                        Text("-100ms", fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    FilledTonalButton(
                        onClick = { onOffsetChange(offsetMs + 100) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "+100ms", modifier = Modifier.size(16.dp))
                        Text("+100ms", fontSize = 11.sp)
                    }

                    if (offsetMs != 0L) {
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = { onOffsetChange(0L) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "重置", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
