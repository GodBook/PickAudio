package com.pickaudio

import android.app.Application
import com.pickaudio.backup.BackupManager
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.data.repository.LibraryRepository
import com.pickaudio.data.repository.PlaylistRepository
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.playback.PlaybackCoordinator
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.*

class PickAudioApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, error -> android.util.Log.e("PickAudio", "Startup recovery failed", error) })
    val database: PickAudioDatabase by lazy { PickAudioDatabase.getInstance(this) }
    val userPreferences: UserPreferences by lazy { UserPreferences(this) }
    val sourceManager: LxSourceManager by lazy { LxSourceManager(this, database) }
    val playbackCoordinator: PlaybackCoordinator by lazy { PlaybackCoordinator(this, database, sourceManager) }
    val downloadCoordinator: DownloadCoordinator by lazy { DownloadCoordinator(this, database, sourceManager) }
    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(this, database,
            stopRelatedTasks = { id ->
                downloadCoordinator.cancelForTrackAndWait(id)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (playbackCoordinator.currentTrack.value?.id == id) playbackCoordinator.pause()
                }
            },
            beforeRecordDelete = { id ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { playbackCoordinator.removeTrackFromQueue(id) }
                playbackCoordinator.flushPersistence()
            },
            afterRelink = { ids ->
                val tracks = ids.mapNotNull { libraryRepository.getTrack(it) }
                withContext(Dispatchers.Main) { tracks.forEach { playbackCoordinator.refreshTrackMetadata(it) } }
            },
            beforeTrackOperation = { downloadCoordinator.suspendTrackOperations(it) },
            afterTrackOperation = { downloadCoordinator.releaseTrackOperations(it) })
    }
    val playlistRepository: PlaylistRepository by lazy { PlaylistRepository(database) }
    val backupManager: BackupManager by lazy { BackupManager(this, database) }
    val lyricRepository: com.pickaudio.data.repository.LyricRepository by lazy { com.pickaudio.data.repository.LyricRepository(this, database) }
    val appUpdateManager: com.pickaudio.update.AppUpdateManager by lazy { com.pickaudio.update.AppUpdateManager(this) }
    val searchStateManager: com.pickaudio.ui.screens.SearchStateManager by lazy { com.pickaudio.ui.screens.SearchStateManager() }

    override fun onCreate() {
        super.onCreate()
        // Eagerly initialize built-in music sources and selections
        sourceManager
        applicationScope.launch { backupManager.recoverPending() }
        applicationScope.launch { libraryRepository.recoverInterruptedDeletions() }
        applicationScope.launch {
            userPreferences.audioCacheMegabytes.collect { com.pickaudio.playback.AudioCacheManager.configureCapacity(this@PickAudioApplication, it) }
        }
    }
}
