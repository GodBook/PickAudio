package com.pickaudio

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.backup.*
import com.pickaudio.data.db.*
import com.pickaudio.data.model.*
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.data.repository.*
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.source.LxSourceManager
import com.pickaudio.network.NetworkPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class RepositoryIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PickAudioDatabase
    private lateinit var previousPreferences: Map<String, String>
    private val published = mutableListOf<Uri>()
    private val sourceManagers = mutableListOf<LxSourceManager>()
    private fun createSourceManager() = LxSourceManager(context, db, NetworkPolicy(setOf("localhost", "127.0.0.1")))
        .also { sourceManagers.add(it) }

    @Before fun setup() = runBlocking {
        previousPreferences = UserPreferences(context).exportSettings()
        db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
        db.playlistDao().insertOrUpdate(PlaylistEntity("favorite", "我喜欢", isSystem = true))
    }
    @After fun cleanup() = runBlocking {
        UserPreferences(context).restoreSettings(previousPreferences)
        published.forEach { context.contentResolver.delete(it, null, null) }
        sourceManagers.forEach { it.close() }
        db.close()
    }
    private suspend fun seed(id: String = "test_song"): Track {
        val track = Track(id, "测试音乐", "测试歌手", "测试专辑", 120000)
        db.trackDao().insertOrUpdate(TrackEntity(id, track.title, track.artist, track.album, track.durationMs, null))
        return track
    }

    @Test fun batchMembershipAndReorderingPersistWithoutDuplicates() = runBlocking {
        val repository = PlaylistRepository(db)
        val first = seed("test_first"); val second = seed("test_second")
        val playlist = repository.createPlaylist("测试歌单")
        repository.addTracks(playlist, listOf(first, second, first))
        assertEquals(2, repository.getTracksForPlaylist(playlist).first().size)
        repository.reorderTracks(playlist, listOf(second.id, first.id))
        assertEquals(listOf(second.id, first.id), repository.getTracksForPlaylist(playlist).first().map { it.id })
        repository.renamePlaylist(playlist, "重命名后的歌单")
        db.trackDao().getTrackById(first.id)?.let { db.trackDao().insertOrUpdate(it.copy(durationMs = 130000)) }
        assertEquals(2, repository.getTracksForPlaylist(playlist).first().size)
        repository.removeTracks(playlist, listOf(second.id))
        assertEquals(listOf(first.id), repository.getTracksForPlaylist(playlist).first().map { it.id })
        assertEquals(2, db.trackDao().getTrackCount())
    }

    @Test fun lyricsAndOffsetsAreStoredSeparatelyForEachSong() = runBlocking {
        val first = seed("lyrics_first"); val second = seed("lyrics_second")
        val file = File.createTempFile("pickaudio-lyrics-", ".lrc", context.cacheDir)
        try {
            file.writeText("[00:00.00]第一句\n[00:01.00]第二句")
            val repository = LyricRepository(context, db)
            repository.importLrc(first.id, Uri.fromFile(file))
            repository.importLrc(second.id, Uri.fromFile(file))
            repository.saveOffset(first.id, 300)
            assertEquals(300, repository.load(first).offsetMs)
            assertEquals(0, repository.load(second).offsetMs)
            assertEquals(2, repository.load(first, refresh = true).lines.size)
        } finally { file.delete() }
    }

    @Test fun backupChecksumsAndRepeatedRestoresPreserveExistingData() = runBlocking {
        val track = seed()
        val repository = PlaylistRepository(db)
        val playlist = repository.createPlaylist("备份测试")
        repository.addTracks(playlist, listOf(track))
        repository.addTracks("favorite", listOf(track))
        val preferences = UserPreferences(context)
        preferences.setDefaultOnlineQuality(Quality.Q320K)
        val file = File.createTempFile("pickaudio-backup-", ".zip", context.cacheDir)
        try {
            val manager = BackupManager(context, db)
            manager.export(Uri.fromFile(file))
            val manifest = manager.inspect(Uri.fromFile(file))
            assertEquals("320k", manifest.settings!!["onlineQuality"])
            manager.restore(manifest, true)
            manager.restore(manifest, true)
            assertEquals(2, repository.getAllPlaylists().first().size)
            assertEquals(1, repository.getTracksForPlaylist(playlist).first().size)
            assertEquals(1, db.trackDao().getTrackCount())
            assertEquals(Quality.Q320K, preferences.defaultOnlineQuality.first())
        } finally { file.delete() }
    }

    @Test fun interruptedDownloadsRecoverAndCacheCleanupPreservesTheirParts() = runBlocking {
        seed()
        val manager = createSourceManager()
        manager.ensureBuiltinSources()
        val coordinator = DownloadCoordinator(context, db, manager)
        val retained = File(coordinator.partialDirectory, "temp_test_interrupted.part").apply { writeBytes(ByteArray(2048)) }
        val obsolete = File(coordinator.partialDirectory, "temp_test_obsolete.part").apply { writeBytes(ByteArray(1024)) }
        val legacy = File(context.cacheDir, "downloads/temp_legacy_partial.part").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3072)) }
        try {
            db.downloadDao().insertOrUpdate(DownloadTaskEntity("test_interrupted", "test_song", "测试音乐", "测试歌手", "测试专辑", null, "wy", "1", "128k", "DOWNLOADING", tempFilePath = retained.path))
            db.downloadDao().insertOrUpdate(DownloadTaskEntity("legacy_partial", "test_song", "旧下载", "测试歌手", "测试专辑", null, "wy", "2", "128k", "PAUSED", downloadedBytes = 3072, tempFilePath = legacy.path))
            val fresh = DownloadCoordinator(context, db, manager)
            fresh.recoverInterrupted()
            assertEquals("PAUSED", db.downloadDao().getTaskById("test_interrupted")!!.status)
            CacheRepository(context, db, coordinator).clearUnusedParts()
            assertTrue(retained.exists()); assertFalse(obsolete.exists())
            assertFalse(legacy.exists())
            assertEquals(3072, File(coordinator.partialDirectory, "temp_legacy_partial.part").length())
            assertEquals(3072, db.downloadDao().getTaskById("legacy_partial")!!.downloadedBytes)
        } finally { retained.delete(); obsolete.delete(); legacy.delete(); File(coordinator.partialDirectory, "temp_legacy_partial.part").delete() }
    }

    @Test fun roomMigrationPreservesSongsPlaylistsAndDownloads() = runBlocking {
        val name = "pickaudio-migration-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, PickAudioDatabase::class.java, name)
            .addMigrations(PickAudioDatabase.MIGRATION_1_2, PickAudioDatabase.MIGRATION_2_3, PickAudioDatabase.MIGRATION_3_4).build()
        var disk = open()
        disk.trackDao().insertOrUpdate(TrackEntity("legacy", "旧歌曲", "歌手", "专辑", 120000, null))
        disk.playlistDao().insertOrUpdate(PlaylistEntity("legacy_playlist", "旧歌单"))
        disk.playlistDao().addTrackToPlaylist(PlaylistTrackEntity("legacy_playlist", "legacy", 0))
        disk.downloadDao().insertOrUpdate(DownloadTaskEntity("legacy_task", "legacy", "旧歌曲", "歌手", "专辑", null, "wy", "1", "128k", "PAUSED", downloadedBytes = 1234))
        disk.close()
        try {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                sqlite.execSQL("ALTER TABLE local_assets DROP COLUMN folderName")
                listOf("folderId", "fileName", "audioInfoJson", "unavailableReason").forEach { sqlite.execSQL("ALTER TABLE local_assets DROP COLUMN $it") }
                listOf("actualQuality", "bytesPerSecond", "etaSeconds", "resourceEtag", "durationMs").forEach { sqlite.execSQL("ALTER TABLE download_tasks DROP COLUMN $it") }
                listOf("executionGeneration", "publishToken", "publishStage").forEach { sqlite.execSQL("ALTER TABLE download_tasks DROP COLUMN $it") }
                sqlite.execSQL("ALTER TABLE playback_snapshot DROP COLUMN currentEntryId")
                sqlite.execSQL("ALTER TABLE playback_snapshot DROP COLUMN queueRevision")
                sqlite.execSQL("ALTER TABLE favorites DROP COLUMN sortOrder")
                sqlite.execSQL("DROP TABLE restore_sessions")
                sqlite.version = 1
            }
            disk = open()
            assertEquals("旧歌曲", disk.trackDao().getTrackById("legacy")!!.title)
            assertEquals(1, disk.playlistDao().getTracksForPlaylist("legacy_playlist").first().size)
            assertEquals(1234L, disk.downloadDao().getTaskById("legacy_task")!!.downloadedBytes)
            assertNull(disk.downloadDao().getTaskById("legacy_task")!!.actualQuality)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    @Test fun downloadUsesTheRealContainerAndRejectsFakeLossless() = runBlocking {
        val audio = ByteBuffer.allocate(44 + 44100 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(capacity() - 44)
        }.array()
        val server = ServerSocket(0)
        val serving = launch(Dispatchers.IO) {
            repeat(2) {
                try { server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) {}
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${audio.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(audio); flush()
                    }
                } } catch (e: java.net.SocketException) { if (!server.isClosed) throw e }
            }
        }
        try {
            val sourceManager = createSourceManager()
            sourceManager.ensureBuiltinSources()
            val script = """
                /**
                 * @name 本地验证音源
                 * @version 1.0.0
                 */
                const { EVENT_NAMES } = globalThis.lx;
                lx.on(EVENT_NAMES.request, () => Promise.resolve("http://127.0.0.1:${server.localPort}/audio"));
                lx.send(EVENT_NAMES.inited, { status: true, sources: { wy: { name: '测试', type: 'music', actions: ['musicUrl'], qualitys: ['128k', 'flac'] } } });
            """.trimIndent()
            val source = sourceManager.importSourceFromCode(script)
            sourceManager.selectSourceForPlatform("wy", source.id)
            assertEquals("http://127.0.0.1:${server.localPort}/audio", sourceManager.resolveMusicUrl("wy", "1", "128k", "测试音乐", "测试歌手"))
            val first = seed("download_standard"); val second = seed("download_lossless")
            val coordinator = DownloadCoordinator(context, db, sourceManager)
            coordinator.recoverInterrupted()
            db.downloadDao().insertOrUpdate(DownloadTaskEntity("standard_task", first.id, first.title, first.artist, first.album, null, "wy", "1", "128k", "PENDING"))
            db.downloadDao().insertOrUpdate(DownloadTaskEntity("lossless_task", second.id, second.title, second.artist, second.album, null, "wy", "2", "flac", "PENDING"))
            withTimeout(30000) { coordinator.runPending() }
            val completed = db.downloadDao().getTaskById("standard_task")!!
            completed.targetUri?.let { published.add(Uri.parse(it)) }
            assertEquals(completed.errorMessage, "COMPLETED", completed.status)
            assertTrue(completed.actualQuality.orEmpty().startsWith("PCM"))
            assertEquals("PCM", AudioInfo.decode(db.localAssetDao().getAssetsForTrack(first.id).single().audioInfoJson)!!.codec)
            assertEquals("wav", db.localAssetDao().getAssetsForTrack(first.id).single().format)
            val rejected = db.downloadDao().getTaskById("lossless_task")!!
            assertEquals("FAILED", rejected.status)
            assertTrue(rejected.errorMessage.orEmpty().contains("FLAC"))
            assertTrue(db.localAssetDao().getAssetsForTrack(second.id).isEmpty())
        } finally { server.close(); serving.cancel() }
    }

    @Test fun scanHonorsShortAudioFilterAndRescanningSkipsExistingFiles() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(android.Manifest.permission.READ_MEDIA_AUDIO)
        try {
            val name = "拾音扫描验证_${System.nanoTime()}"
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.wav")
                put(MediaStore.Audio.Media.TITLE, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/PickAudioVerification")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.DURATION, 1000)
            })!!
            published.add(uri)
            val audio = ByteBuffer.allocate(44 + 88200).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(capacity() - 44)
            }.array()
            context.contentResolver.openOutputStream(uri)!!.use { it.write(audio) }
            val repository = LibraryRepository(context, db)
            repository.scanMediaStore(true)
            assertTrue(db.trackDao().getAllTracks().first().none { it.title == name })
            repository.scanMediaStore(false)
            val count = db.trackDao().getAllTracks().first().count { it.title == name }
            assertEquals(1, count)
            repository.scanMediaStore(false)
            assertEquals(1, db.trackDao().getAllTracks().first().count { it.title == name })
        } finally { automation.dropShellPermissionIdentity() }
    }
}
