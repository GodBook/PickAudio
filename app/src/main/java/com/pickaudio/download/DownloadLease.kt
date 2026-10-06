package com.pickaudio.download

import com.pickaudio.data.db.DownloadTaskEntity

internal object DownloadLease {
    val activeStates = setOf("RESOLVING", "DOWNLOADING", "VERIFYING", "PUBLISHING")
    fun canWrite(task: DownloadTaskEntity?, generation: Long): Boolean =
        task != null && task.executionGeneration == generation && task.status in activeStates

    fun invalidate(task: DownloadTaskEntity, status: String, message: String? = null): DownloadTaskEntity =
        task.copy(status = status, executionGeneration = task.executionGeneration + 1,
            errorMessage = message, bytesPerSecond = 0, etaSeconds = null, updatedAt = System.currentTimeMillis())
}
