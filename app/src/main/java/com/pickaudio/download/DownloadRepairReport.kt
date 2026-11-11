package com.pickaudio.download

data class DownloadRepairFailure(val taskId: String, val title: String, val message: String)

data class DownloadRepairReport(
    val checked: Int = 0,
    val requeued: Int = 0,
    val skipped: Int = 0,
    val errors: List<DownloadRepairFailure> = emptyList()
)
