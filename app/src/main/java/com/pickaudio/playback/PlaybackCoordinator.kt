package com.pickaudio.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pickaudio.data.db.OnlineRefEntity
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.db.PlaybackSnapshotEntity
import com.pickaudio.data.db.QueueEntryEntity
import com.pickaudio.data.db.TrackEntity
import com.pickaudio.data.model.PlaybackMode
import com.pickaudio.data.model.Track
import com.pickaudio.data.model.PlaybackPhase
import com.pickaudio.data.model.PlaybackUiState
import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.data.model.toTrack
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.online.AlternativeVersionException
import com.pickaudio.source.LxSourceManager
import com.pickaudio.data.repository.toTrack
import com.pickaudio.data.repository.rankAssets
import com.pickaudio.data.model.AudioInfo
import com.pickaudio.download.AudioFormatProbe
import com.pickaudio.download.AndroidAudioProbe
import com.pickaudio.data.repository.checkAssetAccess
import com.pickaudio.data.repository.ensureTrackIdentity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import java.util.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackCoordinator(
    private val context: Context,
    private val database: PickAudioDatabase,
    private val sourceManager: LxSourceManager,
    val userPreferences: UserPreferences = UserPreferences(context)
) {
    private val playbackDao = database.playbackDao()
    private val queueDao = database.queueDao()
    private val trackDao = database.trackDao()
    private val localAssetDao = database.localAssetDao()
    private val favoriteDao = database.favoriteDao()

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("PlaybackCoordinator", "Unhandled coroutine error in PlaybackCoordinator", throwable)
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + coroutineExceptionHandler)
    private val gson = Gson()

    private var requestJob: Job? = null
    private var serviceStart: CompletableDeferred<Unit>? = null
    private var requestGeneration = 0L
    private var restoringJob: Job? = null
    private val _restored = MutableStateFlow(false)
    val restored = _restored.asStateFlow()
    private val queueModel = PlaybackQueue()
    private data class WriteRequest(val queue: QueueSnapshot, val position: Long, val mode: PlaybackMode,
        val completion: CompletableDeferred<Unit>? = null)
    private val writes = Channel<WriteRequest>(Channel.UNLIMITED)
    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState = _uiState.asStateFlow()
    private val _versionCandidates = MutableStateFlow<List<SearchSongItem>>(emptyList())
    val versionCandidates = _versionCandidates.asStateFlow()
    private data class ClearedQueue(val queue: QueueSnapshot, val position: Long, val playing: Boolean)
    private var clearedQueue: ClearedQueue? = null
    private val _queueEntries = MutableStateFlow<List<PlaybackQueueEntry>>(emptyList())
    val queueEntries = _queueEntries.asStateFlow()
    private val _playRequested = MutableStateFlow(false)
    val playRequested = _playRequested.asStateFlow()

    private var playerInstance: ExoPlayer? = null
    private var resolvedAudioInfo: AudioInfo? = null
    private var resolvedAssetId: Long? = null
    private var resolvedCacheKey: String? = null
    val player: ExoPlayer get() = playerInstance ?: createPlayer().also { playerInstance = it }
    private fun createPlayer(): ExoPlayer {
        val dataSourceFactory = AudioCacheManager.createDataSourceFactory(context, sourceManager.audioClient())
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000
            )
            .setBackBuffer(15_000, true)
            .build()

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true // handle audio focus automatically
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                repeatMode = if (_playbackMode.value == PlaybackMode.SINGLE_LOOP && !stopAfterCurrentTrack) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                addListener(playerListener)
            }
    }

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playbackMode = MutableStateFlow(PlaybackMode.SEQUENTIAL)
    val playbackMode: StateFlow<PlaybackMode> = _playbackMode.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // Sleep Timer
    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs.asStateFlow()
    private var sleepTimerJob: Job? = null
    private var stopAfterState by androidx.compose.runtime.mutableStateOf(false)
    var stopAfterCurrentTrack: Boolean
        get() = stopAfterState
        set(value) {
            stopAfterState = value
            playerInstance?.repeatMode = if (!value && _playbackMode.value == PlaybackMode.SINGLE_LOOP) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }

    // Shuffle history and round tracking
    // Periodic progress saver
    private var progressTickerJob: Job? = null
    private var snapshotSaverJob: Job? = null
    private var consecutiveFailures = 0
    private var automaticTrack = false
    private var skipJob: Job? = null
    private var recoveryJob: Job? = null
    private var stallMonitorJob: Job? = null
    private var streamRecoveryAttempts = 0

    init {
        startSnapshotWriter()
        restoreSnapshot()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
            if (playing) PlaybackDiagnostics.playing()
            if (playing) startProgressTicker()
            else {
                if (playerInstance?.playbackState == Player.STATE_READY) _currentPositionMs.value = player.currentPosition.coerceAtLeast(0)
                progressTickerJob?.cancel()
                snapshotSaverJob?.cancel()
            }
            if (playing) _currentTrack.value?.let { track -> scope.launch { userPreferences.recordPlayed(track.id) } }
            saveSnapshot()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (_uiState.value.phase in listOf(PlaybackPhase.ERROR, PlaybackPhase.CHOOSE_VERSION, PlaybackPhase.RESOLVING)) return
            // isPlaying also becomes false while buffering or temporarily losing audio focus.
            // Only a cleared play request represents a pause; keep the service alive while waiting.
            val phase = when {
                player.playbackState == Player.STATE_BUFFERING && _playRequested.value -> PlaybackPhase.BUFFERING
                player.isPlaying -> PlaybackPhase.PLAYING
                _playRequested.value && player.playbackState == Player.STATE_READY -> PlaybackPhase.READY
                _currentTrack.value != null -> PlaybackPhase.PAUSED
                else -> PlaybackPhase.IDLE
            }
            _uiState.value = _uiState.value.copy(phase = phase)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (_uiState.value.phase != PlaybackPhase.RESOLVING) _playRequested.value = playWhenReady
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_BUFFERING) PlaybackDiagnostics.buffering()
            if (state == Player.STATE_READY) {
                _durationMs.value = player.duration.coerceAtLeast(0L)
                val format = player.audioFormat
                val mime = format?.sampleMimeType.orEmpty()
                val header = format?.initializationData?.firstNotNullOfOrNull { AudioFormatProbe.detect(it) } ?: resolvedAudioInfo
                val bits = header?.bitDepth ?: when (format?.pcmEncoding) {
                    C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24; C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                    C.ENCODING_PCM_8BIT -> 8; else -> null
                }
                val info = AudioInfo.fromMime(mime, header, bits,
                    format?.sampleRate?.takeIf { it > 0 } ?: header?.sampleRate,
                    format?.channelCount?.takeIf { it > 0 } ?: header?.channels,
                    format?.averageBitrate?.takeIf { it > 0 }?.toLong() ?: header?.bitrate)
                resolvedAudioInfo = info
                resolvedAssetId?.let { id -> scope.launch(Dispatchers.IO) { localAssetDao.updateAudioInfo(id, info.encode()) } }
                val failure = info.qualityFailure(_uiState.value.requestedQuality)
                _uiState.value = _uiState.value.copy(phase = if (player.playWhenReady) PlaybackPhase.PLAYING else PlaybackPhase.READY,
                    actualQuality = info.label, message = info.qualityNotice(_uiState.value.requestedQuality))
                if (failure != null) {
                    failCurrentTrack("$failure，请更换音质或音源")
                    player.stop()
                }
            } else if (state == Player.STATE_BUFFERING && _uiState.value.phase != PlaybackPhase.RESOLVING) {
                _uiState.value = _uiState.value.copy(phase = PlaybackPhase.BUFFERING)
            } else if (state == Player.STATE_ENDED) {
                handleTrackEnded()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Errors queued by a replaced/stopped stream must not overwrite the new resolution.
            if (_uiState.value.phase == PlaybackPhase.RESOLVING) return
            handlePlaybackFailure(error)
        }
    }

    private fun handlePlaybackFailure(error: androidx.media3.common.PlaybackException) {
        Log.e("PlaybackCoordinator", "ExoPlayer playback error: ${error.errorCodeName}", error)
        _isPlaying.value = false
        if (recoverStream(error)) return
        failCurrentTrack("无法播放这首歌，请检查网络、文件授权或切换音源后重试")
    }

    private fun startStallMonitor(generation: Long) {
        stallMonitorJob?.cancel()
        stallMonitorJob = scope.launch {
            val monitor = PlaybackStallMonitor()
            while (isActive && generation == requestGeneration) {
                val current = playerInstance ?: break
                val buffering = current.playbackState == Player.STATE_BUFFERING
                val active = _playRequested.value &&
                    current.currentMediaItem?.localConfiguration?.uri?.scheme in listOf("http", "https") &&
                    current.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                    (buffering || current.isPlaying) &&
                    _uiState.value.phase !in listOf(PlaybackPhase.RESOLVING, PlaybackPhase.ERROR)
                if (monitor.stalled(SystemClock.elapsedRealtime(), active, buffering,
                        current.currentPosition, current.bufferedPosition)) {
                    handlePlaybackFailure(androidx.media3.common.PlaybackException(
                        "音频连接长时间没有播放进展", null,
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
                    current.stop()
                    break
                }
                delay(500)
            }
        }
    }

    private fun recoverStream(error: androidx.media3.common.PlaybackException): Boolean {
        val track = _currentTrack.value ?: return false
        val remote = playerInstance?.currentMediaItem?.localConfiguration?.uri?.scheme in listOf("http", "https")
        if (!remote || !_playRequested.value || streamRecoveryAttempts >= 2 || !isRecoverableStreamError(error)) return false
        val attempt = ++streamRecoveryAttempts
        val generation = requestGeneration
        val position = playerInstance?.currentPosition?.coerceAtLeast(0) ?: _currentPositionMs.value
        val quality = _uiState.value.requestedQuality
        _currentPositionMs.value = position
        _uiState.value = _uiState.value.copy(phase = PlaybackPhase.RESOLVING, message = "播放连接中断，正在重新连接")
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            delay(attempt * 1000L)
            if (generation == requestGeneration && _playRequested.value) {
                playTrack(track, startPosition = position, automatic = automaticTrack, requestedQuality = quality, recovering = true)
            }
        }
        return true
    }

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                try {
                    if (_isPlaying.value) {
                        _currentPositionMs.value = player.currentPosition.coerceAtLeast(0L)
                        _durationMs.value = player.duration.coerceAtLeast(0L)
                        if (_currentPositionMs.value >= 1000) consecutiveFailures = 0
                    }
                } catch (e: Exception) {
                    Log.w("PlaybackCoordinator", "Error updating progress ticker: ${e.message}")
                }
                delay(500)
            }
        }

        snapshotSaverJob?.cancel()
        snapshotSaverJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(5000)
                try {
                    if (_isPlaying.value) {
                        saveSnapshot()
                    }
                } catch (e: Exception) {
                    Log.w("PlaybackCoordinator", "Error in snapshot saver loop: ${e.message}")
                }
            }
        }
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        _playbackMode.value = mode
        playerInstance?.repeatMode = if (mode == PlaybackMode.SINGLE_LOOP && !stopAfterCurrentTrack) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (mode == PlaybackMode.SHUFFLE) {
            queueModel.resetShuffle(clearHistory = true)
        }
        saveSnapshot()
    }

    fun togglePlaybackMode(): PlaybackMode {
        val next = when (_playbackMode.value) {
            PlaybackMode.SEQUENTIAL -> PlaybackMode.LIST_LOOP
            PlaybackMode.LIST_LOOP -> PlaybackMode.SINGLE_LOOP
            PlaybackMode.SINGLE_LOOP -> PlaybackMode.SHUFFLE
            PlaybackMode.SHUFFLE -> PlaybackMode.SEQUENTIAL
        }
        setPlaybackMode(next)
        return next
    }

    private suspend fun ensureTracksPersisted(tracks: List<Track>): Map<String, Track> =
        tracks.associate { it.id to database.ensureTrackIdentity(it) }

    private fun applyCanonicalTracks(mapping: Map<String, Track>) {
        if (queueModel.canonicalize(mapping)) {
            publishQueue()
            _currentTrack.value = queueModel.current?.track
            saveSnapshot()
        }
    }

    fun setQueueAndPlay(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) { return }
        restoringJob?.cancel()
        queueModel.replace(tracks, startIndex.coerceIn(tracks.indices))
        publishQueue()
        playTrack(queueModel.current!!.track)
        persistQueue()
    }

    private fun publishQueue() {
        _queueEntries.value = queueModel.entries
        _queue.value = queueModel.entries.map { it.track }
        _currentIndex.value = queueModel.currentIndex
    }

    private fun persistQueue() = saveSnapshot()

    fun playQueueItem(index: Int, position: Long = 0L, autoPlay: Boolean = true) {
        restoringJob?.cancel()
        val track = queueModel.select(index) ?: return
        publishQueue()
        playTrack(track, startPosition = position, autoPlay = autoPlay)
    }

    fun playNext(track: Track) {
        restoringJob?.cancel()
        queueModel.insert(track, if (queueModel.currentIndex >= 0) queueModel.currentIndex + 1 else queueModel.entries.size)
        publishQueue()
        persistQueue()
    }

    fun addToQueue(track: Track) = addTracksToQueue(listOf(track))

    fun addTracksToQueue(tracks: List<Track>) {
        restoringJob?.cancel()
        tracks.forEach { queueModel.insert(it) }
        publishQueue()
        persistQueue()
    }

    fun removeQueueItem(index: Int) {
        restoringJob?.cancel()
        if (index !in _queue.value.indices) return
        val cur = _currentIndex.value
        queueModel.remove(index)
        publishQueue()
        if (_queue.value.isEmpty()) {
            requestJob?.cancel()
            recoveryJob?.cancel()
            requestGeneration++
            _playRequested.value = false
            player.stop()
            _currentTrack.value = null
            _uiState.value = PlaybackUiState()
        } else if (index == cur) {
            playTrack(queueModel.current!!.track)
        }
        persistQueue()
    }

    fun removeTrackFromQueue(trackId: String) {
        val currentList = _queue.value
        val indices = currentList.mapIndexedNotNull { idx, t -> if (t.id == trackId) idx else null }.reversed()
        for (idx in indices) {
            removeQueueItem(idx)
        }
    }

    fun clearQueue() {
        clearedQueue = ClearedQueue(queueModel.snapshot(), _currentPositionMs.value, _isPlaying.value || _playRequested.value)
        requestJob?.cancel()
        recoveryJob?.cancel()
        skipJob?.cancel()
        restoringJob?.cancel()
        requestGeneration++
        _playRequested.value = false
        player.stop()
        queueModel.clear()
        publishQueue()
        _currentTrack.value = null
        _uiState.value = PlaybackUiState()
        persistQueue()
    }

    fun undoClearQueue() {
        val saved = clearedQueue ?: return
        clearedQueue = null
        queueModel.restore(saved.queue)
        publishQueue()
        queueModel.current?.track?.let { playTrack(it, startPosition = saved.position, autoPlay = saved.playing) }
        persistQueue()
    }

    fun moveQueueItem(from: Int, to: Int) {
        queueModel.move(from, to)
        publishQueue()
        persistQueue()
    }

    fun retryCurrent() { _currentTrack.value?.let { playTrack(it, startPosition = _currentPositionMs.value, requestedQuality = _uiState.value.requestedQuality) } }
    fun changeQuality(quality: String) {
        val track = _currentTrack.value ?: return
        val position = playerInstance?.takeIf { it.playbackState == Player.STATE_READY }?.currentPosition ?: _currentPositionMs.value
        playTrack(track, startPosition = position, autoPlay = _playRequested.value, requestedQuality = quality)
    }
    fun addOrPlayTrack(track: Track) {
        val index = _queue.value.indexOfFirst { it.id == track.id }
        if (index >= 0) playQueueItem(index)
        else if (_queue.value.isEmpty()) setQueueAndPlay(listOf(track))
        else {
            val next = queueModel.insert(track, (_currentIndex.value + 1).coerceAtLeast(0))
            playQueueItem(next)
            persistQueue()
        }
    }
    fun dismissVersionCandidates() { _versionCandidates.value = emptyList() }
    fun confirmVersion(item: SearchSongItem) {
        replaceCurrentTrack(item.toTrack())
    }

    fun replaceCurrentTrack(track: Track) {
        queueModel.replaceCurrent(track)
        publishQueue()
        _versionCandidates.value = emptyList()
        playTrack(track)
        persistQueue()
    }
    fun refreshTrackMetadata(track: Track) {
        queueModel.refreshTrack(track)
        publishQueue()
        if (_currentTrack.value?.id == track.id) {
            pause()
            playerInstance?.stop()
            _currentTrack.value = track
            _durationMs.value = track.durationMs
            _uiState.value = PlaybackUiState(PlaybackPhase.PAUSED)
        }
        persistQueue()
    }

    suspend fun clearPlaybackCache() {
        val track = _currentTrack.value
        val position = player.currentPosition
        val resume = player.playWhenReady
        player.stop()
        withContext(Dispatchers.IO) { AudioCacheManager.clear(context) }
        if (track != null) {
            playTrack(track, startPosition = position, autoPlay = resume)
        }
    }

    fun playOrPause() {
        if (_uiState.value.phase == PlaybackPhase.ERROR) { retryCurrent(); return }
        if (_playRequested.value) pause() else resume()
        saveSnapshot()
    }

    fun pause() {
        stallMonitorJob?.cancel()
        _playRequested.value = false
        requestJob?.cancel()
        requestGeneration++
        skipJob?.cancel()
        recoveryJob?.cancel()
        if (_uiState.value.phase == PlaybackPhase.RESOLVING) {
            _uiState.value = _uiState.value.copy(phase = PlaybackPhase.PAUSED, message = "已取消加载，点击播放可重试")
        }
        playerInstance?.pause()
        saveSnapshot()
    }

    fun resume() {
        if (_uiState.value.phase == PlaybackPhase.RESOLVING) {
            val generation = requestGeneration
            _playRequested.value = true
            scope.launch {
                try { ensurePlaybackService() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (generation == requestGeneration) onPlaybackServiceStartFailed(e)
                }
            }
            return
        }
        if (_currentTrack.value == null && _queue.value.isNotEmpty()) playQueueItem(_currentIndex.value.takeIf { it >= 0 } ?: 0)
        else if (_currentTrack.value == null) return
        else if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            _currentTrack.value?.let { playTrack(it, startPosition = if (player.playbackState == Player.STATE_ENDED) 0 else _currentPositionMs.value) }
        } else {
            _playRequested.value = true
            requestJob?.cancel()
            val generation = ++requestGeneration
            requestJob = scope.launch {
                try {
                    ensurePlaybackService()
                    ensureActive()
                    if (generation == requestGeneration && _playRequested.value) {
                        player.play()
                        startStallMonitor(generation)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (generation == requestGeneration) onPlaybackServiceStartFailed(e)
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _currentPositionMs.value = positionMs
        saveSnapshot()
    }

    private suspend fun ensurePlaybackService() {
        val ready = serviceStart?.takeUnless { it.isCompleted } ?: CompletableDeferred<Unit>().also {
            serviceStart = it
            try {
                androidx.core.content.ContextCompat.startForegroundService(context,
                    android.content.Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_START_PLAYBACK))
            } catch (e: Exception) { it.completeExceptionally(e) }
        }
        try {
            // Android 15+ rejects background audio focus requests until a foreground service exists.
            withTimeout(8_000) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            ready.cancel()
            throw IllegalStateException("Playback service did not become ready", e)
        } finally {
            if (serviceStart === ready && ready.isCompleted) serviceStart = null
        }
    }

    internal fun onPlaybackServiceReady() { serviceStart?.complete(Unit) }

    internal fun onPlaybackServiceStartFailed(error: Exception) {
        Log.w("PlaybackCoordinator", "无法启动后台播放服务", error)
        serviceStart?.completeExceptionally(error)
        requestJob?.cancel()
        recoveryJob?.cancel()
        requestGeneration++
        _playRequested.value = false
        playerInstance?.pause()
        _uiState.value = _uiState.value.copy(phase = PlaybackPhase.ERROR, message = "后台播放服务暂时无法启动，请打开应用后重试")
        saveSnapshot()
    }

    fun previous() = previousInternal(false)
    fun previousQueueItem() = previousInternal(true)
    fun nextQueueIndex(): Int = queueModel.peekNext(_playbackMode.value) ?: C.INDEX_UNSET
    fun previousQueueIndex(): Int = queueModel.peekPrevious(_playbackMode.value) ?: C.INDEX_UNSET
    internal fun shuffleTimelineOrder(): List<Int> = queueModel.shuffleTimelineOrder()

    private fun previousInternal(forceTrack: Boolean) {
        // Smart Previous Rule: if progress > 3s, seek to 0. Else jump previous.
        if (!forceTrack && shouldRestartOnPrevious(_currentPositionMs.value)) {
            player.seekTo(0L)
            _currentPositionMs.value = 0L
            return
        }

        val q = _queue.value
        if (q.isEmpty()) { return }

        queueModel.previous(_playbackMode.value)?.let { index ->
            publishQueue()
            playTrack(q[index])
        }
    }

    fun next() = advanceNext(false)

    fun stopPlayback() {
        pause()
        playerInstance?.stop()
        _uiState.value = _uiState.value.copy(phase = PlaybackPhase.PAUSED)
    }

    private fun advanceNext(automatic: Boolean) {
        val q = _queue.value
        if (q.isEmpty()) { return }
        queueModel.next(_playbackMode.value)?.let { index ->
            publishQueue()
            playTrack(q[index], automatic = automatic)
        }
    }

    private fun handleTrackEnded() {
        if (stopAfterCurrentTrack) {
            pause()
            stopAfterCurrentTrack = false
            return
        }

        val q = _queue.value
        if (q.isEmpty()) { return }
        val next = queueModel.next(_playbackMode.value, naturalEnd = true)
        if (next == null) pause() else {
            publishQueue()
            playTrack(q[next], automatic = true)
        }
    }

    private fun playTrack(track: Track, startPosition: Long = 0L, autoPlay: Boolean = true, automatic: Boolean = false,
        requestedQuality: String? = null, recovering: Boolean = false) {
        restoringJob?.cancel()
        requestJob?.cancel()
        skipJob?.cancel()
        recoveryJob?.cancel()
        if (!recovering) streamRecoveryAttempts = 0
        stallMonitorJob?.cancel()
        automaticTrack = automatic
        if (!automatic) { consecutiveFailures = 0 }
        val generation = ++requestGeneration
        _currentTrack.value = track
        _currentPositionMs.value = startPosition
        _durationMs.value = track.durationMs
        _versionCandidates.value = emptyList()
        resolvedAudioInfo = null; resolvedAssetId = null
        resolvedCacheKey = null
        PlaybackDiagnostics.begin()
        _uiState.value = PlaybackUiState(PlaybackPhase.RESOLVING, requestedQuality = requestedQuality)
        _playRequested.value = autoPlay

        requestJob = scope.launch {
            try {
                // Player creation and reset can fail too (audio service/decoder/cache failures).
                // Keep them inside the same error boundary as resolution and preparation.
                player.stop()
                player.playWhenReady = false
                if (_playRequested.value) ensurePlaybackService()
                val mapping = withContext(Dispatchers.IO) { ensureTracksPersisted(listOf(track)) }
                ensureActive()
                if (generation != requestGeneration) return@launch
                applyCanonicalTracks(mapping)
                val mediaUri = try {
                    resolveTrackMediaUri(mapping.getValue(track.id), generation, requestedQuality)
                } catch (e: TimeoutCancellationException) {
                    ensureActive()
                    throw IllegalStateException("音源请求超时，请重试或更换音源", e)
                }
                ensureActive()
                if (generation != requestGeneration) { return@launch }
                val mediaItem = MediaItem.Builder()
                    .setUri(mediaUri)
                    .setCustomCacheKey(resolvedCacheKey)
                    .setMediaId(queueModel.current?.id?.toString() ?: track.id)
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
                        .setArtworkUri(track.coverUri?.let { Uri.parse(it) }).build())
                    .build()

                // A controller can request play during a paused preparation.
                if (_playRequested.value && !autoPlay) ensurePlaybackService()
                player.setMediaItem(mediaItem)
                player.prepare()
                player.seekTo(_currentPositionMs.value)
                player.playWhenReady = _playRequested.value
                _uiState.value = _uiState.value.copy(phase = PlaybackPhase.BUFFERING)
                startStallMonitor(generation)
            } catch (e: CancellationException) { throw e }
            catch (e: AlternativeVersionException) {
                if (generation == requestGeneration) {
                    _playRequested.value = false
                    _versionCandidates.value = e.candidates
                    _uiState.value = _uiState.value.copy(phase = PlaybackPhase.CHOOSE_VERSION, message = e.message)
                }
            } catch (e: Exception) {
                Log.e("PlaybackCoordinator", "Failed to play track: ${track.title}", e)
                if (generation == requestGeneration) {
                    PlaybackDiagnostics.failed(e.javaClass.simpleName)
                    failCurrentTrack(e.message ?: "无法获取歌曲，请重试或更换音源")
                    _isPlaying.value = false
                    runCatching { playerInstance?.stop() }
                }
            }
        }
        saveSnapshot()
    }

    private fun failCurrentTrack(message: String) {
        consecutiveFailures++
        if (!automaticTrack || _queue.value.size < 2 || consecutiveFailures >= 3 ||
            (_playbackMode.value == PlaybackMode.SEQUENTIAL && _currentIndex.value == _queue.value.lastIndex)) {
            _uiState.value = _uiState.value.copy(phase = PlaybackPhase.ERROR, message = message)
            _playRequested.value = false
            return
        }
        // The queue is still playing. Dropping foreground status during this delay makes the
        // next background service/audio-focus request fail on Android 15+ and some OEMs.
        _uiState.value = _uiState.value.copy(phase = PlaybackPhase.RESOLVING, message = "当前歌曲暂不可用，正在播放下一首")
        _playRequested.value = true
        val generation = requestGeneration
        skipJob = scope.launch {
            delay(1000)
            if (generation == requestGeneration && _playRequested.value) { advanceNext(true) }
        }
    }

    private suspend fun resolveTrackMediaUri(track: Track, generation: Long, requestedQuality: String?): Uri = withContext(Dispatchers.IO) {
        // 1. Check local asset
        val assets = localAssetDao.getAssetsForTrack(track.id)
        for (asset in rankAssets(assets, requestedQuality)) {
            val uri = Uri.parse(asset.uri)
            val access = checkAssetAccess(context, uri)
            if (!access.available) { localAssetDao.updateAvailability(asset.id, false, access.reason); continue }
            val info = AudioInfo.decode(asset.audioInfoJson) ?: try { AndroidAudioProbe.inspect(context, uri).info }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { localAssetDao.updateAvailability(asset.id, false, "音频信息无法读取"); continue }
            if (info.qualityFailure(requestedQuality) != null) continue
            localAssetDao.updateAudioInfo(asset.id, info.encode())
            withContext(Dispatchers.Main) {
                if (generation == requestGeneration) { resolvedAudioInfo = info; resolvedAssetId = asset.id }
            }
            return@withContext uri
        }

        // 2. Check online ref and resolve
        val platform = track.platform
            ?: database.onlineRefDao().getByTrackId(track.id)?.platform
            ?: if (track.id.startsWith("online_wy_")) "wy" else if (track.id.startsWith("online_tx_")) "tx" else null
        val songId = track.platformSongId
            ?: database.onlineRefDao().getByTrackId(track.id)?.platformSongId
            ?: track.id.removePrefix("online_wy_").removePrefix("online_tx_")

        if (platform != null && songId.isNotBlank()) {
            try {
                val quality = requestedQuality ?: userPreferences.defaultOnlineQuality.first().value
                withContext(Dispatchers.Main) {
                    if (generation == requestGeneration) _uiState.value = _uiState.value.copy(requestedQuality = quality)
                }
                val resource = sourceManager.resolveMusicResource(platform, songId, quality, track.title, track.artist)
                val key = resolveAudioCacheKey(sourceManager.audioClient(), resource, platform, songId, quality)
                withContext(Dispatchers.Main) { if (generation == requestGeneration) resolvedCacheKey = key }
                return@withContext Uri.parse(resource.url)
            } catch (e: Exception) { throw e }
        }

        // Fallback to track localUri if present
        throw IllegalStateException("音频文件已移动或访问权限失效，请重新关联文件")
    }

    private fun isAccessible(uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") java.io.File(uri.path.orEmpty()).isFile
        else context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    // Sleep Timer
    fun setSleepTimer(minutes: Int) {
        setSleepTimerDuration(minutes.coerceAtLeast(0) * 60_000L)
    }
    internal fun setSleepTimerDuration(totalMs: Long) {
        sleepTimerJob?.cancel()
        if (totalMs <= 0) {
            _sleepTimerRemainingMs.value = null
            return
        }

        sleepTimerJob = scope.launch {
            val deadline = SystemClock.elapsedRealtime() + totalMs
            var remaining = totalMs
            while (remaining > 0) {
                _sleepTimerRemainingMs.value = remaining
                delay(1000)
                remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            }
            _sleepTimerRemainingMs.value = null
            pause()
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingMs.value = null
        stopAfterCurrentTrack = false
    }

    fun playQueueItemFromUi(index: Int) = playQueueItem(index)

    private fun saveSnapshot() {
        writes.trySend(WriteRequest(queueModel.snapshot(), _currentPositionMs.value, _playbackMode.value))
    }

    suspend fun flushPersistence() = withContext(Dispatchers.Main) {
        val completion = CompletableDeferred<Unit>()
        writes.send(WriteRequest(queueModel.snapshot(), _currentPositionMs.value, _playbackMode.value, completion))
        completion.await()
    }

    private fun startSnapshotWriter() {
        scope.launch(Dispatchers.IO) {
            var savedRevision = Long.MIN_VALUE
            for (first in writes) {
                var request = first
                val completions = mutableListOf<CompletableDeferred<Unit>>()
                first.completion?.let(completions::add)
                while (true) {
                    val next = writes.tryReceive().getOrNull() ?: break
                    request = next
                    next.completion?.let(completions::add)
                }
                try {
                    var mapping = request.queue.entries.associate { it.track.id to it.track }
                    database.withTransaction {
                        if (savedRevision != request.queue.revision) {
                            mapping = ensureTracksPersisted(request.queue.entries.map { it.track }.distinctBy { it.id })
                            queueDao.clearQueue()
                            queueDao.insertQueueEntries(request.queue.entries.mapIndexed { index, entry ->
                                QueueEntryEntity(id = entry.id, trackId = mapping.getValue(entry.track.id).id, queueOrder = index)
                            })
                        }
                        if (request.queue.entries.isEmpty()) playbackDao.clearSnapshot()
                        else playbackDao.saveSnapshot(PlaybackSnapshotEntity(
                            currentTrackId = request.queue.entries.firstOrNull { it.id == request.queue.currentEntryId }?.track?.id?.let { mapping[it]?.id },
                            currentEntryId = request.queue.currentEntryId,
                            progressMs = request.position.coerceAtLeast(0),
                            playbackMode = request.mode.name,
                            shuffleOrderJson = gson.toJson(request.queue.shufflePool),
                            shuffleHistoryJson = gson.toJson(request.queue.shuffleHistory),
                            queueRevision = request.queue.revision
                        ))
                    }
                    savedRevision = request.queue.revision
                    withContext(Dispatchers.Main) { applyCanonicalTracks(mapping) }
                    completions.forEach { it.complete(Unit) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    completions.forEach { it.completeExceptionally(e) }
                    Log.e("PlaybackCoordinator", "保存队列与快照失败", e)
                }
            }
        }
    }

    private fun restoreSnapshot() {
        val generation = requestGeneration
        restoringJob = scope.launch(Dispatchers.IO) {
            try {
            val restored = database.withTransaction {
                val rows = queueDao.getEntriesSync()
                val ids = rows.map { it.trackId }.distinct()
                val tracks = ids.chunked(900).flatMap { trackDao.getTracksByIds(it) }.associateBy { it.id }
                val assets = ids.chunked(900).flatMap { localAssetDao.getAssetsForTracks(it) }.groupBy { it.trackId }
                val refs = ids.chunked(900).flatMap { database.onlineRefDao().getRefsForTracks(it) }.groupBy { it.trackId }
                val favorites = favoriteDao.getAllFavoriteTrackIds().first().toSet()
                val entries = rows.mapNotNull { row -> tracks[row.trackId]?.let {
                    PlaybackQueueEntry(row.id, it.toTrack(assets[it.id].orEmpty(), refs[it.id].orEmpty(), it.id in favorites))
                } }
                entries to playbackDao.getSnapshot()
            }
            val (entries, snapshot) = restored
            if (entries.isEmpty()) return@launch
            fun ids(value: String?) = runCatching {
                com.google.gson.JsonParser.parseString(value ?: "[]").asJsonArray.mapNotNull { runCatching { it.asLong }.getOrNull() }
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                ensureActive()
                if (generation != requestGeneration) return@withContext
                val current = snapshot?.currentEntryId ?: entries.firstOrNull { it.track.id == snapshot?.currentTrackId }?.id
                queueModel.restore(QueueSnapshot(entries, current, ids(snapshot?.shuffleOrderJson), ids(snapshot?.shuffleHistoryJson), 0))
                publishQueue()
                _playbackMode.value = runCatching { PlaybackMode.valueOf(snapshot?.playbackMode ?: "") }.getOrDefault(PlaybackMode.SEQUENTIAL)
                _currentTrack.value = queueModel.current?.track
                _currentPositionMs.value = snapshot?.progressMs?.coerceAtLeast(0) ?: 0
                _durationMs.value = _currentTrack.value?.durationMs ?: 0
                _playRequested.value = false
                _uiState.value = PlaybackUiState(PlaybackPhase.PAUSED)
                queueModel.current?.let { entry ->
                    entry.track.localUri?.let { uri ->
                        player.setMediaItem(entry.toMediaItem().buildUpon().setUri(uri).build())
                        player.prepare()
                        player.seekTo(_currentPositionMs.value)
                        player.pause()
                    }
                }
            }
            } finally { _restored.value = true }
        }
    }

    fun onServiceDestroyed() {
        stallMonitorJob?.cancel()
        serviceStart?.cancel(CancellationException("Playback service destroyed"))
        serviceStart = null
        sleepTimerJob?.cancel()
        _sleepTimerRemainingMs.value = null
        stopAfterState = false
        requestJob?.cancel()
        skipJob?.cancel()
        recoveryJob?.cancel()
        requestGeneration++
        _playRequested.value = false
        playerInstance?.let { current ->
            if (current.playbackState == Player.STATE_READY) _currentPositionMs.value = current.currentPosition.coerceAtLeast(0)
            saveSnapshot()
            current.removeListener(playerListener)
            current.release()
        }
        playerInstance = null
        _isPlaying.value = false
        progressTickerJob?.cancel()
        snapshotSaverJob?.cancel()
        if (_currentTrack.value != null && _uiState.value.phase !in listOf(PlaybackPhase.ERROR, PlaybackPhase.CHOOSE_VERSION)) {
            _uiState.value = _uiState.value.copy(phase = PlaybackPhase.PAUSED, message = null)
        }
    }
}
