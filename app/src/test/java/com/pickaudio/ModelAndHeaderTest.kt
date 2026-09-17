package com.pickaudio

import com.google.gson.Gson
import com.pickaudio.backup.BackupManifest
import com.pickaudio.backup.BackupPlaylist
import com.pickaudio.backup.BackupTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelAndHeaderTest {

    @Test
    fun testParseLxHeader() {
        val script = """
            /**
             * @name 我的测试源
             * @description 这是一个全能测试音源
             * @version 1.2.0
             * @author 测试者
             * @homepage https://example.com/source
             */
            const { EVENT_NAMES } = globalThis.lx;
        """.trimIndent()

        var name = ""
        var desc = ""
        var version = ""
        var author = ""
        var homepage = ""

        for (line in script.lines().take(20)) {
            val t = line.trim().trimStart('*').trim()
            if (t.startsWith("@name")) name = t.removePrefix("@name").trim()
            if (t.startsWith("@description")) desc = t.removePrefix("@description").trim()
            if (t.startsWith("@version")) version = t.removePrefix("@version").trim()
            if (t.startsWith("@author")) author = t.removePrefix("@author").trim()
            if (t.startsWith("@homepage")) homepage = t.removePrefix("@homepage").trim()
        }

        assertEquals("我的测试源", name)
        assertEquals("这是一个全能测试音源", desc)
        assertEquals("1.2.0", version)
        assertEquals("测试者", author)
        assertEquals("https://example.com/source", homepage)
    }

    @Test
    fun testBackupManifestSerialization() {
        val manifest = BackupManifest(
            version = 1,
            playlists = listOf(
                BackupPlaylist(id = "pl_1", name = "摇滚合集", sortOrder = 1, trackIds = listOf("t1", "t2"))
            ),
            favorites = listOf("t1"),
            tracks = listOf(
                BackupTrack(id = "t1", title = "海阔天空", artist = "Beyond", album = "海阔天空", durationMs = 320000L),
                BackupTrack(id = "t2", title = "光辉岁月", artist = "Beyond", album = "命运派对", durationMs = 300000L)
            ),
            lyrics = emptyList(),
            sourceDescriptors = emptyList()
        )

        val gson = Gson()
        val json = gson.toJson(manifest)
        assertNotNull(json)

        val restored = gson.fromJson(json, BackupManifest::class.java)
        assertEquals(1, restored.version)
        assertEquals(1, restored.playlists.size)
        assertEquals("摇滚合集", restored.playlists[0].name)
        assertEquals(2, restored.tracks.size)
        assertEquals("海阔天空", restored.tracks[0].title)
    }
}
