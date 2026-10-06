package com.pickaudio

import com.pickaudio.playback.audioCacheKey
import com.pickaudio.data.db.*
import com.pickaudio.data.repository.matchRelinkCandidates
import org.junit.Assert.*
import org.junit.Test

class CacheAndRepairTest {
    @Test fun snapshotIdentityRefreshKeepsNewerQueueMetadataAndOccurrences() {
        val old = com.pickaudio.data.model.Track("temporary", "旧标题", "", "", 1000)
        val queue = com.pickaudio.playback.PlaybackQueue().apply { replace(listOf(old, old)) }
        val occurrences = queue.entries.map { it.id }
        val updated = old.copy(title = "新标题", localUri = "file:///new.wav")
        queue.refreshTrack(updated)
        queue.canonicalize(mapOf(old.id to old.copy(id = "canonical")))
        assertEquals(occurrences, queue.entries.map { it.id })
        assertTrue(queue.entries.all { it.track.id == "canonical" && it.track.title == "新标题" && it.track.localUri == "file:///new.wav" })
    }
    @Test fun rotatingUrlsShareOnlyWithStrongValidatorAndSameMusicIdentity() {
        fun key(url: String, etag: String?, quality: String = "128k", source: String = "source", song: String = "1") =
            audioCacheKey(url, "wy", song, quality, source, etag)
        val a = "https://cdn.example/audio?token=a"
        val b = "https://cdn.example/audio?token=b"
        assertEquals(key(a, "\"same\""), key(b, "\"same\""))
        assertNotEquals(key(a, null), key(b, null))
        assertNotEquals(key(a, "W/\"same\""), key(b, "W/\"same\""))
        assertNotEquals(key(a, "\"same\""), key(b, "\"changed\""))
        assertNotEquals(key(a, "\"same\""), key(b, "\"same\"", quality = "flac"))
        assertNotEquals(key(a, "\"same\""), key(b, "\"same\"", source = "other"))
        assertNotEquals(key(a, "\"same\""), key(b, "\"same\"", song = "2"))
    }
    @Test fun sameNamedDifferentRecordingIsNotAnAutomaticRelinkCandidate() {
        val target = TrackEntity("target", "同名歌曲", "", "", 120000, null)
        val other = target.copy(id = "other", durationMs = 150000)
        val hint = LocalAssetEntity(trackId = "target", uri = "missing://hint", sourceType = "BACKUP_HINT", fileSize = 1000,
            mimeType = null, format = "mp3", fileName = "song.mp3", isAvailable = false)
        val file = hint.copy(trackId = "other", uri = "file:///song.mp3", isAvailable = true)
        assertTrue(matchRelinkCandidates(target, listOf(hint), listOf(file), mapOf("other" to other)).isEmpty())
        val close = other.copy(durationMs = 120001)
        assertEquals(1, matchRelinkCandidates(target, listOf(hint), listOf(file), mapOf("other" to close)).size)
    }
}
