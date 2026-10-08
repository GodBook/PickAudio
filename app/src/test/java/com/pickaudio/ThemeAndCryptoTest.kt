package com.pickaudio

import androidx.compose.ui.graphics.luminance
import com.pickaudio.data.model.ThemeColor
import com.pickaudio.source.LxCrypto
import com.pickaudio.ui.theme.pickAudioColorScheme
import org.junit.Assert.*
import org.junit.Test

class ThemeAndCryptoTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun aesMatchesNistEcbAndCbcVectorsWithPkcsPadding() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val input = hex("6bc1bee22e409f96e93d7e117393172a")
        val ecb = LxCrypto.aesEncrypt(input, "aes-128-ecb", key, byteArrayOf())
        assertEquals(32, ecb.size)
        assertArrayEquals(hex("3ad77bb40d7a3660a89ecaf32466ef97"), ecb.copyOf(16))
        val cbc = LxCrypto.aesEncrypt(input, "cbc", key, hex("000102030405060708090a0b0c0d0e0f"))
        assertArrayEquals(hex("7649abac8119b246cee98e9b12e9197d"), cbc.copyOf(16))
        assertTrue(runCatching { LxCrypto.aesEncrypt(input, "aes-256-ecb", key, byteArrayOf()) }.isFailure)
        assertTrue(runCatching { LxCrypto.aesEncrypt(input, "cbc", key, byteArrayOf()) }.isFailure)
    }

    @Test fun everyThemeHasDistinctAccentsAndReadableTextInBothModes() {
        for (dark in listOf(false, true)) {
            val schemes = ThemeColor.entries.map { pickAudioColorScheme(it, dark) }
            assertEquals(ThemeColor.entries.size, schemes.map { it.primary }.distinct().size)
            schemes.forEach { scheme ->
                listOf(scheme.primary to scheme.onPrimary, scheme.primaryContainer to scheme.onPrimaryContainer,
                    scheme.secondary to scheme.onSecondary, scheme.secondaryContainer to scheme.onSecondaryContainer,
                    scheme.tertiary to scheme.onTertiary, scheme.tertiaryContainer to scheme.onTertiaryContainer,
                    scheme.background to scheme.onBackground, scheme.surface to scheme.onSurface).forEach { (bg, text) ->
                    val contrast = (maxOf(bg.luminance(), text.luminance()) + 0.05f) /
                        (minOf(bg.luminance(), text.luminance()) + 0.05f)
                    assertTrue("Theme contrast $contrast, dark=$dark", contrast >= 4.5f)
                }
            }
        }
        assertEquals(ThemeColor.BLUE, ThemeColor.fromName(null))
        assertEquals(ThemeColor.BLUE, ThemeColor.fromName("unknown-future-color"))
    }
}
