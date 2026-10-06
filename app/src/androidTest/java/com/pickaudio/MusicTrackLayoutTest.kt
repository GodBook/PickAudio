package com.pickaudio

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.model.Track
import com.pickaudio.ui.components.MusicTrackRow
import com.pickaudio.ui.components.TrackMenuAction
import com.pickaudio.ui.theme.PickAudioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicTrackLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun metadataAndActionsRemainVisibleWithLargeTextInCompactViewport() {
        val track = Track(
            "layout_local", "覆盖升级保留验证", "拾音验证", "体验升级", 20000,
            localUri = "file:///layout-verification.wav"
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    PickAudioTheme {
                        Box(Modifier.width(320.dp).height(260.dp)) {
                            MusicTrackRow(
                                track, isCurrent = true, onClick = {}, onFavorite = {},
                                actions = listOf(TrackMenuAction("加入歌单") {})
                            )
                        }
                    }
                }
            }
        }
        val title = compose.onNodeWithText(track.title, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val metadata = compose.onNodeWithText("拾音验证 · 体验升级", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val origin = compose.onNodeWithText("本地 · 0:20 · 当前歌曲", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("歌手信息不应与歌曲标题重叠", metadata.top >= title.bottom)
        assertTrue("来源信息不应与歌手信息重叠", origin.top >= metadata.bottom)
        // Material buttons expand their touch region beyond the visible icon surface.
        compose.onNodeWithContentDescription("加入我喜欢").assertIsDisplayed()
            .assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("${track.title}的更多操作").assertIsDisplayed()
            .assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
    }
}
