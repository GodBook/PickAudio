package com.pickaudio.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pickaudio.MainActivity
import com.pickaudio.PickAudioApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class DownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("downloads", "音乐下载", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 2040, Intent(this, MainActivity::class.java).putExtra("open_downloads", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("拾音正在下载音乐")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        setNotification(params, 2040, builder.setContentText("准备下载").build(), JOB_END_NOTIFICATION_POLICY_REMOVE)
        val coordinator = (application as PickAudioApplication).downloadCoordinator
        runner = scope.launch {
            val notificationJob = launch {
                coordinator.getAllTasks().collectLatest { tasks ->
                    val active = tasks.filter { it.status in DownloadCoordinator.ACTIVE_STATES }
                    manager.notify(2040, builder.setContentText(active.firstOrNull()?.title ?: "等待下一首")
                        .setSubText("${active.size} 首正在处理").build())
                }
            }
            try { withContext(Dispatchers.IO) { coordinator.runPending() } }
            finally {
                notificationJob.cancel()
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { runner?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
