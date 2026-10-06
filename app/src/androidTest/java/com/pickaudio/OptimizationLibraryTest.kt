package com.pickaudio

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.*
import com.pickaudio.data.model.AudioInfo
import com.pickaudio.data.repository.*
import com.pickaudio.download.AndroidAudioProbe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class OptimizationLibraryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PickAudioDatabase
    private val files = mutableListOf<File>()
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build() }
    @After fun cleanup() { files.forEach { it.delete() }; db.close() }
    private fun audio(bits: Int = 16, truncated: Boolean = false): File {
        val length = 44 + 44100 * (bits / 8)
        val bytes = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(length - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(44100); putInt(44100 * bits / 8); putShort((bits / 8).toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(length - 44)
        }.array()
        return File.createTempFile("optimization-probe-", ".wav", context.cacheDir).also {
            it.writeBytes(if (truncated) bytes.copyOf(1000) else bytes); files.add(it)
        }
    }
    @Test fun actual16And24bitPcmAreDetectedAndTruncatedContentIsRejected() = runBlocking {
        val sixteen = AndroidAudioProbe.inspect(context, Uri.fromFile(audio()), validatePackets = true)
        val twentyFour = AndroidAudioProbe.inspect(context, Uri.fromFile(audio(24)), validatePackets = true)
        assertEquals("PCM", sixteen.info.codec); assertEquals(16, sixteen.info.bitDepth)
        assertEquals(24, twentyFour.info.bitDepth)
        assertTrue(runCatching { AndroidAudioProbe.inspect(context, Uri.fromFile(audio(truncated = true)), true) }.isFailure)
    }
    @Test fun relinkKeepsStableIdAndExplicitTransferPreservesBothSongsAssociations() = runBlocking {
        val repository = LibraryRepository(context, db)
        val uri = Uri.fromFile(audio())
        assertTrue(repository.importSafFile(uri))
        val original = db.trackDao().getAllTracks().first().single()
        db.trackDao().insertOrUpdate(TrackEntity("restored_id", "恢复歌曲", "", "", 123, null))
        db.favoriteDao().addFavorite(FavoriteEntity("restored_id", 321))
        db.playlistDao().insertOrUpdate(PlaylistEntity("restore_list", "恢复歌单"))
        db.playlistDao().addTrackToPlaylist(PlaylistTrackEntity("restore_list", "restored_id", 3))
        db.queueDao().insertQueueEntries(listOf(QueueEntryEntity(51, "restored_id", 0), QueueEntryEntity(52, original.id, 1)))
        assertTrue(runCatching { repository.relinkTrack("restored_id", uri) }.exceptionOrNull() is RelinkConflictException)
        assertEquals("restored_id", repository.relinkTrack("restored_id", uri, true))
        assertNotNull(db.trackDao().getTrackById(original.id))
        assertNotNull(repository.getTrack(original.id)!!.repairReason)
        assertTrue(db.favoriteDao().isFavoriteSync("restored_id"))
        assertEquals("restored_id", db.playlistDao().getMembersSync("restore_list").single().trackId)
        assertEquals(listOf(51L, 52L), db.queueDao().getEntriesSync().map { it.id })
        assertEquals("restored_id", db.localAssetDao().getAssetByUri(uri.toString())!!.trackId)
    }
    @Test fun failedImportDoesNotCreateOrphanTrackAndRepairStatusSurvivesNewRepository() = runBlocking {
        val file = File.createTempFile("optimization-broken-", ".wav", context.cacheDir).also { it.writeText("not audio"); files.add(it) }
        val repository = LibraryRepository(context, db)
        assertFalse(repository.importSafFile(Uri.fromFile(file)))
        assertEquals(0, db.trackDao().getTrackCount())
        val good = audio()
        assertTrue(repository.importSafFile(Uri.fromFile(good)))
        val id = db.trackDao().getAllTracks().first().single().id
        good.delete()
        repository.refreshAvailability()
        assertNotNull(LibraryRepository(context, db).getTrack(id)!!.repairReason)
        assertFalse(LibraryRepository(context, db).getTrack(id)!!.isAvailable)
    }
    @Test fun directoryEnumerationIsOneQueryAndSiblingLyricsUseTheSameIndex() = runBlocking {
        val authority = "com.pickaudio.test.optimization.documents"
        val base = Uri.parse("content://$authority")
        try {
            context.contentResolver.call(base, "seed", null, Bundle().apply { putInt("songs", 100) })
            val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
            val repository = LibraryRepository(context, db)
            assertEquals(100, repository.importSafDirectory(tree))
            assertEquals(100, db.trackDao().getTrackCount())
            assertEquals(100, db.lyricDao().getAllLyrics().size)
            assertEquals(1, context.contentResolver.call(base, "stats", null, null)!!.getInt("childQueries"))
            assertTrue(db.localAssetDao().getAllAssets().first().all { it.fileSize > 0 && it.folderId.isNotEmpty() && AudioInfo.decode(it.audioInfoJson)?.codec == "PCM" })
        } finally {
            context.contentResolver.call(base, "clear", null, null)
        }
    }
}
