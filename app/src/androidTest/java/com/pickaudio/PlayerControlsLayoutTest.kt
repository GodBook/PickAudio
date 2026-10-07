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
import com.pickaudio.data.model.PlaybackPhase
import com.pickaudio.data.model.Track
import com.pickaudio.ui.screens.PlayerScreen
import com.pickaudio.ui.theme.PickAudioTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerControlsLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PickAudioApplication
    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { app.playbackCoordinator.clearQueue() }
        app.playbackCoordinator.flushPersistence()
        app.database.trackDao().deleteById("player_controls_layout")
        Unit
    }

    @Test fun playbackControlsStayVisibleWithLargeTextAndAnErrorInACompactScreen() {
        val song = Track("player_controls_layout", "播放器小屏及错误布局验证", "测试歌手", "包含较长名称的测试专辑", 180000)
        compose.runOnIdle {
            app.playbackCoordinator.setQueueAndPlay(listOf(song))
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    PickAudioTheme {
                        Box(Modifier.width(320.dp).height(600.dp)) {
                            PlayerScreen(app.playbackCoordinator, {}, {}, false)
                        }
                    }
                }
            }
        }
        compose.waitUntil(5000) { app.playbackCoordinator.uiState.value.phase == PlaybackPhase.ERROR }
        listOf("上一首", "下一首", "播放", "播放队列").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed()
        }
    }
}
