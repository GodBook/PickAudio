package com.pickaudio

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.DownloadTaskEntity
import com.pickaudio.data.db.LocalAssetEntity
import com.pickaudio.data.db.OnlineRefEntity
import com.pickaudio.data.db.TrackEntity
import com.pickaudio.ui.screens.DownloadScreen
import com.pickaudio.ui.theme.PickAudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Uses only files and records created in the instrumentation target application's own sandbox. */
@RunWith(AndroidJUnit4::class)
class AutomaticDownloadRepairUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PickAudioApplication

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(20_000) {
        while (!condition()) delay(50)
    }

    @Test fun foregroundVisitDetectsOldShortDownloadAndExposesRepairWithoutRetryingFailedTasks() = runBlocking<Unit> {
        val monitor = app.downloadRepairMonitor
        await { !monitor.state.value.checking && monitor.state.value.report != null }
        app.sourceManager.ensureBuiltinSources()
        val selection = requireNotNull(app.database.sourceDao().getSelectionForPlatform("tx"))
        val wifiOnly = app.userPreferences.wifiOnlyDownload.first()
        val id = "automatic_repair_${UUID.randomUUID().toString().replace("-", "")}"
        val bytes = shortWave()
        var oldUri: Uri? = null
        var importedSourceId: String? = null
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            await { !monitor.state.value.checking }
            val source = app.sourceManager.importSourceFromCode("""
                lx.on(lx.EVENT_NAMES.request, function() {
                    return Promise.reject(new Error('自动修复界面回归：明确拒绝，不访问网络'));
                });
                lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{tx:{name:'自动修复界面回归',actions:['musicUrl'],qualitys:['128k']}}});
            """.trimIndent())
            importedSourceId = source.id
            app.sourceManager.selectSourceForPlatform("tx", source.id)
            app.userPreferences.setWifiOnlyDownload(false)

            val originalUri = createOwnedAudio(id, bytes)
            oldUri = originalUri
            val oldValue = originalUri.toString()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val ticket = """{"id":"${UUID.randomUUID()}","sha256":"$sha"}"""
            app.database.withTransaction {
                app.database.trackDao().insertOrUpdate(TrackEntity(id, "自动修复入口回归", "测试", "", 20_000, null))
                app.database.onlineRefDao().insertOrUpdate(OnlineRefEntity(trackId = id, platform = "tx", platformSongId = id,
                    platformMetadataJson = """{"songid":123,"songmid":"$id","strMediaMid":"fixture_media","interval":20}"""))
                app.database.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = id, uri = oldValue,
                    sourceType = "DOWNLOADED", fileSize = bytes.size.toLong(), mimeType = "audio/wav", format = "wav", fileHash = sha))
                // The old app accepted a self-consistent two-second clip as a completed song.
                app.database.downloadDao().insertOrUpdate(DownloadTaskEntity(id, id, "自动修复入口回归", "测试", "", null,
                    "tx", id, "128k", "COMPLETED", downloadedBytes = bytes.size.toLong(), totalBytes = bytes.size.toLong(),
                    targetUri = oldValue, durationMs = 2000, publishStage = "COMPLETED", publishToken = ticket))
            }

            // Exercise MainActivity.onResume, not a direct call to the monitor or scan method.
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            await { !monitor.state.value.checking && monitor.state.value.report?.requeued == 1 }
            val repaired = requireNotNull(app.database.downloadDao().getTaskById(id))
            assertTrue(repaired.status in setOf("PENDING", "RESOLVING", "DOWNLOADING", "VERIFYING", "PUBLISHING", "FAILED"))
            assertEquals("128k", repaired.targetQuality)
            assertEquals(20_000L, repaired.durationMs)
            assertNull(repaired.targetUri)
            val oldAsset = requireNotNull(app.database.localAssetDao().getAssetByUri(oldValue))
            assertFalse(oldAsset.isAvailable)
            assertTrue(oldAsset.unavailableReason.orEmpty().contains("不完整"))
            assertEquals(20_000L, app.database.trackDao().getTrackById(id)!!.durationMs)
            assertArrayEquals(bytes, app.contentResolver.openInputStream(originalUri)!!.use { it.readBytes() })

            compose.runOnIdle { compose.activity.setContent { PickAudioTheme { DownloadScreen(app.downloadCoordinator, {}) } } }
            compose.onNodeWithText("发现 1 首不完整歌曲", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("检查下载完整性").assertIsDisplayed().assertIsEnabled()

            // If UIDT is waiting for system scheduling, finish this task using its local-only
            // rejecting source. No remote HTTP request is needed to test retry idempotency.
            withTimeout(20_000) { withContext(Dispatchers.IO) { app.downloadCoordinator.runPending() } }
            await { app.database.downloadDao().getTaskById(id)?.status == "FAILED" }
            val failed = requireNotNull(app.database.downloadDao().getTaskById(id))
            assertTrue(failed.errorMessage.orEmpty().contains("明确拒绝"))
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            await { !monitor.state.value.checking && monitor.state.value.report?.requeued == 0 }
            val afterVisit = requireNotNull(app.database.downloadDao().getTaskById(id))
            assertEquals("FAILED", afterVisit.status)
            assertEquals(failed.executionGeneration, afterVisit.executionGeneration)
            assertEquals("128k", afterVisit.targetQuality)

            val beforeManual = monitor.state.value.report
            compose.onNodeWithContentDescription("检查下载完整性").performClick()
            await { !monitor.state.value.checking && monitor.state.value.report !== beforeManual }
            assertEquals(0, monitor.state.value.report!!.requeued)
            assertEquals(failed.executionGeneration, app.database.downloadDao().getTaskById(id)!!.executionGeneration)
            compose.onNodeWithText("完整性检查完成，未发现需要重新下载的歌曲").assertIsDisplayed()
        } finally {
            app.downloadCoordinator.cancelAndWait(id)
            await { !monitor.state.value.checking }
            app.database.downloadDao().deleteTask(id)
            app.database.trackDao().deleteById(id)
            oldUri?.let { app.contentResolver.delete(it, null, null) }
            app.database.sourceDao().setPlatformSelection(selection)
            importedSourceId?.let { app.sourceManager.deleteSource(it) }
            app.userPreferences.setWifiOnlyDownload(wifiOnly)
        }
    }

    private fun createOwnedAudio(id: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$id.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/PickAudio")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(app.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        try {
            requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
            app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            app.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun shortWave(): ByteArray = ByteBuffer.allocate(44 + 8000 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(capacity() - 44)
    }.array()
}
