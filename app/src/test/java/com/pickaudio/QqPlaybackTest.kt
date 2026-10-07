package com.pickaudio

import com.google.gson.JsonParser
import com.pickaudio.online.QqMusicPlaybackAdapter
import com.pickaudio.online.QqMusicSearchAdapter
import com.pickaudio.source.lxMusicInfo
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class QqPlaybackTest {
    private fun response(items: String, servers: String = "\"http://ws.stream.qqmusic.qq.com/\",\"https://isure.stream.qqmusic.qq.com/\"") =
        """{"code":0,"req_0":{"code":0,"data":{"retcode":0,"sip":[$servers],"midurlinfo":[$items]}}}"""

    @Test fun originalSongAndMediaIdsAreKeptSeparateForBothQualities() = runBlocking {
        MockWebServer().use { server ->
            val adapter = QqMusicPlaybackAdapter(OkHttpClient(), server.url("/musicu.fcg").toString())
            listOf("128k" to "M500media.mp3", "320k" to "M800media.mp3").forEach { (quality, filename) ->
                server.enqueue(MockResponse().setBody(response("""{"songmid":"song","purl":"$filename?vkey=fixture"}""")))
                assertEquals("https://isure.stream.qqmusic.qq.com/$filename?vkey=fixture", adapter.resolve("song", "media", quality))
                val request = server.takeRequest()
                val param = JsonParser.parseString(request.requestUrl!!.queryParameter("data")).asJsonObject
                    .getAsJsonObject("req_0").getAsJsonObject("param")
                assertEquals("song", param.getAsJsonArray("songmid")[0].asString)
                assertEquals(filename, param.getAsJsonArray("filename")[0].asString)
                assertEquals("https://y.qq.com/", request.getHeader("Referer"))
            }
        }
    }

    @Test fun matchingRecordingIsSelectedAndPreviewOrPermissionFailuresAreRejected() {
        val adapter = QqMusicPlaybackAdapter(OkHttpClient())
        val items = """{"songmid":"other","purl":"other.mp3"},{"songmid":"song","purl":"original.mp3"}"""
        assertTrue(adapter.parseUrl(response(items), "song").endsWith("/original.mp3"))
        listOf(
            response("""{"songmid":"song","purl":"","opi30surl":"preview.mp3"}"""),
            response("""{"songmid":"other","purl":"other.mp3"}"""),
            """{"code":0,"req_0":{"code":1000,"data":{"retcode":104009,"midurlinfo":[]}}}""",
            response("""{"songmid":"song","purl":"//example.com/other.mp3"}"""),
            response("""{"songmid":"song","purl":"audio.mp3"}""", "\"https://example.com/\"")
        ).forEach { assertTrue(runCatching { adapter.parseUrl(it, "song") }.isFailure) }
    }

    @Test fun lxFieldsPreserveNumericIdAndMediaIdFromBothQqResponseFormats() {
        listOf(
            """{"songid":123,"songmid":"song","strMediaMid":"media","albumname":"专辑","albummid":"album","size128":1048576}""",
            """{"id":123,"mid":"song","file":{"media_mid":"media","size_128mp3":1048576},"album":{"name":"专辑","mid":"album"}}"""
        ).forEach { metadata ->
            val info = lxMusicInfo("tx", "song", metadata, "歌曲", "歌手")
            assertEquals(123, info.get("songId").asInt)
            assertEquals(123, info.get("id").asInt)
            assertEquals("song", info.get("songmid").asString)
            assertEquals("media", info.get("strMediaMid").asString)
            assertEquals("专辑", info.get("albumName").asString)
            assertEquals("album", info.get("albumId").asString)
            assertEquals("歌曲", info.get("name").asString)
            assertEquals("歌手", info.get("singer").asString)
            assertTrue(info.getAsJsonObject("_types").has("128k"))
        }
    }

    @Test fun legacyMetadataLookupAcceptsOnlyTheRequestedRecording() {
        val response = """{"code":0,"data":[{"mid":"other","id":1},{"mid":"song","id":123,"file":{"media_mid":"media"}}]}"""
        val metadata = QqMusicSearchAdapter.parseSongMetadata(response, "song")
        val info = lxMusicInfo("tx", "song", metadata, "原曲", "歌手")
        assertEquals(123, info.get("songId").asInt)
        assertEquals("media", info.get("strMediaMid").asString)
        assertTrue(runCatching { QqMusicSearchAdapter.parseSongMetadata(response, "missing") }.isFailure)
    }
}
