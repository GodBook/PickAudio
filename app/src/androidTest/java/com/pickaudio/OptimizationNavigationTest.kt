package com.pickaudio

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.db.*
import com.pickaudio.data.model.Track
import com.pickaudio.ui.theme.PickAudioTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OptimizationNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PickAudioApplication
    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { app.playbackCoordinator.clearQueue() }
        app.playbackCoordinator.flushPersistence()
        app.database.trackDao().deleteById("optimization_navigation")
        Unit
    }
    @Test fun backCollapsesPlayerBeforeLeavingUnderlyingSelection() {
        val track = Track("optimization_navigation", "返回层级专项歌曲", "验证", "", 1000)
        runBlocking { app.database.trackDao().insertOrUpdate(TrackEntity(track.id, track.title, track.artist, "", 1000, null, isInLibrary = true)) }
        compose.waitUntil(5000) { compose.onAllNodesWithText(track.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("多选").performClick()
        compose.runOnIdle { app.playbackCoordinator.setQueueAndPlay(listOf(track)) }
        compose.waitUntil(5000) { compose.onAllNodesWithText(track.title).fetchSemanticsNodes().size > 1 }
        val matches = compose.onAllNodesWithText(track.title)
        matches[matches.fetchSemanticsNodes().lastIndex].performClick()
        compose.onNodeWithContentDescription("收起播放器").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("收起播放器").assertDoesNotExist()
        compose.onNodeWithText("完成").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("多选").assertIsDisplayed()
    }
    @Test fun essentialImportPathsRemainReachableAtDoubleFontScale() {
        compose.runOnIdle { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                PickAudioTheme { Box(Modifier.width(320.dp).height(600.dp)) { MainApp(app) } }
            }
        } }
        compose.onNodeWithText("导入音乐").performClick()
        compose.onNodeWithText("选择音频文件").assertIsDisplayed()
        compose.onNodeWithText("选择文件夹").assertIsDisplayed()
    }
}
