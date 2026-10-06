package com.pickaudio.playback

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var requestGeneration = 0L
    private var restoringJob: Job? = null
    private val queueWriteMutex = Mutex()
    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState = _uiState.asStateFlow()
    private val _versionCandidates = MutableStateFlow<List<SearchSongItem>>(emptyList())
    val versionCandidates = _versionCandidates.asStateFlow()
    private var clearedQueue: Triple<List<Track>, Int, Long>? = null

    val player: ExoPlayer by lazy {
        val dataSourceFactory = AudioCacheManager.createDataSourceFactory(context)
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

        ExoPlayer.Builder(context)
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
            player.repeatMode = if (!value && _playbackMode.value == PlaybackMode.SINGLE_LOOP) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }

    // Shuffle history and round tracking
    private val shuffleHistory = Stack<Int>()
    private val shufflePool = mutableListOf<Int>()

    // Periodic progress saver
    private var progressTickerJob: Job? = null
    private var snapshotSaverJob: Job? = null
    private var consecutiveFailures = 0
    private var automaticTrack = false
    private var skipJob: Job? = null

    init {
        restoreSnapshot()
        startProgressTicker()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
            if (playing) _currentTrack.value?.let { track -> scope.launch { userPreferences.recordPlayed(track.id) } }
            if (_uiState.value.phase !in listOf(PlaybackPhase.ERROR, PlaybackPhase.CHOOSE_VERSION, PlaybackPhase.RESOLVING)) {
                _uiState.value = _uiState.value.copy(phase = if (playing) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED)
            }
            saveSnapshot()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                _durationMs.value = player.duration.coerceAtLeast(0L)
                consecutiveFailures = 0
                val format = player.audioFormat
                val mime = format?.sampleMimeType.orEmpty()
                val bitrate = format?.averageBitrate?.takeIf { it > 0 }
                val label = when {
                    mime.contains("flac") -> "FLAC"
                    mime == "audio/raw" -> "PCM"
                    mime == "audio/mpeg" -> "MP3" + (bitrate?.let { " · ${it / 1000} kbps" } ?: "")
                    mime == "audio/mp4a-latm" -> "AAC" + (bitrate?.let { " · ${it / 1000} kbps" } ?: "")
                    mime.isNotBlank() -> mime.substringAfter('/').removePrefix("x-").uppercase() + (bitrate?.let { " · ${it / 1000} kbps" } ?: "")
                    else -> "音频信息读取中"
                }
                _uiState.value = _uiState.value.copy(phase = if (player.playWhenReady) PlaybackPhase.PLAYING else PlaybackPhase.READY, actualQuality = label)
                if (_uiState.value.requestedQuality?.startsWith("flac") == true && mime.isNotBlank() && !mime.contains("flac")) {
                    player.stop()
                    _uiState.value = _uiState.value.copy(phase = PlaybackPhase.ERROR, message = "音源返回有损音频，未达到所选无损音质，请更换音质或音源")
                }
            } else if (state == Player.STATE_BUFFERING && _uiState.value.phase != PlaybackPhase.RESOLVING) {
                _uiState.value = _uiState.value.copy(phase = PlaybackPhase.BUFFERING)
            } else if (state == Player.STATE_ENDED) {
                handleTrackEnded()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e("PlaybackCoordinator", "ExoPlayer playback error: ${error.errorCodeName}", error)
            _isPlaying.value = false
            consecutiveFailures++
            _uiState.value = _uiState.value.copy(phase = PlaybackPhase.ERROR, message = "无法播放这首歌，请检查网络、文件授权或切换音源后重试")
            skipFailedAutomaticTrack()
        }
    }

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                try {
                    if (_isPlaying.value) {
                        _currentPositionMs.value = player.currentPosition.coerceAtLeast(0L)
                        _durationMs.value = player.duration.coerceAtLeast(0L)
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
        player.repeatMode = if (mode == PlaybackMode.SINGLE_LOOP && !stopAfterCurrentTrack) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (mode == PlaybackMode.SHUFFLE) {
            resetShufflePool()
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

    private fun resetShufflePool() {
        shufflePool.clear()
        val cur = _currentIndex.value
        val list = _queue.value.indices.filter { it != cur }.shuffled().toMutableList()
        shufflePool.addAll(list)
    }

    private suspend fun ensureTracksPersisted(tracks: List<Track>) {
        for (t in tracks) {
            if (trackDao.getTrackById(t.id) == null) {
                trackDao.insertOrUpdate(
                    TrackEntity(
                        id = t.id,
                        title = t.title,
                        artist = t.artist,
                        album = t.album,
                        durationMs = t.durationMs,
                        coverUri = t.coverUri,
                        trackNumber = t.trackNumber
                    )
                )
            }
            val platform = t.platform ?: if (t.id.startsWith("online_wy_")) "wy" else if (t.id.startsWith("online_tx_")) "tx" else null
            val songId = t.platformSongId ?: t.id.removePrefix("online_wy_").removePrefix("online_tx_")
            if (platform != null && songId.isNotEmpty()) {
                if (database.onlineRefDao().getByTrackId(t.id) == null) {
                    database.onlineRefDao().insertOrUpdate(
                        OnlineRefEntity(
                            trackId = t.id,
                            platform = platform,
                            platformSongId = songId,
                            platformMetadataJson = "{}"
                        )
                    )
                }
            }
        }
    }

    fun setQueueAndPlay(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) { return }
        _queue.value = tracks
        val safeIndex = startIndex.coerceIn(0, tracks.size - 1)
        _currentIndex.value = safeIndex
        shuffleHistory.clear()
        resetShufflePool()
        playTrack(tracks[safeIndex])

        persistQueue()
    }

    private fun persistQueue() {
        val tracks = _queue.value.toList()
        scope.launch {
            queueWriteMutex.withLock {
                withContext(Dispatchers.IO) {
                    ensureTracksPersisted(tracks)
                    database.withTransaction {
                        queueDao.clearQueue()
                        queueDao.insertQueueEntries(tracks.mapIndexed { index, track -> QueueEntryEntity(trackId = track.id, queueOrder = index) })
                        if (tracks.isEmpty()) { playbackDao.clearSnapshot() }
                    }
                }
            }
            saveSnapshot()
        }
    }

    fun playNext(track: Track) {
        val currentList = _queue.value.toMutableList()
        val cur = _currentIndex.value
        if (cur >= 0 && cur < currentList.size) {
            currentList.add(cur + 1, track)
        } else {
            currentList.add(track)
        }
        _queue.value = currentList
        resetShufflePool()
        persistQueue()
        scope.launch(Dispatchers.IO) {
            try {
                ensureTracksPersisted(listOf(track))
                saveSnapshot()
            } catch (e: Exception) {
                Log.e("PlaybackCoordinator", "playNext db error", e)
            }
        }
    }

    fun addToQueue(track: Track) {
        val currentList = _queue.value.toMutableList()
        currentList.add(track)
        _queue.value = currentList
        resetShufflePool()
        persistQueue()
        scope.launch(Dispatchers.IO) {
            try {
                ensureTracksPersisted(listOf(track))
                saveSnapshot()
            } catch (e: Exception) {
                Log.e("PlaybackCoordinator", "addToQueue db error", e)
            }
        }
    }

    fun removeQueueItem(index: Int) {
        val currentList = _queue.value.toMutableList()
        if (index !in currentList.indices) { return }
        val cur = _currentIndex.value
        currentList.removeAt(index)
        _queue.value = currentList

        if (currentList.isEmpty()) {
            requestJob?.cancel()
            requestGeneration++
            player.stop()
            _currentTrack.value = null
            _currentIndex.value = -1
        } else if (index == cur) {
            val nextIndex = if (index < currentList.size) index else 0
            _currentIndex.value = nextIndex
            playTrack(currentList[nextIndex])
        } else if (index < cur) {
            _currentIndex.value = cur - 1
        }
        resetShufflePool()
        persistQueue()
        saveSnapshot()
    }

    fun removeTrackFromQueue(trackId: String) {
        val currentList = _queue.value
        val indices = currentList.mapIndexedNotNull { idx, t -> if (t.id == trackId) idx else null }.reversed()
        for (idx in indices) {
            removeQueueItem(idx)
        }
    }

    fun clearQueue() {
        clearedQueue = Triple(_queue.value.toList(), _currentIndex.value, _currentPositionMs.value)
        requestJob?.cancel()
        skipJob?.cancel()
        restoringJob?.cancel()
        requestGeneration++
        player.stop()
        _queue.value = emptyList()
        _currentIndex.value = -1
        _currentTrack.value = null
        shuffleHistory.clear()
        _uiState.value = PlaybackUiState()
        persistQueue()
    }

    fun undoClearQueue() {
        val saved = clearedQueue ?: return
        clearedQueue = null
        setQueueAndPlay(saved.first, saved.second)
        requestJob?.invokeOnCompletion { if (_currentTrack.value?.id == saved.first.getOrNull(saved.second)?.id) scope.launch { seekTo(saved.third) } }
    }

    fun moveQueueItem(from: Int, to: Int) {
        val list = _queue.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) { return }
        val current = _currentIndex.value
        list.add(to, list.removeAt(from))
        _queue.value = list
        _currentIndex.value = when {
            current == from -> to
            from < current && to >= current -> current - 1
            from > current && to <= current -> current + 1
            else -> current
        }
        shuffleHistory.clear()
        resetShufflePool()
        persistQueue()
    }

    fun retryCurrent() { _currentTrack.value?.let { playTrack(it) } }
    fun addOrPlayTrack(track: Track) {
        val index = _queue.value.indexOfFirst { it.id == track.id }
        if (index >= 0) { _currentIndex.value = index; playTrack(_queue.value[index]) }
        else if (_queue.value.isEmpty()) setQueueAndPlay(listOf(track))
        else {
            val list = _queue.value.toMutableList()
            val next = (_currentIndex.value + 1).coerceIn(0, list.size)
            list.add(next, track)
            _queue.value = list
            _currentIndex.value = next
            playTrack(track)
            persistQueue()
        }
    }
    fun dismissVersionCandidates() { _versionCandidates.value = emptyList() }
    fun confirmVersion(item: SearchSongItem) {
        replaceCurrentTrack(item.toTrack())
    }

    fun replaceCurrentTrack(track: Track) {
        val list = _queue.value.toMutableList()
        val index = _currentIndex.value
        if (index in list.indices) { list[index] = track } else { list.add(track) }
        _queue.value = list
        _currentIndex.value = if (index in list.indices) index else list.lastIndex
        _versionCandidates.value = emptyList()
        playTrack(track)
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
        if (_uiState.value.phase == PlaybackPhase.RESOLVING) {
            requestJob?.cancel(); requestGeneration++
            _uiState.value = _uiState.value.copy(phase = PlaybackPhase.PAUSED, message = "已取消加载，点击播放可重试")
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            if (_currentTrack.value == null && _queue.value.isNotEmpty()) {
                val idx = if (_currentIndex.value in _queue.value.indices) _currentIndex.value else 0
                _currentIndex.value = idx
                playTrack(_queue.value[idx])
            } else {
                if (player.playbackState == Player.STATE_IDLE) retryCurrent() else { player.play(); ensurePlaybackService() }
            }
        }
        saveSnapshot()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _currentPositionMs.value = positionMs
        saveSnapshot()
    }

    private fun ensurePlaybackService() {
        runCatching { androidx.core.content.ContextCompat.startForegroundService(context, android.content.Intent(context, PlaybackService::class.java)) }
            .onFailure { Log.w("PlaybackCoordinator", "无法启动后台播放服务", it) }
    }

    fun previous() {
        // Smart Previous Rule: if progress > 3s, seek to 0. Else jump previous.
        if (player.currentPosition > 3000L) {
            player.seekTo(0L)
            _currentPositionMs.value = 0L
            return
        }

        val q = _queue.value
        if (q.isEmpty()) { return }

        if (_playbackMode.value == PlaybackMode.SHUFFLE) {
            if (shuffleHistory.isNotEmpty()) {
                val prevIndex = shuffleHistory.pop()
                if (prevIndex in q.indices) {
                    _currentIndex.value = prevIndex
                    playTrack(q[prevIndex], isBackTracking = true)
                    return
                }
            }
        }

        val cur = _currentIndex.value
        val prevIndex = if (cur > 0) cur - 1 else q.size - 1
        _currentIndex.value = prevIndex
        playTrack(q[prevIndex])
    }

    fun next() = advanceNext(false)

    private fun advanceNext(automatic: Boolean) {
        val q = _queue.value
        if (q.isEmpty()) { return }
        val cur = _currentIndex.value

        val nextIndex = when (_playbackMode.value) {
            PlaybackMode.SHUFFLE -> getNextShuffleIndex()
            else -> if (cur + 1 < q.size) cur + 1 else 0
        }

        if (nextIndex in q.indices) {
            if (cur in q.indices) { shuffleHistory.push(cur) }
            _currentIndex.value = nextIndex
            playTrack(q[nextIndex], automatic = automatic)
        }
    }

    private fun handleTrackEnded() {
        if (stopAfterCurrentTrack) {
            stopAfterCurrentTrack = false
            player.pause()
            return
        }

        val q = _queue.value
        if (q.isEmpty()) { return }
        val cur = _currentIndex.value

        when (_playbackMode.value) {
            PlaybackMode.SINGLE_LOOP -> {
                // Natural end of track loops same track
                player.seekTo(0L)
                player.play()
            }
            PlaybackMode.SEQUENTIAL -> {
                if (cur + 1 < q.size) {
                    _currentIndex.value = cur + 1
                    playTrack(q[cur + 1], automatic = true)
                } else {
                    // Sequential mode stops at the end of queue
                    player.pause()
                }
            }
            PlaybackMode.LIST_LOOP -> {
                val nextIdx = (cur + 1) % q.size
                _currentIndex.value = nextIdx
                playTrack(q[nextIdx], automatic = true)
            }
            PlaybackMode.SHUFFLE -> {
                val nextIdx = getNextShuffleIndex()
                if (nextIdx in q.indices) {
                    if (cur in q.indices) { shuffleHistory.push(cur) }
                    _currentIndex.value = nextIdx
                    playTrack(q[nextIdx], automatic = true)
                }
            }
        }
    }

    private fun getNextShuffleIndex(): Int {
        val q = _queue.value
        if (q.isEmpty()) { return -1 }
        if (q.size == 1) { return 0 }

        if (shufflePool.isEmpty()) {
            resetShufflePool()
        }
        return if (shufflePool.isNotEmpty()) shufflePool.removeAt(0) else 0
    }

    private fun playTrack(track: Track, isBackTracking: Boolean = false, startPosition: Long = 0L, autoPlay: Boolean = true, automatic: Boolean = false) {
        restoringJob?.cancel()
        requestJob?.cancel()
        skipJob?.cancel()
        automaticTrack = automatic
        if (!automatic) { consecutiveFailures = 0 }
        val generation = ++requestGeneration
        player.stop()
        _currentTrack.value = track
        _currentPositionMs.value = startPosition
        _durationMs.value = track.durationMs
        _versionCandidates.value = emptyList()
        _uiState.value = PlaybackUiState(PlaybackPhase.RESOLVING)

        requestJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { ensureTracksPersisted(listOf(track)) }
                val mediaUri = resolveTrackMediaUri(track)
                ensureActive()
                if (generation != requestGeneration) { return@launch }
                val mediaItem = MediaItem.Builder()
                    .setUri(mediaUri)
                    .setMediaId(track.id)
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
                        .setArtworkUri(track.coverUri?.let { Uri.parse(it) }).build())
                    .build()

                player.setMediaItem(mediaItem)
                player.prepare()
                player.seekTo(startPosition)
                player.playWhenReady = autoPlay
                if (autoPlay) { ensurePlaybackService() }
                _uiState.value = _uiState.value.copy(phase = PlaybackPhase.BUFFERING)
            } catch (e: CancellationException) { throw e }
            catch (e: AlternativeVersionException) {
                if (generation == requestGeneration) {
                    _versionCandidates.value = e.candidates
                    _uiState.value = _uiState.value.copy(phase = PlaybackPhase.CHOOSE_VERSION, message = e.message)
                }
            } catch (e: Exception) {
                Log.e("PlaybackCoordinator", "Failed to play track: ${track.title}", e)
                if (generation == requestGeneration) {
                    consecutiveFailures++
                    _uiState.value = _uiState.value.copy(phase = PlaybackPhase.ERROR, message = e.message ?: "无法获取歌曲，请重试或更换音源")
                    _isPlaying.value = false
                    player.stop()
                    skipFailedAutomaticTrack()
                }
            }
        }
        saveSnapshot()
    }

    private fun skipFailedAutomaticTrack() {
        if (!automaticTrack || _queue.value.size < 2 || consecutiveFailures >= 3) { return }
        val generation = requestGeneration
        skipJob = scope.launch {
            delay(1000)
            if (generation == requestGeneration && _uiState.value.phase == PlaybackPhase.ERROR) { advanceNext(true) }
        }
    }

    private suspend fun resolveTrackMediaUri(track: Track): Uri = withContext(Dispatchers.IO) {
        // 1. Check local asset
        val assets = localAssetDao.getAssetsForTrack(track.id)
        for (asset in assets.filter { it.isAvailable }) {
            val uri = Uri.parse(asset.uri)
            if (isAccessible(uri)) { return@withContext uri }
            localAssetDao.updateAvailability(asset.id, false)
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
                val quality = userPreferences.defaultOnlineQuality.first().value
                _uiState.value = _uiState.value.copy(requestedQuality = quality)
                val url = sourceManager.resolveMusicUrl(platform, songId, quality, track.title, track.artist)
                return@withContext Uri.parse(url)
            } catch (e: Exception) { throw e }
        }

        // Fallback to track localUri if present
        if (!track.localUri.isNullOrEmpty() && isAccessible(Uri.parse(track.localUri))) {
            return@withContext Uri.parse(track.localUri)
        }

        throw IllegalStateException("音频文件已移动或访问权限失效，请重新关联文件")
    }

    private fun isAccessible(uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") java.io.File(uri.path.orEmpty()).isFile
        else context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    // Sleep Timer
    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerRemainingMs.value = null
            return
        }

        val totalMs = minutes * 60 * 1000L
        sleepTimerJob = scope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                _sleepTimerRemainingMs.value = remaining
                delay(1000)
                remaining -= 1000
            }
            _sleepTimerRemainingMs.value = null
            player.pause()
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingMs.value = null
        stopAfterCurrentTrack = false
    }

    private fun saveSnapshot() {
        val curTrack = _currentTrack.value ?: return
        val pos = _currentPositionMs.value.coerceAtLeast(0L)
        val mode = _playbackMode.value.name
        val historyJson = synchronized(shuffleHistory) {
            try {
                gson.toJson(shuffleHistory.toList())
            } catch (e: Exception) {
                null
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                playbackDao.saveSnapshot(
                    PlaybackSnapshotEntity(
                        id = 1,
                        currentTrackId = curTrack.id,
                        progressMs = pos,
                        playbackMode = mode,
                        shuffleHistoryJson = historyJson
                    )
                )
            } catch (e: Exception) {
                Log.w("PlaybackCoordinator", "Failed to save playback snapshot: ${e.message}")
            }
        }
    }

    private fun restoreSnapshot() {
        val generation = requestGeneration
        restoringJob = scope.launch(Dispatchers.IO) {
            val queueEntities = queueDao.getQueueTracksSync()
            if (queueEntities.isNotEmpty()) {
                val restoredTracks = queueEntities.map { entity ->
                    val isFav = favoriteDao.isFavoriteSync(entity.id)
                    val assets = localAssetDao.getAssetsForTrack(entity.id)
                    val localUri = assets.firstOrNull { it.isAvailable }?.uri
                    Track(
                        id = entity.id,
                        title = entity.title,
                        artist = entity.artist,
                        album = entity.album,
                        durationMs = entity.durationMs,
                        coverUri = entity.coverUri,
                        trackNumber = entity.trackNumber,
                        localUri = localUri,
                        isAvailable = localUri != null,
                        isFavorite = isFav
                    )
                }
                if (generation != requestGeneration) return@launch
                _queue.value = restoredTracks

                val snapshot = playbackDao.getSnapshot()
                if (snapshot != null) {
                    val mode = try { PlaybackMode.valueOf(snapshot.playbackMode) } catch (e: Exception) { PlaybackMode.SEQUENTIAL }
                    _playbackMode.value = mode

                    val idx = restoredTracks.indexOfFirst { it.id == snapshot.currentTrackId }
                    if (idx >= 0) {
                        _currentIndex.value = idx
                        _currentTrack.value = restoredTracks[idx]
                        _currentPositionMs.value = snapshot.progressMs

                        // Prepare player at restored position but keep paused
                        withContext(Dispatchers.Main) {
                            try {
                                if (generation != requestGeneration || restoredTracks[idx].localUri == null) return@withContext
                                val mediaUri = Uri.parse(restoredTracks[idx].localUri)
                                player.setMediaItem(MediaItem.fromUri(mediaUri))
                                player.prepare()
                                player.seekTo(snapshot.progressMs)
                                player.pause()
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                }
            }
        }
    }
}
