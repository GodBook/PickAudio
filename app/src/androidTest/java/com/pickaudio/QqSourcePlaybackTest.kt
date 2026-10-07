package com.pickaudio

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.data.model.toTrack
import com.pickaudio.data.repository.LibraryRepository
import com.pickaudio.data.repository.ensureTrackIdentity
import com.pickaudio.network.NetworkPolicy
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class QqSourcePlaybackTest {
    @Test fun qqOriginalMetadataResolvesThroughLxAndMedia3DecodesTheStream() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PickAudioDatabase::class.java).build()
        try {
            MockWebServer().use { server ->
                val pcm = ByteBuffer.allocate(44 + 44100 * 2 * 3).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
                    putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
                    put("data".toByteArray()); putInt(capacity() - 44)
                    while (remaining() >= 2) putShort(0)
                }.array()
                server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(pcm)))
                val song = SearchSongItem("tx", "original_mid", "QQ 原曲回归", "测试歌手", "测试专辑", 3000,
                    metadataJson = """{"songid":123,"strMediaMid":"media_mid"}""").toTrack()
                db.ensureTrackIdentity(song)
                LxSourceManager(context, db, NetworkPolicy(setOf("localhost", "127.0.0.1"))).use { manager ->
                    val source = manager.importSourceFromCode("""
                        lx.on(lx.EVENT_NAMES.request, function(request) {
                            var m = request.info.musicInfo;
                            if (request.source !== 'tx' || m.songId !== 123 || m.songmid !== 'original_mid' || m.strMediaMid !== 'media_mid')
                                throw new Error('原曲信息丢失');
                            return '${server.url("/original.wav")}';
                        });
                        lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{tx:{name:'QQ音频专项',actions:['musicUrl'],qualitys:['128k']}}});
                    """.trimIndent())
                    manager.selectSourceForPlatform("tx", source.id)
                    val url = manager.resolveMusicUrl("tx", "original_mid", "128k", song.title, song.artist)
                    var failure: PlaybackException? = null
                    val player = withContext(Dispatchers.Main) {
                        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(manager.audioClient())))
                            .build().apply {
                                addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failure = error } })
                                setMediaItem(MediaItem.fromUri(url)); prepare(); play()
                            }
                    }
                    try {
                        withTimeout(10000) {
                            while (!withContext(Dispatchers.Main) {
                                failure?.let { throw it }
                                player.isPlaying && player.currentPosition > 100
                            }) delay(50)
                        }
                        assertEquals("/original.wav", server.takeRequest().path)
                        assertTrue(LibraryRepository(context, db).getAllTracks().first().isEmpty())
                    } finally { withContext(Dispatchers.Main) { player.release() } }
                }
            }
        } finally { db.close() }
    }
}
