package com.pickaudio

import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.download.AudioFormatProbe
import com.pickaudio.download.ResumePolicy
import com.pickaudio.online.VersionMatcher
import org.junit.Assert.*
import org.junit.Test

class AudioAndVersionTest {
    private fun song(title: String = "晴天", artist: String = "周杰伦", duration: Long = 270000) = SearchSongItem("wy", "1", title, artist, "叶惠美", duration)
    @Test fun differentVersionsAndArtistsAreRejected() {
        assertTrue(VersionMatcher.sameRecording("晴天", "周杰伦", 270100, song()))
        assertFalse(VersionMatcher.sameRecording("晴天 (Live)", "周杰伦", 270000, song()))
        assertFalse(VersionMatcher.sameRecording("晴天", "其他歌手", 270000, song()))
        assertFalse(VersionMatcher.sameRecording("晴天", "周杰伦", 200000, song()))
        assertFalse(VersionMatcher.sameRecording("晴天", "周杰伦", 0, song()))
    }
    @Test fun collaboratorsCanAppearInDifferentOrder() {
        assertTrue(VersionMatcher.sameRecording("歌曲", "歌手甲 / 歌手乙", 180000, song("歌曲", "歌手乙、歌手甲", 180200)))
    }
    @Test fun fakeAudioAndTruncatedFlacAreRejected() {
        listOf("<html>错误</html>", "{\"code\":403}", "not audio", "fLaC").forEach { assertNull(AudioFormatProbe.detect(it.toByteArray())) }
    }
    @Test fun lossyDownloadsCannotBeSavedAsFlac() {
        val mp3 = AudioFormatProbe.detect("ID3some metadata".toByteArray())!!
        assertEquals("mp3", mp3.extension)
        try { AudioFormatProbe.requireRequestedQuality("flac", mp3); fail("必须拒绝伪无损") } catch (_: IllegalArgumentException) {}
    }
    @Test fun flacBitDepthIsReadFromStreamInfo() {
        val header = ByteArray(42)
        "fLaC".toByteArray().copyInto(header)
        header[7] = 34
        header[21] = (15 shl 4).toByte()
        val format = AudioFormatProbe.detect(header)!!
        assertEquals(16, format.bitDepth)
        AudioFormatProbe.requireRequestedQuality("flac", format)
        try { AudioFormatProbe.requireRequestedQuality("flac24bit", format); fail("必须拒绝 16bit") } catch (_: IllegalArgumentException) {}
        header[20] = 1
        header[21] = (7 shl 4).toByte()
        assertEquals(24, AudioFormatProbe.detect(header)!!.bitDepth)
    }
    @Test fun actualContainerDeterminesExtension() {
        assertEquals("m4a", AudioFormatProbe.detect(byteArrayOf(0, 0, 0, 12) + "ftypM4A ".toByteArray())!!.extension)
        assertEquals("wav", AudioFormatProbe.detect("RIFF0000WAVE".toByteArray())!!.extension)
        assertEquals("ogg", AudioFormatProbe.detect("OggS".toByteArray())!!.extension)
    }
    @Test fun resumingRequiresTheSameCompleteResource() {
        assertTrue(ResumePolicy.canResume(500, "v1", 206, "bytes 500-999/1000", "v1"))
        assertFalse(ResumePolicy.canResume(500, "v1", 200, null, "v1"))
        assertFalse(ResumePolicy.canResume(500, "v1", 206, "bytes 500-999/1000", "v2"))
        assertFalse(ResumePolicy.canResume(500, null, 206, "bytes 500-999/1000", null))
        assertFalse(ResumePolicy.canResume(500, "v1", 206, "bytes 0-499/1000", "v1"))
        assertFalse(ResumePolicy.canResume(500, "v1", 206, "bytes 500-749/1000", "v1"))
    }
}
