package com.pickaudio

import android.net.Uri
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.backup.BackupManager
import com.pickaudio.data.db.*
import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.data.model.toTrack
import com.pickaudio.data.repository.*
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryMembershipTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val migrations = MigrationTestHelper(instrumentation, PickAudioDatabase::class.java)
    private lateinit var db: PickAudioDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build() }
    @After fun cleanup() { db.close() }
    private fun song(id: String = "mid") = SearchSongItem("tx", id, "曲库专项歌曲", "歌手", "专辑", 180000,
        metadataJson = """{"songid":123,"songmid":"$id","strMediaMid":"media","albumname":"专辑","albummid":"album"}""").toTrack()

    @Test fun playingFavoritingPlaylistAndDownloadRecordsStayOutUntilExplicitlyAdded() = runBlocking {
        val library = LibraryRepository(context, db)
        val song = song()
        val canonical = db.ensureTrackIdentity(song)
        db.queueDao().insertQueueEntries(listOf(QueueEntryEntity(trackId = canonical.id, queueOrder = 0)))
        db.playlistDao().insertOrUpdate(PlaylistEntity("favorite", "我喜欢", isSystem = true))
        val playlists = PlaylistRepository(db)
        playlists.addTracks("favorite", listOf(song))
        val playlist = playlists.createAndAddTracks("保留歌单", listOf(song))
        LxSourceManager(context, db).use { sources ->
            sources.ensureBuiltinSources()
            DownloadCoordinator(context, db, sources, scheduleOverride = {}).enqueueBatch(listOf(song), "128k")
        }
        assertTrue(library.getAllTracks().first().isEmpty())
        assertEquals(1, db.trackDao().getTrackCount())
        assertEquals(1, library.addTracks(listOf(song, song)))
        assertEquals(0, library.addTracks(listOf(song)))
        assertEquals(listOf(song.id), LibraryRepository(context, db).getAllTracks().first().map { it.id })
        library.removeTracks(listOf(song.id))
        assertTrue(library.getAllTracks().first().isEmpty())
        assertEquals(1, playlists.getTracksForPlaylist(playlist).first().size)
        assertTrue(db.favoriteDao().isFavoriteSync(song.id))
        assertEquals(1, db.queueDao().getEntriesSync().size)
        assertNotNull(library.getTrack(song.id))
    }

    @Test fun explicitAddReusesCanonicalIdentityAndSurvivesFreshBackupRestore() = runBlocking {
        db.trackDao().insertOrUpdate(TrackEntity("canonical", "原曲", "歌手", "专辑", 180000, null))
        db.onlineRefDao().insertOrUpdate(OnlineRefEntity(trackId = "canonical", platform = "tx", platformSongId = "mid", platformMetadataJson = "{}"))
        val library = LibraryRepository(context, db)
        assertEquals(1, library.addTracks(listOf(song())))
        db.ensureTrackIdentity(song("listened"))
        assertEquals(2, db.trackDao().getTrackCount())
        assertNull(db.trackDao().getTrackById(song().id))
        val file = File.createTempFile("membership-backup-", ".zip", context.cacheDir)
        val restored = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
        try {
            val manager = BackupManager(context, db)
            manager.export(Uri.fromFile(file))
            val manifest = manager.inspect(Uri.fromFile(file))
            BackupManager(context, restored).restore(manifest, false)
            assertEquals(listOf("canonical"), LibraryRepository(context, restored).getAllTracks().first().map { it.id })
            assertNotNull(restored.trackDao().getTrackById(song("listened").id))
        } finally { restored.close(); file.delete() }
    }

    @Test fun qqScriptReceivesLxMetadataForTheOriginalRecordingWithoutAddingToLibrary() = runBlocking {
        val song = song()
        db.ensureTrackIdentity(song)
        LxSourceManager(context, db).use { manager ->
            val source = manager.importSourceFromCode("""
                lx.on(lx.EVENT_NAMES.request, function(request) {
                    var m = request.info.musicInfo;
                    if (request.source !== 'tx' || m.songId !== 123 || m.id !== 123 || m.songmid !== 'mid' ||
                        m.strMediaMid !== 'media' || m.name !== '曲库专项歌曲' || m.singer !== '歌手' || m.albumName !== '专辑')
                        throw new Error('QQ 歌曲信息不兼容');
                    return 'https://isure.stream.qqmusic.qq.com/original.mp3?vkey=fixture';
                });
                lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{tx:{name:'QQ专项',actions:['musicUrl'],qualitys:['128k']}}});
            """.trimIndent())
            manager.selectSourceForPlatform("tx", source.id)
            assertEquals("https://isure.stream.qqmusic.qq.com/original.mp3?vkey=fixture",
                manager.resolveMusicUrl("tx", "mid", "128k", song.title, song.artist))
            assertTrue(LibraryRepository(context, db).getAllTracks().first().isEmpty())
        }
    }

    @Test fun v4MigrationKeepsLocalLibraryAndHidesListenedOnlineRecordsWithoutDeletingRelations() {
        val name = "membership-migration.db"
        try {
            migrations.createDatabase(name, 4).use { database ->
                database.execSQL("INSERT INTO tracks (id,title,artist,album,durationMs,trackNumber,createdAt) VALUES ('local','本地','','',1000,0,1),('online_tx_mid','试听','','',1000,0,2)")
                database.execSQL("INSERT INTO local_assets (trackId,uri,sourceType,fileSize,isAvailable,folderName,folderId,fileName) VALUES ('local','file:///fixture.wav','SAF_FILE',44,1,'','','')")
                database.execSQL("INSERT INTO favorites (trackId,addedAt,sortOrder) VALUES ('online_tx_mid',123,123)")
                database.execSQL("INSERT INTO queue_entries (trackId,queueOrder) VALUES ('online_tx_mid',0)")
            }
            migrations.runMigrationsAndValidate(name, 5, true, PickAudioDatabase.MIGRATION_4_5).use { database ->
                database.query("SELECT id FROM tracks WHERE isInLibrary = 1").use {
                    assertTrue(it.moveToFirst()); assertEquals("local", it.getString(0)); assertFalse(it.moveToNext())
                }
                database.query("SELECT trackId FROM favorites").use { assertTrue(it.moveToFirst()); assertEquals("online_tx_mid", it.getString(0)) }
                database.query("SELECT trackId FROM queue_entries").use { assertTrue(it.moveToFirst()); assertEquals("online_tx_mid", it.getString(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }
}
