package com.pickaudio.ui.components

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pickaudio.data.model.Track
import com.pickaudio.data.model.PlaybackUiState
import com.pickaudio.data.model.PlaybackPhase
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pickaudio.playback.PlaybackCoordinator

@Composable
fun ConnectedMiniPlayer(coordinator: PlaybackCoordinator, onClick: () -> Unit) {
    val track by coordinator.currentTrack.collectAsStateWithLifecycle()
    val playing by coordinator.isPlaying.collectAsStateWithLifecycle()
    val progress by coordinator.currentPositionMs.collectAsStateWithLifecycle()
    val duration by coordinator.durationMs.collectAsStateWithLifecycle()
    val state by coordinator.uiState.collectAsStateWithLifecycle()
    MiniPlayer(track, playing, progress, duration, coordinator::playOrPause, coordinator::next, onClick,
        playbackState = state)
}

@Composable
fun MiniPlayer(
    currentTrack: Track?,
    isPlaying: Boolean,
    progressMs: Long,
    durationMs: Long,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playbackState: PlaybackUiState = PlaybackUiState()
) {
    if (currentTrack == null) return

    val progressFraction = if (durationMs > 0) (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cover
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (!currentTrack.coverUri.isNullOrEmpty()) {
                        AsyncImage(
                            model = currentTrack.coverUri,
                            contentDescription = currentTrack.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title & Artist
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentTrack.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (playbackState.phase in listOf(PlaybackPhase.RESOLVING, PlaybackPhase.BUFFERING, PlaybackPhase.ERROR, PlaybackPhase.CHOOSE_VERSION)) playbackState.phase.label else currentTrack.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Play / Pause (Tonal button)
                FilledTonalIconButton(
                    onClick = onPlayPauseClick,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) com.pickaudio.R.string.action_pause else com.pickaudio.R.string.action_play),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Next
                IconButton(onClick = onNextClick, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = stringResource(com.pickaudio.R.string.action_next),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Progress indicator bar
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
