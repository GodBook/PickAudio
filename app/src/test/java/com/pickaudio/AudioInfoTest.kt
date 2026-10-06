package com.pickaudio

import com.pickaudio.data.model.AudioInfo
import com.pickaudio.data.db.LocalAssetEntity
import com.pickaudio.data.repository.rankAssets
import com.pickaudio.download.AudioFormatProbe
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioInfoTest {
    private fun wav(code: Short, bits: Short = 16) = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(code); putShort(2); putInt(48000); putInt(192000); putShort(4); putShort(bits)
        put("data".toByteArray()); putInt(0)
    }.array()
    @Test fun wavCodecDeterminesLosslessnessRatherThanContainerName() {
        assertTrue(AudioFormatProbe.detect(wav(1))!!.lossless == true)
        assertEquals("PCM", AudioFormatProbe.detect(wav(1))!!.codec)
        assertFalse(AudioFormatProbe.detect(wav(17, 4))!!.lossless == true)
        assertEquals("ADPCM", AudioFormatProbe.detect(wav(17, 4))!!.codec)
        assertNull(AudioFormatProbe.detect("RIFF0000WAVE".toByteArray())!!.lossless)
    }
    @Test fun mp4NeedsCodecFactsAndAlacDoesNotBecomeLossy() {
        val header = AudioFormatProbe.detect(byteArrayOf(0, 0, 0, 12) + "ftypM4A ".toByteArray())!!
        assertNull(header.lossless)
        assertTrue(AudioInfo.fromMime("audio/alac", header, 24).lossless == true)
        assertFalse(AudioInfo.fromMime("audio/mp4a-latm", header).lossless == true)
    }
    @Test fun playbackAndDownloadUseSame24bitAndDowngradeRules() {
        val sixteen = AudioInfo("flac", "audio/flac", true, 16, codec = "FLAC")
        assertNotNull(sixteen.qualityFailure("flac24bit"))
        assertNull(sixteen.copy(bitDepth = 24).qualityFailure("flac24bit"))
        assertNotNull(sixteen.copy(bitDepth = null).qualityFailure("flac24bit"))
        val lower = AudioInfo("mp3", "audio/mpeg", false, bitrate = 128000, codec = "MP3")
        assertNotNull(lower.qualityNotice("320k"))
        assertNull(lower.qualityNotice("128k"))
        assertEquals(lower, AudioInfo.decode(lower.encode()))
    }
    @Test fun availableAssetsAreRankedByRequestedAndActualQuality() {
        fun asset(id: Long, bits: Int, available: Boolean = true) = LocalAssetEntity(id, "song", "file:///$id",
            "SAF_FILE", 123, "audio/flac", "flac", available,
            audioInfoJson = AudioInfo("flac", "audio/flac", true, bits, 48000, codec = "FLAC").encode())
        assertEquals(listOf(2L, 1L), rankAssets(listOf(asset(1, 16), asset(3, 32, false), asset(2, 24)), "flac24bit").map { it.id })
    }
}
