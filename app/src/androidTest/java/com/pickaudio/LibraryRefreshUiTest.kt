package com.pickaudio

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.LocalAssetEntity
import com.pickaudio.data.db.TrackEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryRefreshUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun foregroundAndRefreshButtonBothRemoveMissingLibraryEntries() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PickAudioApplication
        val prefix = "refresh_ui_${UUID.randomUUID()}"
        val folder = File(app.cacheDir, prefix).apply { mkdirs() }
        val remaining = File(folder, "remaining.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val ids = mutableListOf<String>()
        suspend fun add(name: String, file: File): String {
            val id = "${prefix}_$name"
            ids.add(id)
            app.database.trackDao().insertOrUpdate(TrackEntity(id, name, "", "", 60000, null, isInLibrary = true))
            app.database.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = id, uri = Uri.fromFile(file).toString(),
                sourceType = "MEDIA_STORE", fileSize = 3, mimeType = "audio/mpeg", format = "mp3"))
            return id
        }
        suspend fun awaitRemoved(id: String) = withTimeout(15000) {
            while (app.database.trackDao().getTrackById(id)?.isInLibrary != false || app.libraryRepository.refreshState.value.running) delay(40)
        }
        try {
            compose.awaitIdle()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val kept = add("保留文件", remaining)
            val automatic = add("自动清理验证", File(folder, "automatic.mp3"))
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            awaitRemoved(automatic)
            compose.onNodeWithContentDescription("刷新曲库，清理已删除文件").assertIsDisplayed()
            val manual = add("手动清理验证", File(folder, "manual.mp3"))
            compose.onNodeWithContentDescription("刷新曲库，清理已删除文件").performClick()
            awaitRemoved(manual)
            assertTrue(app.database.trackDao().getTrackById(kept)!!.isInLibrary)
            assertTrue(remaining.exists())
            assertEquals(1, app.libraryRepository.refreshState.value.removed)
        } finally {
            ids.forEach { app.database.trackDao().deleteById(it) }
            remaining.delete(); folder.delete()
        }
    }
}
