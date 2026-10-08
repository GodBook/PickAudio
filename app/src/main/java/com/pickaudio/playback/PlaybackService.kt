package com.pickaudio.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.pickaudio.PickAudioApplication
import com.pickaudio.MainActivity
import com.pickaudio.R
import com.pickaudio.data.model.PlaybackPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private var sessionPlayer: QueueSessionPlayer? = null
    private lateinit var openPlayer: PendingIntent

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val app = application as? PickAudioApplication
                app?.playbackCoordinator?.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as PickAudioApplication
        val player = QueueSessionPlayer(app.playbackCoordinator).also { sessionPlayer = it }

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW))
        val provider = DefaultMediaNotificationProvider.Builder(this).setNotificationId(NOTIFICATION_ID)
            .setChannelId(CHANNEL_ID).setChannelName(R.string.playback_channel).build()
        provider.setSmallIcon(R.drawable.ic_notification_music)
        setMediaNotificationProvider(provider)
        openPlayer = PendingIntent.getActivity(this, 2041, Intent(this, MainActivity::class.java).putExtra("open_player", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(openPlayer).build().also(::addSession)
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                app.playbackCoordinator.onPlaybackServiceStartFailed(
                    ForegroundServiceStartNotAllowedException("Media notification could not enter foreground"))
            }
        })
        serviceScope.launch {
            combine(app.playbackCoordinator.playRequested, app.playbackCoordinator.uiState) { requested, state ->
                requested && state.phase in listOf(PlaybackPhase.RESOLVING, PlaybackPhase.BUFFERING, PlaybackPhase.PLAYING, PlaybackPhase.READY)
            }.distinctUntilChanged().collect { active ->
                // Only clean up an idle preparation notice here. Media3 owns the foreground state
                // for playable sessions, including buffering and temporary audio-focus suppression.
                if (!active && sessionPlayer?.playbackState == Player.STATE_IDLE) leaveForeground()
            }
        }

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        try {
            ContextCompat.registerReceiver(
                this,
                becomingNoisyReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.w("PlaybackService", "Failed to register becomingNoisyReceiver: ${e.message}")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A Media3 self-start can arrive after a pause has already removed foreground status.
        // Every foreground start must meet the platform deadline, including these queued intents.
        val coordinator = (application as PickAudioApplication).playbackCoordinator
        try {
            enterForegroundForStart()
            if (intent?.action == ACTION_START_PLAYBACK) coordinator.onPlaybackServiceReady()
        } catch (e: ForegroundServiceStartNotAllowedException) {
            coordinator.onPlaybackServiceStartFailed(e)
            stopSelfResult(startId)
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            coordinator.onPlaybackServiceStartFailed(e)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val result = super.onStartCommand(intent, flags, startId)
        if (!coordinator.playRequested.value) leaveForeground()
        return result
    }

    private fun enterForegroundForStart() {
        // Reuse existing media controls. Calling Media3's notification update here would self-start
        // the service recursively; direct platform promotion does not schedule another start.
        val existing = getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == NOTIFICATION_ID }?.notification
        val notification = existing ?: NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_music).setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.playback_preparing)).setContentIntent(openPlayer)
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_TRANSPORT).build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val coordinator = (application as PickAudioApplication).playbackCoordinator
        // The bootstrap notification is foreground before Media3's controller finishes connecting.
        if (!coordinator.playRequested.value) super.onTaskRemoved(rootIntent)
    }

    private fun leaveForeground() {
        // An idle/cancelled resolution has no playable session to attach to the preparation notice.
        stopForeground(if (sessionPlayer?.playbackState == Player.STATE_IDLE) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            unregisterReceiver(becomingNoisyReceiver)
        } catch (e: Exception) {
            // ignore
        }
        mediaSession?.run {
            release()
            mediaSession = null
        }
        sessionPlayer?.close()
        sessionPlayer = null
        (application as PickAudioApplication).playbackCoordinator.onServiceDestroyed()
        super.onDestroy()
    }
    companion object {
        internal const val ACTION_START_PLAYBACK = "com.pickaudio.action.START_PLAYBACK"
        private const val CHANNEL_ID = "pickaudio_playback"
        private const val NOTIFICATION_ID = 2041
    }
}
