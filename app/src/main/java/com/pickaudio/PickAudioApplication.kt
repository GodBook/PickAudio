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

class PickAudioApplication : Application() {
    val database: PickAudioDatabase by lazy { PickAudioDatabase.getInstance(this) }
    val userPreferences: UserPreferences by lazy { UserPreferences(this) }
    val sourceManager: LxSourceManager by lazy { LxSourceManager(this, database) }
    val playbackCoordinator: PlaybackCoordinator by lazy { PlaybackCoordinator(this, database, sourceManager) }
    val downloadCoordinator: DownloadCoordinator by lazy { DownloadCoordinator(this, database, sourceManager) }
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(this, database) }
    val playlistRepository: PlaylistRepository by lazy { PlaylistRepository(database) }
    val backupManager: BackupManager by lazy { BackupManager(this, database) }
    val appUpdateManager: com.pickaudio.update.AppUpdateManager by lazy { com.pickaudio.update.AppUpdateManager(this) }

    override fun onCreate() {
        super.onCreate()
    }
}
