package com.pickaudio.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import com.pickaudio.data.model.PlaybackMode
import com.pickaudio.data.model.PlaybackPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.IdentityHashMap

@OptIn(UnstableApi::class)
internal fun PlaybackQueueEntry.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(track.localUri ?: "pickaudio://queue/$id")
    .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
        .setAlbumTitle(track.album).setArtworkUri(track.coverUri?.let(Uri::parse))
        .setIsPlayable(true).setIsBrowsable(false).build()).build()

/** Standard session commands address the same queue occurrences as the application. */
@OptIn(UnstableApi::class)
class QueueSessionPlayer(private val coordinator: PlaybackCoordinator) : ForwardingPlayer(coordinator.player) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = IdentityHashMap<Player.Listener, Player.Listener>()
    private data class State(val entries: List<PlaybackQueueEntry>, val index: Int,
        val mode: PlaybackMode, val phase: PlaybackPhase, val requested: Boolean)
    private var previous: State? = null

    init {
        scope.launch {
            combine(coordinator.queueEntries, coordinator.currentIndex, coordinator.playbackMode,
                coordinator.uiState, coordinator.playRequested) { entries, index, mode, state, requested ->
                State(entries, index, mode, state.phase, requested)
            }.distinctUntilChanged().collect { state ->
                val old = previous
                previous = state
                val flags = FlagSet.Builder()
                val timelineChanged = old?.entries != state.entries || (state.mode == PlaybackMode.SHUFFLE && old.index != state.index)
                if (timelineChanged) flags.add(Player.EVENT_TIMELINE_CHANGED)
                if (old?.index != state.index || old.entries.getOrNull(old.index)?.id != state.entries.getOrNull(state.index)?.id) {
                    flags.add(Player.EVENT_MEDIA_ITEM_TRANSITION).add(Player.EVENT_MEDIA_METADATA_CHANGED)
                }
                if (old?.mode != state.mode) flags.add(Player.EVENT_REPEAT_MODE_CHANGED).add(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
                if (old?.phase != state.phase) flags.add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                if (old?.requested != state.requested) flags.add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                flags.add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                val events = Player.Events(flags.build())
                listeners.keys.toList().forEach { listener ->
                    if (timelineChanged) listener.onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                        listener.onMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                        listener.onMediaMetadataChanged(mediaMetadata)
                    }
                    if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) listener.onRepeatModeChanged(repeatMode)
                    if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
                    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) listener.onPlaybackStateChanged(playbackState)
                    if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) listener.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    listener.onAvailableCommandsChanged(availableCommands)
                    listener.onEvents(this@QueueSessionPlayer, events)
                }
            }
        }
    }

    override fun addListener(listener: Player.Listener) {
        if (listeners.containsKey(listener)) return
        val wrapped = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = listener.onEvents(this@QueueSessionPlayer, events)
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = listener.onTimelineChanged(currentTimeline, reason)
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = listener.onMediaItemTransition(currentMediaItem, reason)
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = listener.onMediaMetadataChanged(this@QueueSessionPlayer.mediaMetadata)
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = listener.onAvailableCommandsChanged(this@QueueSessionPlayer.availableCommands)
            override fun onPlaybackStateChanged(playbackState: Int) = listener.onPlaybackStateChanged(this@QueueSessionPlayer.playbackState)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = listener.onPlayWhenReadyChanged(this@QueueSessionPlayer.playWhenReady, reason)
            override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(this@QueueSessionPlayer.isPlaying)
            override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(error)
            override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error)
            override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(this@QueueSessionPlayer.repeatMode)
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = listener.onShuffleModeEnabledChanged(this@QueueSessionPlayer.shuffleModeEnabled)
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = listener.onPlaybackParametersChanged(playbackParameters)
            override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
                listener.onPositionDiscontinuity(mapPosition(oldPosition), mapPosition(newPosition), reason)
        }
        listeners[listener] = wrapped
        super.addListener(wrapped)
    }

    private fun mapPosition(position: Player.PositionInfo): Player.PositionInfo {
        val index = coordinator.queueEntries.value.indexOfFirst { it.id.toString() == position.mediaItem?.mediaId }
            .takeIf { it >= 0 } ?: currentMediaItemIndex
        val item = coordinator.queueEntries.value.getOrNull(index)
        return Player.PositionInfo(item?.id, index, item?.toMediaItem(), item?.id, index,
            position.positionMs, position.contentPositionMs, C.INDEX_UNSET, C.INDEX_UNSET)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
        .addAll(Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE)
        .addIf(Player.COMMAND_SEEK_TO_NEXT, mediaItemCount > 0)
        .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, mediaItemCount > 0)
        .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, mediaItemCount > 0)
        .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, mediaItemCount > 0)
        .addIf(Player.COMMAND_SEEK_TO_MEDIA_ITEM, mediaItemCount > 0)
        .build()

    override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
    override fun play() = coordinator.resume()
    override fun pause() = coordinator.pause()
    override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) play() else pause() }
    override fun getPlayWhenReady() = coordinator.playRequested.value
    override fun prepare() {
        if (coordinator.uiState.value.phase != PlaybackPhase.RESOLVING &&
            coordinator.player.playbackState == Player.STATE_IDLE && mediaItemCount > 0)
            coordinator.playQueueItem(currentMediaItemIndex, coordinator.currentPositionMs.value, false)
    }
    override fun stop() = coordinator.stopPlayback()
    override fun getPlaybackState() = if (coordinator.uiState.value.phase == PlaybackPhase.RESOLVING) Player.STATE_BUFFERING else super.getPlaybackState()
    override fun getRepeatMode() = when (coordinator.playbackMode.value) {
        PlaybackMode.SINGLE_LOOP -> if (coordinator.stopAfterCurrentTrack) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        PlaybackMode.LIST_LOOP, PlaybackMode.SHUFFLE -> Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_OFF
    }
    override fun setRepeatMode(repeatMode: Int) = coordinator.setPlaybackMode(when (repeatMode) {
        Player.REPEAT_MODE_ONE -> PlaybackMode.SINGLE_LOOP
        Player.REPEAT_MODE_ALL -> if (shuffleModeEnabled) PlaybackMode.SHUFFLE else PlaybackMode.LIST_LOOP
        else -> PlaybackMode.SEQUENTIAL
    })
    override fun getShuffleModeEnabled() = coordinator.playbackMode.value == PlaybackMode.SHUFFLE
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) =
        coordinator.setPlaybackMode(if (shuffleModeEnabled) PlaybackMode.SHUFFLE else PlaybackMode.SEQUENTIAL)
    override fun getMediaItemCount() = coordinator.queueEntries.value.size
    override fun getMediaItemAt(index: Int) = coordinator.queueEntries.value[index].toMediaItem()
    override fun getCurrentMediaItem() = coordinator.queueEntries.value.getOrNull(coordinator.currentIndex.value)?.toMediaItem()
    override fun getMediaMetadata() = currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY
    override fun getCurrentMediaItemIndex() = coordinator.currentIndex.value.coerceAtLeast(0)
    override fun getCurrentWindowIndex() = currentMediaItemIndex
    override fun getCurrentPeriodIndex() = currentMediaItemIndex
    override fun getNextMediaItemIndex() = coordinator.nextQueueIndex()
    override fun getPreviousMediaItemIndex() = coordinator.previousQueueIndex()
    override fun getNextWindowIndex() = nextMediaItemIndex
    override fun getPreviousWindowIndex() = previousMediaItemIndex
    override fun hasNextMediaItem() = mediaItemCount > 0
    override fun hasPreviousMediaItem() = mediaItemCount > 0
    override fun hasNext() = hasNextMediaItem()
    override fun hasPrevious() = hasPreviousMediaItem()
    override fun hasNextWindow() = hasNextMediaItem()
    override fun hasPreviousWindow() = hasPreviousMediaItem()
    override fun seekToNext() = coordinator.next()
    override fun seekToNextMediaItem() = coordinator.next()
    override fun seekToNextWindow() = coordinator.next()
    override fun next() = coordinator.next()
    override fun seekToPrevious() = coordinator.previous()
    override fun seekToPreviousMediaItem() = coordinator.previousQueueItem()
    override fun seekToPreviousWindow() = coordinator.previousQueueItem()
    override fun previous() = coordinator.previous()
    override fun seekTo(positionMs: Long) = coordinator.seekTo(positionMs)
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val position = if (positionMs == C.TIME_UNSET) 0 else positionMs
        if (mediaItemIndex == currentMediaItemIndex && coordinator.player.playbackState != Player.STATE_IDLE) coordinator.seekTo(position)
        else coordinator.playQueueItem(mediaItemIndex, position, playWhenReady)
    }
    override fun seekToDefaultPosition() = seekTo(currentMediaItemIndex, 0)
    override fun seekToDefaultPosition(mediaItemIndex: Int) = seekTo(mediaItemIndex, 0)
    override fun getDuration() = coordinator.durationMs.value.takeIf { it > 0 } ?: C.TIME_UNSET
    override fun getContentDuration() = duration
    override fun getCurrentPosition() = if (coordinator.uiState.value.phase in listOf(PlaybackPhase.RESOLVING, PlaybackPhase.ERROR)) coordinator.currentPositionMs.value else super.getCurrentPosition()
    override fun getContentPosition() = currentPosition
    override fun getCurrentTimeline(): Timeline = QueueTimeline(coordinator)
    override fun isCurrentMediaItemSeekable() = duration > 0
    override fun isCurrentWindowSeekable() = isCurrentMediaItemSeekable
    override fun release() { close(); coordinator.onServiceDestroyed() }

    fun close() {
        scope.cancel()
        listeners.values.toList().forEach { super.removeListener(it) }
        listeners.clear()
    }
}

@OptIn(UnstableApi::class)
private class QueueTimeline(private val coordinator: PlaybackCoordinator) : Timeline() {
    private val entries = coordinator.queueEntries.value.toList()
    private val currentIndex = coordinator.currentIndex.value
    private val nextIndex = coordinator.nextQueueIndex()
    private val previousIndex = coordinator.previousQueueIndex()
    override fun getWindowCount() = entries.size
    override fun getPeriodCount() = entries.size
    override fun getWindow(index: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        val entry = entries[index]
        val duration = if (index == currentIndex) coordinator.durationMs.value else entry.track.durationMs
        return window.set(entry.id, entry.toMediaItem(), null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
            true, false, null, 0, if (duration > 0) duration * 1000 else C.TIME_UNSET, index, index, 0)
    }
    override fun getPeriod(index: Int, period: Period, setIds: Boolean): Period {
        val entry = entries[index]
        val duration = if (index == currentIndex) coordinator.durationMs.value else entry.track.durationMs
        return period.set(if (setIds) entry.id else null, if (setIds) entry.id else null, index,
            if (duration > 0) duration * 1000 else C.TIME_UNSET, 0)
    }
    override fun getIndexOfPeriod(uid: Any) = entries.indexOfFirst { it.id == uid }
    override fun getUidOfPeriod(index: Int): Any = entries[index].id
    override fun getNextWindowIndex(index: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
        if (repeatMode == Player.REPEAT_MODE_ONE) index
        else if (shuffleModeEnabled && index == currentIndex) nextIndex
        else super.getNextWindowIndex(index, repeatMode, false)
    override fun getPreviousWindowIndex(index: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
        if (repeatMode == Player.REPEAT_MODE_ONE) index
        else if (shuffleModeEnabled && index == currentIndex) previousIndex
        else super.getPreviousWindowIndex(index, repeatMode, false)
}
