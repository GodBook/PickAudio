package com.pickaudio

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.*
import com.pickaudio.data.repository.*
import com.pickaudio.ui.screens.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class OptimizationPerformanceTest {
    @Test fun tenThousandSongsUseScopedPlaylistQueriesAndBackgroundLibraryProjection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queries = CopyOnWriteArrayList<String>()
        val db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java)
            .setQueryCallback({ sql, _ -> if (sql.startsWith("SELECT", true)) queries.add(sql) }, Executor { it.run() }).build()
        try {
            db.withTransaction {
                db.trackDao().insertOrUpdateAll((0 until 10000).map { TrackEntity("large_$it", "歌曲 $it", "歌手 ${it % 100}", "专辑 ${it % 50}", 180000, null, isInLibrary = true) })
                db.playlistDao().insertOrUpdate(PlaylistEntity("large_list", "大库专项"))
                repeat(20) {
                    db.playlistDao().addTrackToPlaylist(PlaylistTrackEntity("large_list", "large_$it", it))
                    db.favoriteDao().addFavorite(FavoriteEntity("large_$it", it.toLong()))
                }
            }
            val repository = PlaylistRepository(db)
            queries.clear()
            var playlist: List<com.pickaudio.data.model.Track> = emptyList()
            val playlistMs = measureTimeMillis { playlist = repository.getTracksForPlaylist("large_list").first() }
            assertEquals(20, playlist.size)
            assertFalse(queries.any { it.trim() == "SELECT * FROM tracks ORDER BY createdAt DESC" })
            assertTrue("Scoped playlist made ${queries.size} SELECTs", queries.size <= 8)
            val library = LibraryRepository(context, db)
            var all: List<com.pickaudio.data.model.Track> = emptyList()
            val loadMs = measureTimeMillis { all = library.getAllTracks().first() }
            assertEquals(10000, all.size)
            val index = withContext(Dispatchers.Default) { buildLibraryIndex(all) }
            var result = LibraryViewState()
            val filterMs = measureTimeMillis {
                result = withContext(Dispatchers.Default) { buildLibraryView(index, LibraryQuery(query = "歌曲 99", sort = "歌曲名称"), emptyList()) }
            }
            assertTrue(result.tracks.isNotEmpty())
            assertTrue(result.tracks.all { it.title.contains("歌曲 99") })
            val samples = (0 until 20).map { i -> measureTimeMillis {
                withContext(Dispatchers.Default) { buildLibraryView(index, LibraryQuery(query = "歌曲 ${90 + i % 10}", sort = "歌曲名称"), emptyList()) }
            } }.sorted()
            android.util.Log.i("PickAudioPerformance", "songs=10000 playlist=20 playlistMs=$playlistMs libraryMs=$loadMs filterFirstMs=$filterMs filterP95Ms=${samples[18]}")
        } finally { db.close() }
        Unit
    }
}
