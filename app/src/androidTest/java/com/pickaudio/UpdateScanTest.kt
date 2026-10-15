package com.pickaudio

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.repository.LibraryRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class UpdateScanTest {
    @Test fun filteredShortSongsStillFinishProgressAndCountAsSkipped() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
        val created = mutableListOf<Uri>()
        try {
            repeat(10) { index ->
                val seconds = if (index < 3) 5 else 35
                val size = 44 + 88200 * seconds
                val bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(size - 8); put("WAVEfmt ".toByteArray()); putInt(16)
                    putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
                    put("data".toByteArray()); putInt(size - 44)
                }.array()
                val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "pickaudio-scan-fixture-$index.wav")
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/PickAudio-Validation")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })!!
                created += uri
                context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            withTimeout(15000) {
                while (created.any { uri -> context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DURATION), null, null, null)!!.use {
                        !it.moveToFirst() || it.getLong(0) <= 0
                    } }) delay(100)
            }
            val library = LibraryRepository(context, db)
            library.startScan(true)
            withTimeout(15000) { while (library.importProgress.value.running) delay(30) }
            val result = library.importProgress.value
            assertEquals("导入完成", result.message)
            assertEquals(result.total, result.processed)
            assertTrue(result.shortSkipped >= 3)
            assertEquals(result.processed, result.imported + result.updated + result.skipped + result.failed)
        } finally {
            created.forEach { context.contentResolver.delete(it, null, null) }
            db.close()
        }
    }
}
