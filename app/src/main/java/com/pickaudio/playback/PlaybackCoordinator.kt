package com.pickaudio.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pickaudio.data.db.OnlineRefEntity
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.db.PlaybackSnapshotEntity
import com.pickaudio.data.db.QueueEntryEntity
import com.pickaudio.data.db.TrackEntity
import com.pickaudio.data.model.PlaybackMode
import com.pickaudio.data.model.Track
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

class PlaybackCoordinator(
    private val context: Context,
    private val database: PickAudioDatabase,
    private val sourceManager: LxSourceManager
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

    private var hasAttemptedFallback = false

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
    var stopAfterCurrentTrack = false

    // Shuffle history and round tracking
    private val shuffleHistory = Stack<Int>()
    private val shufflePool = mutableListOf<Int>()

    // Periodic progress saver
    private var progressTickerJob: Job? = null
    private var snapshotSaverJob: Job? = null
    private var consecutiveFailures = 0

    init {
        restoreSnapshot()
        startProgressTicker()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
            saveSnapshot()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                _durationMs.value = player.duration.coerceAtLeast(0L)
            } else if (state == Player.STATE_ENDED) {
                handleTrackEnded()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e("PlaybackCoordinator", "ExoPlayer playback error: ${error.errorCodeName}", error)
            val cur = _currentTrack.value
            if (!hasAttemptedFallback && cur != null) {
                hasAttemptedFallback = true
                scope.launch {
                    val fallbackUri = tryFallbackUri(cur)
                    if (fallbackUri != null) {
                        Log.i("PlaybackCoordinator", "Retrying playback with fallback stream: $fallbackUri")
                        try {
                            val mediaItem = MediaItem.Builder()
                                .setUri(fallbackUri)
                                .setMediaId(cur.id)
                                .build()
                            player.setMediaItem(mediaItem)
                            player.prepare()
                            player.play()
                            return@launch
                        } catch (e: Exception) {
                            Log.w("PlaybackCoordinator", "Fallback retry failed: ${e.message}")
                        }
                    }
                    handlePlaybackFailure(error)
                }
                return
            }
            handlePlaybackFailure(error)
        }

        private fun handlePlaybackFailure(error: androidx.media3.common.PlaybackException) {
            _isPlaying.value = false
            consecutiveFailures++
            scope.launch(Dispatchers.Main) {
                Toast.makeText(context, "播放出错: ${error.message ?: "网络或音频解码异常"}", Toast.LENGTH_SHORT).show()
            }
            val q = _queue.value
            if (q.size > 1 && consecutiveFailures < 3) {
                scope.launch {
                    delay(1000)
                    next()
                }
            } else {
                player.stop()
            }
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
        player.repeatMode = if (mode == PlaybackMode.SINGLE_LOOP) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
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
        if (tracks.isEmpty()) return
        _queue.value = tracks
        val safeIndex = startIndex.coerceIn(0, tracks.size - 1)
        _currentIndex.value = safeIndex
        shuffleHistory.clear()
        resetShufflePool()
        playTrack(tracks[safeIndex])

        scope.launch(Dispatchers.IO) {
            try {
                ensureTracksPersisted(tracks)
                queueDao.clearQueue()
                queueDao.insertQueueEntries(tracks.mapIndexed { idx, t ->
                    QueueEntryEntity(trackId = t.id, queueOrder = idx)
                })
                saveSnapshot()
            } catch (e: Exception) {
                Log.e("PlaybackCoordinator", "Error setting queue in DB", e)
            }
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
        if (index !in currentList.indices) return
        val cur = _currentIndex.value
        currentList.removeAt(index)
        _queue.value = currentList

        if (currentList.isEmpty()) {
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
        player.stop()
        _queue.value = emptyList()
        _currentIndex.value = -1
        _currentTrack.value = null
        shuffleHistory.clear()
        scope.launch(Dispatchers.IO) {
            try {
                queueDao.clearQueue()
                saveSnapshot()
            } catch (e: Exception) {
                Log.w("PlaybackCoordinator", "clearQueue db error", e)
            }
        }
    }

    fun playOrPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (_currentTrack.value == null && _queue.value.isNotEmpty()) {
                val idx = if (_currentIndex.value in _queue.value.indices) _currentIndex.value else 0
                _currentIndex.value = idx
                playTrack(_queue.value[idx])
            } else {
                player.play()
            }
        }
        saveSnapshot()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _currentPositionMs.value = positionMs
        saveSnapshot()
    }

    fun previous() {
        // Smart Previous Rule: if progress > 3s, seek to 0. Else jump previous.
        if (player.currentPosition > 3000L) {
            player.seekTo(0L)
            _currentPositionMs.value = 0L
            return
        }

        val q = _queue.value
        if (q.isEmpty()) return

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

    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return
        val cur = _currentIndex.value

        val nextIndex = when (_playbackMode.value) {
            PlaybackMode.SHUFFLE -> getNextShuffleIndex()
            else -> if (cur + 1 < q.size) cur + 1 else 0
        }

        if (nextIndex in q.indices) {
            if (cur in q.indices) shuffleHistory.push(cur)
            _currentIndex.value = nextIndex
            playTrack(q[nextIndex])
        }
    }

    private fun handleTrackEnded() {
        if (stopAfterCurrentTrack) {
            stopAfterCurrentTrack = false
            player.pause()
            return
        }

        val q = _queue.value
        if (q.isEmpty()) return
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
                    playTrack(q[cur + 1])
                } else {
                    // Sequential mode stops at the end of queue
                    player.pause()
                }
            }
            PlaybackMode.LIST_LOOP -> {
                val nextIdx = (cur + 1) % q.size
                _currentIndex.value = nextIdx
                playTrack(q[nextIdx])
            }
            PlaybackMode.SHUFFLE -> {
                val nextIdx = getNextShuffleIndex()
                if (nextIdx in q.indices) {
                    if (cur in q.indices) shuffleHistory.push(cur)
                    _currentIndex.value = nextIdx
                    playTrack(q[nextIdx])
                }
            }
        }
    }

    private fun getNextShuffleIndex(): Int {
        val q = _queue.value
        if (q.isEmpty()) return -1
        if (q.size == 1) return 0

        if (shufflePool.isEmpty()) {
            resetShufflePool()
        }
        return if (shufflePool.isNotEmpty()) shufflePool.removeAt(0) else 0
    }

    private fun playTrack(track: Track, isBackTracking: Boolean = false) {
        _currentTrack.value = track
        _currentPositionMs.value = 0L
        hasAttemptedFallback = false

        scope.launch {
            try {
                val mediaUri = resolveTrackMediaUri(track)
                val mediaItem = MediaItem.Builder()
                    .setUri(mediaUri)
                    .setMediaId(track.id)
                    .build()

                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
                consecutiveFailures = 0
            } catch (e: Exception) {
                Log.e("PlaybackCoordinator", "Failed to play track: ${track.title}", e)
                consecutiveFailures++
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "播放失败: 无法获取音频播放地址 (${track.title})", Toast.LENGTH_SHORT).show()
                }
                val q = _queue.value
                if (q.size > 1 && consecutiveFailures < 3) {
                    delay(1000)
                    next()
                } else {
                    _isPlaying.value = false
                    player.stop()
                }
            }
        }
        saveSnapshot()
    }

    private suspend fun tryFallbackUri(track: Track): Uri? = withContext(Dispatchers.IO) {
        val platform = track.platform
            ?: database.onlineRefDao().getByTrackId(track.id)?.platform
            ?: if (track.id.startsWith("online_wy_")) "wy" else if (track.id.startsWith("online_tx_")) "tx" else null
        val songId = track.platformSongId
            ?: database.onlineRefDao().getByTrackId(track.id)?.platformSongId
            ?: track.id.removePrefix("online_wy_").removePrefix("online_tx_")

        if (platform == "wy" && songId.isNotBlank()) {
            return@withContext Uri.parse("https://music.163.com/song/media/outer/url?id=$songId.mp3")
        }
        if (platform == "tx" && songId.isNotBlank()) {
            try {
                val fallbackUrl = sourceManager.resolveMusicUrl("tx", songId, "128k", track.title, track.artist)
                return@withContext Uri.parse(fallbackUrl)
            } catch (e: Exception) {
                Log.w("PlaybackCoordinator", "Failed to resolve fallback for tx: ${e.message}")
            }
        }
        null
    }

    private suspend fun resolveTrackMediaUri(track: Track): Uri = withContext(Dispatchers.IO) {
        // 1. Check local asset
        val assets = localAssetDao.getAssetsForTrack(track.id)
        val validLocal = assets.firstOrNull { it.isAvailable }
        if (validLocal != null) {
            return@withContext Uri.parse(validLocal.uri)
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
                val url = sourceManager.resolveMusicUrl(platform, songId, "320k", track.title, track.artist)
                return@withContext Uri.parse(url)
            } catch (e: Exception) {
                Log.w("PlaybackCoordinator", "Failed to resolve 320k, falling back to 128k: ${e.message}")
                try {
                    val url128 = sourceManager.resolveMusicUrl(platform, songId, "128k", track.title, track.artist)
                    return@withContext Uri.parse(url128)
                } catch (e2: Exception) {
                    if (platform == "wy") {
                        return@withContext Uri.parse("https://music.163.com/song/media/outer/url?id=$songId.mp3")
                    }
                    throw e2
                }
            }
        }

        // Fallback to track localUri if present
        if (!track.localUri.isNullOrEmpty()) {
            return@withContext Uri.parse(track.localUri)
        }

        throw IllegalStateException("无法解析播放地址: ${track.title}")
    }

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
        scope.launch(Dispatchers.IO) {
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
                                val mediaUri = resolveTrackMediaUri(restoredTracks[idx])
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
