package com.pickaudio.download

enum class DownloadCommand { PAUSE, RESUME, CANCEL }
data class DownloadControlReport(val changed: Int, val skipped: Int, val failures: List<String>, val scheduled: Boolean = true) {
    val summary: String get() = "已处理 $changed 项 · 跳过 $skipped 项 · 失败 ${failures.size} 项" +
        if (!scheduled) "；系统暂未安排下载，请稍后继续" else ""
}
