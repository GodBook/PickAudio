package com.pickaudio

import com.pickaudio.online.LyricParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricParserTest {

    @Test
    fun testParseStandardLrc() {
        val lrc = """
            [00:01.00]First line
            [00:05.50]Second line
            [00:10.00]Third line
        """.trimIndent()

        val parsed = LyricParser.parse(lrc)
        assertEquals(3, parsed.size)
        assertEquals(1000L, parsed[0].timeMs)
        assertEquals("First line", parsed[0].text)
        assertEquals(5500L, parsed[1].timeMs)
        assertEquals("Second line", parsed[1].text)
        assertEquals(10000L, parsed[2].timeMs)
        assertEquals("Third line", parsed[2].text)
    }

    @Test
    fun testParseWithTranslations() {
        val lrc = """
            [00:02.00]Hello world
            [00:06.00]Music is life
        """.trimIndent()

        val trans = """
            [00:02.05]你好世界
            [00:05.95]音乐即生命
        """.trimIndent()

        val parsed = LyricParser.parse(lrc, trans)
        assertEquals(2, parsed.size)
        assertEquals("Hello world", parsed[0].text)
        assertEquals("你好世界", parsed[0].translation)
        assertEquals("Music is life", parsed[1].text)
        assertEquals("音乐即生命", parsed[1].translation)
    }

    @Test
    fun testActiveIndexAndOffset() {
        val lrc = """
            [00:02.00]Line 1
            [00:06.00]Line 2
            [00:10.00]Line 3
        """.trimIndent()

        val parsed = LyricParser.parse(lrc)

        // At 1000ms -> before line 1, index 0
        assertEquals(0, LyricParser.findActiveIndex(parsed, 1000L))
        // At 3000ms -> line 1 (starts at 2000)
        assertEquals(0, LyricParser.findActiveIndex(parsed, 3000L))
        // At 6500ms -> line 2 (starts at 6000)
        assertEquals(1, LyricParser.findActiveIndex(parsed, 6500L))
        // At 12000ms -> line 3 (starts at 10000)
        assertEquals(2, LyricParser.findActiveIndex(parsed, 12000L))

        // Test offset: with offset +1000ms, effective position at 5500 is 5500 - 1000 = 4500 (line 1)
        assertEquals(0, LyricParser.findActiveIndex(parsed, 5500L, offsetMs = 1000L))
        // With offset -1000ms, effective position at 5500 is 5500 - (-1000) = 6500 (line 2)
        assertEquals(1, LyricParser.findActiveIndex(parsed, 5500L, offsetMs = -1000L))
    }
}
