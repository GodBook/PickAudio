package com.pickaudio

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.online.LyricLine
import com.pickaudio.ui.components.SyncedLyricsView
import com.pickaudio.ui.theme.PickAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun currentLyricRemainsVisibleWithLargeTextInCompactViewport() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                PickAudioTheme {
                    Box(Modifier.width(320.dp).height(200.dp)) {
                        SyncedLyricsView(listOf(LyricLine(0, "歌词在小屏保持可见"), LyricLine(5000, "下一行歌词")), 3500, 300, {}, {})
                    }
                }
            }
        } }
        compose.onNodeWithText("歌词在小屏保持可见").assertIsDisplayed()
        compose.onNodeWithText("校准: 300ms").assertIsDisplayed()
    }
}
