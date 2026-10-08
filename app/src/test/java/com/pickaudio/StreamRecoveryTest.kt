package com.pickaudio

import androidx.media3.common.PlaybackException
import com.pickaudio.playback.isRecoverableHttpStatus
import com.pickaudio.playback.isRecoverableStreamError
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamRecoveryTest {
    @Test fun connectionFailuresAndTimeoutsCanRecover() {
        listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT).forEach {
            assertTrue(isRecoverableStreamError(PlaybackException("transport failure", null, it)))
        }
    }

    @Test fun expiredUrlsAndTransientHttpFailuresCanRecover() {
        listOf(401, 403, 408, 410, 429, 500, 502, 503, 504).forEach {
            assertTrue("HTTP $it", isRecoverableHttpStatus(it))
        }
    }

    @Test fun permanentHttpFailuresAndMissingStatusDoNotLoop() {
        listOf(400, 404, 405, 416, 501).forEach {
            assertFalse("HTTP $it", isRecoverableHttpStatus(it))
        }
        assertFalse(isRecoverableStreamError(PlaybackException("unknown status", null,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
    }

    @Test fun decodingFilesAndPermissionErrorsAreNotRetried() {
        listOf(PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED).forEach {
            assertFalse(isRecoverableStreamError(PlaybackException("permanent failure", null, it)))
        }
    }

}
