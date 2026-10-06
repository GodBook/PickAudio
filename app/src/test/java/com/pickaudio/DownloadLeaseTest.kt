package com.pickaudio

import com.pickaudio.data.db.DownloadTaskEntity
import com.pickaudio.download.DownloadLease
import com.pickaudio.download.PublicationTicket
import com.pickaudio.download.ResourceValidator
import org.junit.Assert.*
import org.junit.Test

class DownloadLeaseTest {
    private fun task() = DownloadTaskEntity("job", "song", "song", "", "", null, "wy", "1", "128k", "DOWNLOADING", executionGeneration = 7)
    @Test fun oldWorkerCannotWriteAfterPauseAndResume() {
        val running = task()
        assertTrue(DownloadLease.canWrite(running, 7))
        val paused = DownloadLease.invalidate(running, "PAUSED")
        assertFalse(DownloadLease.canWrite(paused, 7))
        val resumed = DownloadLease.invalidate(paused, "PENDING").copy(status = "RESOLVING")
        assertFalse(DownloadLease.canWrite(resumed, 7))
        assertTrue(DownloadLease.canWrite(resumed, resumed.executionGeneration))
    }
    @Test fun cancellationAndCompletionRejectTransferWrites() {
        assertFalse(DownloadLease.canWrite(DownloadLease.invalidate(task(), "CANCELLED"), 7))
        assertFalse(DownloadLease.canWrite(task().copy(status = "COMPLETED"), 7))
        assertFalse(DownloadLease.canWrite(null, 7))
    }
    @Test fun publicationTicketAndResourceValidatorRoundTripAndRejectBadData() {
        val ticket = PublicationTicket.create("1".repeat(64))
        assertEquals(ticket, PublicationTicket.decode(ticket.encode()))
        assertNull(PublicationTicket.decode("{\"id\":\"../../other\",\"sha256\":\"short\"}"))
        val resource = ResourceValidator("https://example.test/song?id=1", "\"etag\"")
        assertEquals(resource, ResourceValidator.decode(resource.encode()))
        assertNull(ResourceValidator.decode("\"legacy-etag\""))
    }
}
