package com.pickaudio

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.pickaudio.backup.*
import com.pickaudio.data.db.*
import com.pickaudio.data.model.Quality
import com.pickaudio.data.preferences.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class OptimizationBackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PickAudioDatabase
    private lateinit var previousSettings: Map<String, String>
    @Before fun setup() = runBlocking {
        previousSettings = UserPreferences(context).exportSettings()
        db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
    }
    @After fun cleanup() = runBlocking {
        db.restoreSessionDao().getPending().first().forEach {
            File(context.filesDir, "restore_payloads/${it.id}.json").delete()
        }
        UserPreferences(context).restoreSettings(previousSettings)
        db.close()
    }
    private fun manifest(version: Int = 3) = BackupManifest(version = version,
        playlists = listOf(BackupPlaylist("restored_list", "恢复歌单", 4, listOf("restored_song"), 111, mapOf("restored_song" to 222))),
        favorites = listOf("restored_song"),
        tracks = listOf(BackupTrack("restored_song", "恢复🎵", "歌手", "专辑", 12345, coverUri = "https://example.com/cover", createdAt = 123,
            files = listOf(BackupFileHint(456, "audio/flac", "flac", "a".repeat(64), "Music/录音")))),
        lyrics = listOf(BackupLyric("restored_song", 300, "[00:00.00]恢复歌词", "MANUAL_LRC")),
        sourceDescriptors = emptyList(), settings = mapOf("onlineQuality" to "320k"),
        favoriteDetails = if (version >= 3) listOf(BackupFavorite("restored_song", 321, 654)) else null)
    private class Interrupted : Error("simulated process interruption")

    @Test fun databaseCommitCanResumeSettingsWithoutDuplicateAssociations() = runBlocking {
        UserPreferences(context).setDefaultOnlineQuality(Quality.Q128K)
        val failing = BackupManager(context, db) { if (it == "DATABASE_APPLIED") throw Interrupted() }
        assertTrue(runCatching { failing.restore(manifest(), true) }.exceptionOrNull() is Interrupted)
        val session = db.restoreSessionDao().getPending().first().single()
        assertEquals("DATABASE_APPLIED", session.state)
        assertTrue(session.manifestJson.length < 512)
        assertEquals(Quality.Q128K, UserPreferences(context).defaultOnlineQuality.first())
        val manager = BackupManager(context, db)
        val report = manager.resumeSession(session.id)
        assertTrue(report.settingsRestored)
        assertEquals(Quality.Q320K, UserPreferences(context).defaultOnlineQuality.first())
        assertEquals("COMPLETED", db.restoreSessionDao().getById(session.id)!!.state)
        assertFalse(File(context.filesDir, "restore_payloads/${session.id}.json").exists())
        assertEquals(321, db.favoriteDao().getFavoritesSync().single().addedAt)
        assertEquals(654, db.favoriteDao().getFavoritesSync().single().sortOrder)
        assertEquals(222, db.playlistDao().getMembersSync("restored_list").single().addedAt)
        manager.restore(manifest(), true)
        assertEquals(1, db.trackDao().getTrackCount())
        assertEquals(321, db.favoriteDao().getFavoritesSync().single().addedAt)
        assertEquals(1, db.playlistDao().getMembersSync("restored_list").size)
    }
    @Test fun crashAfterSettingsDoesNotOverwriteLaterUserPreferenceOnResume() = runBlocking {
        val failing = BackupManager(context, db) { if (it == "SETTINGS_APPLIED") throw Interrupted() }
        assertTrue(runCatching { failing.restore(manifest(), true) }.exceptionOrNull() is Interrupted)
        UserPreferences(context).setDefaultOnlineQuality(Quality.Q128K)
        val id = db.restoreSessionDao().getPending().first().single().id
        BackupManager(context, db).resumeSession(id)
        assertEquals(Quality.Q128K, UserPreferences(context).defaultOnlineQuality.first())
        assertTrue(db.restoreSessionDao().getPending().first().isEmpty())
    }
    @Test fun exportRoundTripsHintsTimesAndLegacyVersionsStillLoad() = runBlocking {
        val manager = BackupManager(context, db)
        manager.restore(manifest(), false)
        val file = File.createTempFile("optimization-backup-", ".zip", context.cacheDir)
        try {
            manager.export(Uri.fromFile(file))
            val loaded = manager.inspect(Uri.fromFile(file))
            assertEquals(3, loaded.version)
            assertEquals(321, loaded.favoriteDetails!!.single().addedAt)
            assertEquals("a".repeat(64), loaded.tracks.single().files!!.single().sha256)
            assertEquals(123, loaded.tracks.single().createdAt)
            for (version in 1..2) {
                val bytes = Gson().toJson(manifest(version)).toByteArray(Charsets.UTF_8)
                ZipOutputStream(file.outputStream()).use {
                    it.putNextEntry(ZipEntry("manifest.json")); it.write(bytes); it.closeEntry()
                    if (version == 2) {
                        it.putNextEntry(ZipEntry("manifest.sha256"))
                        it.write(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }.toByteArray())
                        it.closeEntry()
                    }
                }
                assertEquals(version, manager.inspect(Uri.fromFile(file)).version)
            }
        } finally { file.delete() }
    }
    @Test fun oversizedExportKeepsDestinationAndInvalidReferencesDoNotCreateSession() = runBlocking {
        repeat(18) {
            val id = "large_song_$it"
            db.trackDao().insertOrUpdate(TrackEntity(id, "歌词歌曲", "", "", 123, null))
            db.lyricDao().insertOrUpdate(LyricRecordEntity(id, "MANUAL_LRC", "x".repeat(1024 * 1024)))
        }
        val file = File.createTempFile("optimization-preserve-", ".zip", context.cacheDir).apply { writeText("existing backup") }
        try {
            val manager = BackupManager(context, db)
            assertTrue(runCatching { manager.export(Uri.fromFile(file)) }.isFailure)
            assertEquals("existing backup", file.readText())
            assertTrue(runCatching { manager.restore(manifest().copy(favorites = listOf("missing")), false) }.isFailure)
            assertTrue(db.restoreSessionDao().getPending().first().isEmpty())
        } finally { file.delete() }
    }
}
