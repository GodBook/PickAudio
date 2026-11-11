package com.pickaudio

import com.pickaudio.download.DownloadRepairMonitor
import com.pickaudio.download.DownloadRepairReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRepairMonitorTest {
    @Test fun overlappingVisitsCoalesceWithoutLosingAForcedCheck() = runTest {
        val gate = CompletableDeferred<Unit>()
        val requests = mutableListOf<Boolean>()
        var scheduled = 0
        val monitor = DownloadRepairMonitor(backgroundScope, scan = { force ->
            requests.add(force)
            if (requests.size == 1) gate.await()
            DownloadRepairReport(checked = 1, requeued = 1, skipped = 0, errors = emptyList())
        }, schedulePending = { scheduled++; true })
        monitor.request()
        runCurrent()
        assertTrue(monitor.state.value.checking)
        monitor.request(force = true)
        repeat(10) { monitor.request() }
        runCurrent()
        assertEquals(listOf(false), requests)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(false, true), requests)
        assertEquals(2, scheduled)
        assertFalse(monitor.state.value.checking)
        assertTrue(monitor.state.value.message.orEmpty().contains("重新下载"))
    }

    @Test fun temporaryFailureDoesNotStopLaterAutomaticChecksOrScheduleFailedWork() = runTest {
        var attempts = 0
        var scheduled = 0
        val monitor = DownloadRepairMonitor(backgroundScope, scan = {
            if (++attempts == 1) error("temporary storage failure")
            DownloadRepairReport(checked = 1, requeued = 0, skipped = 0, errors = emptyList())
        }, schedulePending = { scheduled++; false })
        monitor.request()
        runCurrent()
        assertFalse(monitor.state.value.checking)
        assertNotNull(monitor.state.value.message)
        assertEquals(0, scheduled)
        monitor.request(force = true)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, scheduled)
        assertNotNull(monitor.state.value.report)
        assertTrue(monitor.state.value.message.orEmpty().contains("检查完成"))
    }
}
