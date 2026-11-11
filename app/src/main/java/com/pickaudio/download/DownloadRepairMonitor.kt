package com.pickaudio.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadRepairState(
    val checking: Boolean = false,
    val report: DownloadRepairReport? = null,
    val message: String? = null
)

/** Foreground visits and playback discoveries share one bounded stream of scan requests. */
class DownloadRepairMonitor(
    scope: CoroutineScope,
    private val scan: suspend (force: Boolean) -> DownloadRepairReport,
    private val schedulePending: suspend () -> Boolean
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val forceRequested = AtomicBoolean()
    private val mutableState = MutableStateFlow(DownloadRepairState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            for (request in requests) {
                val force = forceRequested.getAndSet(false)
                mutableState.value = mutableState.value.copy(checking = true, message = null)
                try {
                    val report = scan(force)
                    val scheduled = schedulePending()
                    val message = when {
                        report.requeued > 0 -> "发现 ${report.requeued} 首不完整歌曲，已加入重新下载队列" +
                            if (scheduled) "" else "，返回应用后继续"
                        report.errors.isNotEmpty() -> "有 ${report.errors.size} 首歌曲暂时无法检查，请稍后重试"
                        force -> "完整性检查完成，未发现需要重新下载的歌曲"
                        else -> null
                    }
                    mutableState.value = DownloadRepairState(report = report, message = message)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    mutableState.value = DownloadRepairState(message = "暂时无法完成下载完整性检查，请稍后重试")
                }
            }
        }
    }

    fun request(force: Boolean = false) {
        if (force) forceRequested.set(true)
        requests.trySend(Unit)
    }
}
