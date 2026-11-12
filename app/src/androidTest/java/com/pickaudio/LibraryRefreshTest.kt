package com.pickaudio

import android.net.Uri
import android.Manifest
import android.content.ContentValues
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.*
import com.pickaudio.data.repository.LibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryRefreshTest {
    @Test fun deletedMediaStoreRowIsRemovedButExistingRowIsKept() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_AUDIO)
        val db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
        val uris = mutableListOf<Uri>()
        try {
            for (id in listOf("deleted", "exists")) {
                val uri = context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "refresh-${UUID.randomUUID()}.mp3")
                        put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/PickAudio")
                    })!!
                uris.add(uri)
                context.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1, 2, 3)) }
                db.trackDao().insertOrUpdate(TrackEntity(id, id, "", "", 60000, null, isInLibrary = true))
                db.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = id, uri = uri.toString(), sourceType = "MEDIA_STORE",
                    fileSize = 3, mimeType = "audio/mpeg", format = "mp3"))
                if (id == "deleted") {
                    assertEquals(1, context.contentResolver.delete(uri, null, null))
                    uris.remove(uri) // A second delete can throw SecurityException on OEM providers.
                }
            }
            assertEquals(1, LibraryRepository(context, db).refreshLibrary())
            assertEquals(listOf("exists"), db.trackDao().getLibraryTracks().first().map { it.id })
        } finally { uris.forEach { context.contentResolver.delete(it, null, null) }; db.close() }
    }

    @Test fun refreshRemovesOnlyConfirmedMissingLocalMembershipAndIsIdempotent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
        val folder = File(context.cacheDir, "refresh-${UUID.randomUUID()}").apply { mkdirs() }
        val existing = File(folder, "exists.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            suspend fun track(id: String, uri: String, type: String = "MEDIA_STORE") {
                db.trackDao().insertOrUpdate(TrackEntity(id, id, "artist", "album", 226000, null, isInLibrary = true))
                db.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = id, uri = uri, sourceType = type,
                    fileSize = 3, mimeType = "audio/mpeg", format = "mp3"))
            }
            track("deleted", Uri.fromFile(File(folder, "deleted.mp3")).toString())
            track("exists", Uri.fromFile(existing).toString())
            track("offline", Uri.fromFile(File(folder, "unmounted/file.mp3")).toString())
            track("permission", "content://com.example.provider/audio/1", "SAF_FILE")
            track("backup", "missing://backup/file", "BACKUP_HINT")
            track("online", Uri.fromFile(File(folder, "online-deleted.mp3")).toString(), "DOWNLOADED")
            db.onlineRefDao().insertOrUpdate(OnlineRefEntity(trackId = "online", platform = "tx", platformSongId = "mid", platformMetadataJson = "{}"))
            db.favoriteDao().addFavorite(FavoriteEntity("deleted"))
            val repository = LibraryRepository(context, db)
            assertEquals(1, repository.refreshLibrary())
            assertEquals(setOf("exists", "offline", "permission", "backup", "online"), db.trackDao().getLibraryTracks().first().map { it.id }.toSet())
            assertNotNull(db.trackDao().getTrackById("deleted"))
            assertTrue(db.favoriteDao().isFavoriteSync("deleted"))
            assertTrue(existing.exists())
            assertEquals(0, repository.refreshLibrary())
            assertFalse(repository.refreshState.value.running)
        } finally {
            existing.delete(); folder.delete(); db.close()
        }
    }
}
