package com.pickaudio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.model.SearchSongItem
import com.pickaudio.data.model.Track
import com.pickaudio.data.model.PlaybackPhase
import com.pickaudio.data.db.TrackEntity
import com.pickaudio.data.db.LocalAssetEntity
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.ui.screens.SearchStateManager
import com.pickaudio.ui.theme.PickAudioTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PickAudioApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var oldPreferences: Map<String, String>
    private var playlistId: String? = null

    @Before fun setup() = runBlocking {
        oldPreferences = app.userPreferences.exportSettings()
        app.userPreferences.setDefaultOnlineQuality(com.pickaudio.data.model.Quality.Q128K)
        app.userPreferences.setDefaultDownloadQuality(com.pickaudio.data.model.Quality.Q320K)
        app.sourceManager.ensureBuiltinSources()
        Unit
    }
    @After fun cleanup() = runBlocking {
        scope.cancel()
        playlistId?.let { app.playlistRepository.deletePlaylist(it) }
        app.userPreferences.restoreSettings(oldPreferences)
        withContext(Dispatchers.Main) { app.playbackCoordinator.clearQueue() }
        app.libraryRepository.deleteTrack("ui_broken_file")
        Unit
    }
    private fun start(manager: SearchStateManager = SearchStateManager(scope) { _, _, _ -> emptyList() }) {
        compose.setContent { PickAudioTheme { MainApp(app, manager) } }
    }

    @Test fun importMenuHasAllThreeNamedPaths() {
        start()
        compose.onNodeWithText("导入音乐").performClick()
        compose.onNode(hasText("扫描手机歌曲") and hasAnyAncestor(isPopup())).assertIsDisplayed()
        compose.onNodeWithText("选择音频文件").assertIsDisplayed()
        compose.onNodeWithText("选择文件夹").assertIsDisplayed()
    }

    @Test fun listeningStaysOutOfLibraryUntilPlayerAddButtonIsClicked() {
        val item = SearchSongItem("tx", "ui_library_membership", "QQ 曲库加入验证", "测试歌手", "测试专辑", 180000,
            metadataJson = """{"songid":123,"strMediaMid":"fixture_media"}""")
        val previous = runBlocking { app.database.sourceDao().getSelectionForPlatform("tx") }
        val source = runBlocking { app.sourceManager.importSourceFromCode("""
            lx.on(lx.EVENT_NAMES.request, () => new Promise(() => {}));
            lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{tx:{name:'曲库UI专项',actions:['musicUrl'],qualitys:['128k']}}});
        """.trimIndent()) }
        try {
            runBlocking { app.sourceManager.selectSourceForPlatform("tx", source.id) }
            val manager = SearchStateManager(scope) { platform, _, _ -> if (platform == "tx") listOf(item) else emptyList() }
            start(manager)
            compose.onNodeWithText("搜索").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("曲库验证")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(5000) { manager.platformStates["tx"]?.items?.isNotEmpty() == true }
            compose.onNodeWithText(item.title).performClick()
            compose.waitUntil(5000) { app.playbackCoordinator.currentTrack.value?.id == "online_tx_${item.songId}" }
            runBlocking { app.playbackCoordinator.flushPersistence() }
            assertFalse(runBlocking { app.libraryRepository.libraryTrackIds.first() }.contains("online_tx_${item.songId}"))
            assertFalse(runBlocking { app.libraryRepository.getAllTracks().first() }.any { it.id == "online_tx_${item.songId}" })
            compose.onNodeWithText("加入曲库").performClick()
            compose.waitUntil(5000) { runBlocking { app.libraryRepository.libraryTrackIds.first() }.contains("online_tx_${item.songId}") }
            compose.onNodeWithText("已在曲库").assertIsDisplayed()
            compose.onNodeWithContentDescription("收起播放器").performClick()
            compose.onNodeWithText("曲库").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText(item.title).fetchSemanticsNodes().size == 2 }
            compose.onAllNodesWithText(item.title).assertCountEquals(2)
        } finally {
            runBlocking {
                withContext(Dispatchers.Main) { app.playbackCoordinator.clearQueue() }
                app.playbackCoordinator.flushPersistence()
                app.database.trackDao().deleteById("online_tx_${item.songId}")
                app.sourceManager.selectSourceForPlatform("tx", previous?.sourceId)
                app.sourceManager.deleteSource(source.id)
            }
        }
    }

    @Test fun settingsQualityAndScanSwitchCanBeChanged() {
        start()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("标准 128K").performScrollTo().performClick()
        compose.onNodeWithText("Hi-Res 24bit").performClick()
        compose.waitUntil(5000) { runBlocking { app.userPreferences.defaultOnlineQuality.first() } == com.pickaudio.data.model.Quality.FLAC24BIT }
        val before = runBlocking { app.userPreferences.filterShortAudio.first() }
        compose.onNodeWithContentDescription("过滤 30 秒以下短音频").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { app.userPreferences.filterShortAudio.first() } != before }
    }

    @Test fun playlistNamesWithSlashesOpenCorrectly() {
        start()
        compose.onNodeWithText("歌单").performClick()
        compose.onNodeWithText("新建").performClick()
        compose.onNode(hasSetTextAction() and hasText("歌单名称")).performTextInput("UX 验证 / 歌单")
        compose.onNodeWithText("创建").performClick()
        compose.waitUntil(5000) { runBlocking { app.playlistRepository.getAllPlaylists().first() }.any { it.name == "UX 验证 / 歌单" } }
        playlistId = runBlocking { app.playlistRepository.getAllPlaylists().first() }.first { it.name == "UX 验证 / 歌单" }.id
        compose.onNodeWithText("UX 验证 / 歌单").performScrollTo().performClick()
        compose.onNodeWithText("歌单暂无歌曲").assertExists()
    }

    @Test fun failedSearchPlatformOffersRetryWhileOtherResultsRemain() {
        val manager = SearchStateManager(scope) { platform, _, _ ->
            if (platform == "tx") error("测试网络失败") else listOf(SearchSongItem(platform, "ui_1", "可用歌曲", "歌手", "专辑", 180000))
        }
        start(manager)
        compose.onNodeWithText("搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("歌曲")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5000) { manager.platformStates["tx"]?.error != null }
        compose.onNodeWithText("可用歌曲").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("重试此平台"))
        compose.onNodeWithText("重试此平台").assertIsDisplayed()
        compose.onNodeWithContentDescription("下载管理").assertExists()
    }

    @Test fun searchLoadMoreAndBatchPlaylistCreationWork() {
        val manager = SearchStateManager(scope) { platform, _, page ->
            (1..if (page == 1) 20 else 1).map { SearchSongItem(platform, "ui_${page}_$it", "UI 歌曲 $page $it", "歌手", "专辑", 180000) }
        }
        start(manager)
        compose.onNodeWithText("搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("音乐")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5000) { manager.platformStates["wy"]?.items?.size == 20 }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("加载更多网易云结果"))
        compose.onNodeWithText("加载更多网易云结果").performClick()
        compose.waitUntil(5000) { manager.platformStates["wy"]?.items?.size == 21 }
        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithText("全选 (0)").performClick()
        compose.onNodeWithText("加入歌单").performClick()
        compose.onNode(hasSetTextAction() and hasText("新建歌单名称")).performTextInput("UX 批量验证")
        compose.onNodeWithText("新建并加入").performClick()
        compose.waitUntil(5000) { runBlocking { app.playlistRepository.getAllPlaylists().first() }.any { it.name == "UX 批量验证" } }
        playlistId = runBlocking { app.playlistRepository.getAllPlaylists().first() }.first { it.name == "UX 批量验证" }.id
        compose.waitUntil(10000) { runBlocking { app.playlistRepository.getTracksForPlaylist(playlistId!!).first().size } == 41 }
        assertEquals(41, runBlocking { app.playlistRepository.getTracksForPlaylist(playlistId!!).first().size })
        runBlocking { app.database.trackDao().getAllTracks().first().filter { it.id.contains("ui_") }.forEach { app.libraryRepository.deleteTrack(it.id) } }
    }

    @Test fun sourceScreenShowsCapabilitiesAndTestingControls() {
        start()
        compose.onNodeWithText("搜索").performClick()
        compose.onNodeWithContentDescription("配置音乐源").performClick()
        compose.onNodeWithText("导入本地脚本").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("测试兼容性"))
        compose.onNodeWithText("测试兼容性").assertIsDisplayed()
    }

    @Test fun missingFileShowsRepairAndClearingQueueCanBeUndone() {
        val track = Track("ui_broken_file", "UX 文件失效验证", "测试歌手", "测试专辑", 120000)
        runBlocking {
            app.database.trackDao().insertOrUpdate(TrackEntity(track.id, track.title, track.artist, track.album, track.durationMs, null))
            app.database.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = track.id, uri = "file:///does-not-exist-pickaudio-test.wav", sourceType = "SAF_FILE", fileSize = 1, mimeType = "audio/wav", format = "wav"))
        }
        start()
        compose.runOnIdle { app.playbackCoordinator.setQueueAndPlay(listOf(track)) }
        compose.waitUntil(5000) { app.playbackCoordinator.uiState.value.phase == PlaybackPhase.ERROR }
        val matches = compose.onAllNodesWithText(track.title)
        matches[matches.fetchSemanticsNodes().lastIndex].performClick()
        compose.onNodeWithText("重新关联音频").assertExists()
        compose.onNodeWithText("重试").assertExists()
        compose.onNodeWithContentDescription("播放队列").assertIsDisplayed().performClick()
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithText("队列已清空").assertIsDisplayed()
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(5000) { app.playbackCoordinator.queue.value.singleOrNull()?.id == track.id }
    }
}
