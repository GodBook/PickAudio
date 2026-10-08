package com.pickaudio.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/** Retry transport failures and expired URLs, without retrying decoding or file-permission errors. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun isRecoverableStreamError(error: PlaybackException): Boolean = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
        val response = generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        isRecoverableHttpStatus(response?.responseCode)
    }
    else -> false
}

internal fun isRecoverableHttpStatus(code: Int?): Boolean = code in setOf(401, 403, 408, 410, 429, 500, 502, 503, 504)
