package com.pickaudio

import com.pickaudio.online.AudioCompleteness
import org.junit.Assert.*
import org.junit.Test

class AudioCompletenessTest {
    @Test fun catalogueDurationSurvivesAnOlderDownloadOverwritingTheTrackDuration() {
        assertEquals(226_000L, AudioCompleteness.expectedDurationMs("tx", """{"interval":226}""", 113_000))
        assertEquals(226_000L, AudioCompleteness.expectedDurationMs("wy", """{"dt":226000}""", 113_000))
        for (metadata in listOf("{}", "invalid", "null", """{"interval":null}""", """{"interval":-1}""",
                """{"interval":9223372036854775807}""")) {
            assertEquals(226_000L, AudioCompleteness.expectedDurationMs("tx", metadata, 226_000))
        }
    }

    @Test fun halfSongsAndThirtySecondPreviewsFailDespiteHavingValidAudioHeaders() {
        for (duration in listOf(30_000L, 113_000L, 220_000L)) {
            assertNotNull(AudioCompleteness.failure(226_000, duration))
            assertTrue(runCatching { AudioCompleteness.requireComplete(226_000, duration) }.isFailure)
        }
    }

    @Test fun encoderPaddingRoundingAndUnknownDurationsAreAllowed() {
        for (duration in listOf(226_905L, 225_000L, 222_000L)) {
            assertNull(AudioCompleteness.failure(226_000, duration))
        }
        assertNull(AudioCompleteness.failure(0, 30_000))
        assertNull(AudioCompleteness.failure(226_000, -1))
        assertNull(AudioCompleteness.failure(1500, 1499))
        assertNotNull(AudioCompleteness.failure(7_200_000, 7_180_000))
    }
}
