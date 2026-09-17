package com.pickaudio.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.pickaudio.PickAudioApplication

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val app = application as? PickAudioApplication
                app?.playbackCoordinator?.player?.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as PickAudioApplication
        val player = app.playbackCoordinator.player

        mediaSession = MediaSession.Builder(this, player).build()

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

    override fun onDestroy() {
        try {
            unregisterReceiver(becomingNoisyReceiver)
        } catch (e: Exception) {
            // ignore
        }
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
