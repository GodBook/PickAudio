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
import com.pickaudio.data.model.Platform
import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.ui.screens.SearchStateManager
import com.pickaudio.ui.theme.PickAudioTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
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

    @Test fun searchAuditionBackKeepsResultsPlatformAndScrollPosition() = runBlocking {
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val manager = SearchStateManager(requestScope) { platform, _, _ ->
            (1..20).map { SearchSongItem(platform, "navigation_$it", "搜索返回验证 $it", "验证歌手", "", 180000,
                metadataJson = """{"songid":123,"strMediaMid":"fixture"}""") }
        }
        val previous = app.database.sourceDao().getSelectionForPlatform("tx")
        val source = app.sourceManager.importSourceFromCode("""
            lx.on(lx.EVENT_NAMES.request, () => { throw new Error('返回专项无需网络'); });
            lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{tx:{name:'返回专项',actions:['musicUrl'],qualitys:['128k','320k','flac','flac24bit']}}});
        """.trimIndent())
        try {
            app.sourceManager.selectSourceForPlatform("tx", source.id)
            withContext(Dispatchers.Main) { manager.selectPlatform(Platform.QQ) }
            compose.runOnIdle { compose.activity.setContent { PickAudioTheme { MainApp(app, manager) } } }
            compose.onNodeWithText("搜索").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("保留搜索")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(5000) { manager.searchResults.size == 20 }
            compose.onNodeWithTag("search_results").performScrollToNode(hasText("搜索返回验证 15"))
            val before = compose.onNodeWithTag("search_results").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            val song = hasText("搜索返回验证 15") and hasAnyAncestor(hasTestTag("search_results"))
            compose.onNode(song).performClick()
            compose.onNodeWithContentDescription("收起播放器").assertIsDisplayed()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithContentDescription("收起播放器").assertDoesNotExist()
            compose.onNodeWithTag("search_results").assertIsDisplayed()
            compose.onNode(song).assertIsDisplayed()
            assertEquals("保留搜索", manager.submittedQuery)
            assertEquals(Platform.QQ, manager.selectedPlatform)
            assertEquals(before, compose.onNodeWithTag("search_results").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value())
            compose.onNode(song).performClick()
            compose.onNodeWithContentDescription("收起播放器").performClick()
            compose.onNodeWithTag("search_results").assertIsDisplayed()
        } finally {
            requestScope.cancel()
            withContext(Dispatchers.Main) { app.playbackCoordinator.clearQueue() }
            app.playbackCoordinator.flushPersistence()
            app.database.trackDao().getAllTracks().first().filter { it.id.startsWith("online_tx_navigation_") }.forEach {
                app.database.trackDao().deleteById(it.id)
            }
            app.sourceManager.selectSourceForPlatform("tx", previous?.sourceId)
            app.sourceManager.deleteSource(source.id)
        }
        Unit
    }

    @Test fun settingsCategoriesStayReachableOnSmallScreensAndBackReturnsHome() {
        compose.runOnIdle { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                PickAudioTheme { Box(Modifier.width(320.dp).height(600.dp)) { MainApp(app) } }
            }
        } }
        compose.onNodeWithContentDescription("设置").performClick()
        for ((category, control) in listOf("APPEARANCE" to "显示模式", "MUSIC" to "默认在线播放音质",
            "LYRICS" to "显示歌词翻译", "STORAGE" to "音频缓存容量", "BACKUP" to "导出备份", "ABOUT" to "检查更新")) {
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_category_$category"))
            compose.onNodeWithTag("settings_category_$category").performClick()
            compose.onNodeWithText(control).performScrollTo().assertIsDisplayed()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("settings_category_APPEARANCE").assertIsDisplayed()
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("导入音乐").assertIsDisplayed()
    }
}
