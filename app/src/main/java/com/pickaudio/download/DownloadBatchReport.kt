package com.pickaudio.download

import com.pickaudio.data.model.Track

data class DownloadBatchFailure(val track: Track, val reason: String)
data class DownloadBatchReport(val added: Int = 0, val resumed: Int = 0, val existing: Int = 0,
    val skipped: Int = 0, val failures: List<DownloadBatchFailure> = emptyList()) {
    val summary get() = "新增 $added · 继续 $resumed · 已有 $existing · 跳过 $skipped · 失败 ${failures.size}"
}
