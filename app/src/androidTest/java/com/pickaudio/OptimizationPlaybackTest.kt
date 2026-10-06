package com.pickaudio

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.*
import com.pickaudio.data.model.PlaybackMode
import com.pickaudio.data.model.Track
import com.pickaudio.playback.PlaybackCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class OptimizationPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as PickAudioApplication
    @get:Rule val migrations = MigrationTestHelper(instrumentation, PickAudioDatabase::class.java)
    private val keepFixture get() = InstrumentationRegistry.getArguments().getString("keepPlaybackFixture") == "true"
    @After fun clearRetainedNotificationFixture() = runBlocking {
        if (!keepFixture) {
            val leftovers = app.database.trackDao().getAllTracks().first().filter { it.id.startsWith("optimization_media_") }
            if (leftovers.isNotEmpty()) {
                withContext(Dispatchers.Main) { app.playbackCoordinator.clearQueue() }
                app.playbackCoordinator.flushPersistence()
                leftovers.forEach { track ->
                    app.database.localAssetDao().getAssetsForTrack(track.id).forEach { asset ->
                        val uri = Uri.parse(asset.uri)
                        if (uri.scheme == "file") File(uri.path.orEmpty()).takeIf { file ->
                            file.canonicalFile.parentFile == app.filesDir.canonicalFile && file.name.startsWith("optimization-media-") && file.extension == "wav"
                        }?.delete()
                    }
                    app.database.trackDao().deleteById(track.id)
                }
            }
        }
    }

    @Test fun actualV2SchemaMigratesQueueHistoryAndFavoriteTime() {
        val name = "optimization-migration.db"
        migrations.createDatabase(name, 2).use { db ->
            db.execSQL("INSERT INTO tracks (id,title,artist,album,durationMs,trackNumber,createdAt) VALUES ('A','A','','',120000,0,1)")
            db.execSQL("INSERT INTO favorites (trackId,addedAt) VALUES ('A',123)")
            db.execSQL("INSERT INTO queue_entries (id,trackId,queueOrder) VALUES (7,'A',0),(8,'A',1)")
            db.execSQL("INSERT INTO playback_snapshot (id,currentTrackId,progressMs,playbackMode,shuffleOrderJson,shuffleHistoryJson,updatedAt) VALUES (1,'A',3000,'SHUFFLE','[1]','[0]',1)")
        }
        migrations.runMigrationsAndValidate(name, 4, true, PickAudioDatabase.MIGRATION_2_3, PickAudioDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT currentEntryId,shuffleOrderJson,shuffleHistoryJson FROM playback_snapshot").use {
                assertTrue(it.moveToFirst()); assertEquals(7L, it.getLong(0)); assertEquals("[8]", it.getString(1)); assertEquals("[7]", it.getString(2))
            }
            db.query("SELECT addedAt,sortOrder FROM favorites").use {
                assertTrue(it.moveToFirst()); assertEquals(123L, it.getLong(0)); assertEquals(123L, it.getLong(1))
            }
        }
        instrumentation.targetContext.deleteDatabase(name)
    }

    private suspend fun await(label: String, condition: () -> Boolean) = withTimeout(12000) {
        while (!withContext(Dispatchers.Main) { condition() }) delay(50)
    }.also { android.util.Log.i("OptimizationMedia", label) }

    @Test fun sleepDeadlineCancelsResolvingAndServiceShutdownCannotResumeIt() = runBlocking {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val coordinator = app.playbackCoordinator
        val id = "optimization_sleep"
        val track = Track(id, "睡眠专项", "", "", 1000, platform = "wy", platformSongId = id)
        val source = app.sourceManager.importSourceFromCode("""
            lx.on(lx.EVENT_NAMES.request, () => new Promise(() => {}));
            lx.send(lx.EVENT_NAMES.inited, {status: true, sources: {wy: {name: '睡眠专项', actions: ['musicUrl'], qualitys: ['128k']}}});
        """.trimIndent())
        val previous = app.database.sourceDao().getSelectionForPlatform("wy")?.takeIf { it.sourceId != source.id }
            ?: PlatformSourceSelectionEntity("wy", "builtin_aggregate")
        val quality = app.userPreferences.defaultOnlineQuality.first()
        try {
            app.sourceManager.selectSourceForPlatform("wy", source.id)
            app.userPreferences.setDefaultOnlineQuality(com.pickaudio.data.model.Quality.Q128K)
            withContext(Dispatchers.Main) { coordinator.setQueueAndPlay(listOf(track)); coordinator.setSleepTimerDuration(200) }
            await("sleep cancelled pending playback") {
                coordinator.sleepTimerRemainingMs.value == null && !coordinator.playRequested.value &&
                    coordinator.uiState.value.phase == com.pickaudio.data.model.PlaybackPhase.PAUSED
            }
            delay(11000) // Pass the platform foreground deadline while the request remains cancelled.
            withContext(Dispatchers.Main) {
                assertFalse(coordinator.isPlaying.value)
                coordinator.setSleepTimer(10)
                app.stopService(android.content.Intent(app, com.pickaudio.playback.PlaybackService::class.java))
            }
            await("actual service shutdown clears timer") { coordinator.sleepTimerRemainingMs.value == null && !coordinator.playRequested.value }
        } finally {
            withContext(Dispatchers.Main) { coordinator.clearQueue() }
            coordinator.flushPersistence()
            app.database.trackDao().deleteById(id)
            app.database.sourceDao().delete(source)
            app.database.sourceDao().setPlatformSelection(previous)
            app.userPreferences.setDefaultOnlineQuality(quality)
            scenario.close()
        }
    }

    @Test fun mediaControllerCommandsAndDuplicateQueueSnapshotUseSameOccurrences() = runBlocking {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val coordinator = app.playbackCoordinator
        val files = (0..1).map { File.createTempFile("optimization-media-", ".wav", app.filesDir) }
        var controller: MediaController? = null
        var restored: PlaybackCoordinator? = null
        val tracks = mutableListOf<Track>()
        try {
            val size = 44 + 44100 * 2 * 30
            val audio = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(size - 8); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(size - 44)
            }.array()
            files.forEachIndexed { index, file ->
                file.writeBytes(audio)
                val id = "optimization_media_${System.nanoTime()}_$index"
                val track = Track(id, "媒体歌曲$index", "验证", "", 30000, localUri = Uri.fromFile(file).toString())
                app.database.trackDao().insertOrUpdate(TrackEntity(id, track.title, track.artist, "", 30000, null))
                app.database.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = id, uri = track.localUri!!,
                    sourceType = "SAF_FILE", fileSize = file.length(), mimeType = "audio/wav", format = "wav"))
                tracks.add(track)
            }
            withContext(Dispatchers.Main) {
                coordinator.clearQueue()
                coordinator.setPlaybackMode(PlaybackMode.LIST_LOOP)
                coordinator.setQueueAndPlay(listOf(tracks[0], tracks[1], tracks[0]), 2)
            }
            await("initial playback") { coordinator.isPlaying.value }
            val future = withContext(Dispatchers.Main) {
                MediaController.Builder(app, SessionToken(app, android.content.ComponentName(app,
                    com.pickaudio.playback.PlaybackService::class.java))).buildAsync()
            }
            controller = withContext(Dispatchers.IO) { future.get(12, TimeUnit.SECONDS) }
            val active = controller!!
            await("full controller timeline") { active.mediaItemCount == 3 && active.currentMediaItemIndex == 2 }
            withContext(Dispatchers.Main) {
                assertTrue(active.availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                assertEquals(coordinator.queueEntries.value[2].id.toString(), active.currentMediaItem!!.mediaId)
                active.seekToNextMediaItem()
            }
            await("standard next") { coordinator.currentIndex.value == 0 && active.currentMediaItemIndex == 0 }
            withContext(Dispatchers.Main) { active.seekTo(2, 2000) }
            await("seek duplicate occurrence") { coordinator.currentIndex.value == 2 && active.currentMediaItemIndex == 2 && coordinator.isPlaying.value }
            val notifications = app.getSystemService(android.app.NotificationManager::class.java)
            fun hasMediaControls(): Boolean = notifications.activeNotifications.any { status ->
                val notification = status.notification
                notification.category == android.app.Notification.CATEGORY_TRANSPORT &&
                    notification.actions.orEmpty().size >= 3 &&
                    notification.extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION,
                        android.media.session.MediaSession.Token::class.java) != null &&
                    notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == tracks[0].title
            }
            withContext(Dispatchers.Main) { active.pause() }
            await("standard pause") { !coordinator.isPlaying.value && !active.playWhenReady }
            await("paused media notification") { hasMediaControls() }
            delay(600)
            assertTrue("A queued self-start must not overwrite paused media controls", hasMediaControls())

            // Exercise the actual notification without a bound controller keeping the service alive.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            withContext(Dispatchers.Main) {
                active.release()
                controller = null
                repeat(3) { coordinator.resume() }
            }
            await("background playback") { coordinator.isPlaying.value }
            await("actual media notification") { hasMediaControls() }
            repeat(5) {
                delay(600)
                assertTrue("Repeated service starts must retain the media notification", hasMediaControls())
                assertTrue("Unbound background service must retain playback", coordinator.isPlaying.value)
            }
            withContext(Dispatchers.Main) { coordinator.seekTo(29000) }
            await("unbound background natural transition") { coordinator.currentIndex.value == 0 && coordinator.isPlaying.value }
            delay(1000)
            assertTrue("Natural transition must retain media controls", hasMediaControls())
            withContext(Dispatchers.Main) { coordinator.playQueueItem(2, 2000, true) }
            await("duplicate occurrence after background transition") { coordinator.currentIndex.value == 2 && coordinator.isPlaying.value }
            withContext(Dispatchers.Main) { coordinator.pause() }
            coordinator.flushPersistence()
            val snapshot = app.database.playbackDao().getSnapshot()!!
            assertEquals(coordinator.queueEntries.value[2].id, snapshot.currentEntryId)
            restored = withContext(Dispatchers.Main) { PlaybackCoordinator(app, app.database, app.sourceManager) }
            val copy = restored!!
            await("snapshot restore") { copy.queue.value.size == 3 }
            withContext(Dispatchers.Main) {
                assertEquals(2, copy.currentIndex.value)
                assertEquals(coordinator.queueEntries.value.map { it.id }, copy.queueEntries.value.map { it.id })
                assertEquals(PlaybackMode.LIST_LOOP, copy.playbackMode.value)
                assertEquals(tracks[0].localUri, copy.currentTrack.value!!.localUri)
                assertFalse(copy.isPlaying.value)
            }
            withContext(Dispatchers.Main) { copy.onServiceDestroyed() }
            copy.flushPersistence()
        } finally {
            withContext(Dispatchers.Main) {
                controller?.release()
                if (!keepFixture) coordinator.clearQueue()
                restored?.onServiceDestroyed()
            }
            coordinator.flushPersistence()
            if (!keepFixture) {
                tracks.forEach { app.database.trackDao().deleteById(it.id) }
                files.forEach { it.delete() }
            }
            scenario.close()
        }
    }
}
