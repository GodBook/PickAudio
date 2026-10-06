package com.pickaudio

import com.pickaudio.data.model.*
import com.pickaudio.ui.screens.SearchStateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchStateManagerTest {
    @Test fun duplicateFullPageStopsAndSwitchingSourceKeepsLoadedResults() = runTest {
        var requests = 0
        val manager = SearchStateManager(this) { platform, _, _ -> requests++; (1..20).map { song(platform, it.toString()) } }
        manager.submit("歌曲"); advanceUntilIdle()
        manager.loadMore("wy"); advanceUntilIdle()
        assertFalse(manager.platformStates.getValue("wy").hasMore)
        assertEquals(20, manager.platformStates.getValue("wy").items.size)
        manager.selectPlatform(Platform.QQ); advanceUntilIdle()
        assertEquals(3, requests)
        assertEquals(20, manager.platformStates.getValue("tx").items.size)
    }
    private fun song(platform: String, id: String) = SearchSongItem(platform, id, "歌曲$id", "歌手", "专辑", 180000)
    @Test fun failedPlatformDoesNotHideSuccessfulResults() = runTest {
        val manager = SearchStateManager(this) { platform, _, _ -> if (platform == "tx") error("服务错误") else listOf(song(platform, "1")) }
        manager.submit("歌曲"); advanceUntilIdle()
        assertEquals(1, manager.searchResults.size)
        assertNotNull(manager.platformStates["tx"]!!.error)
        assertNull(manager.platformStates["wy"]!!.error)
    }
    @Test fun pagesAreIndependentAndDeduplicated() = runTest {
        val pages = mutableListOf<Pair<String, Int>>()
        val manager = SearchStateManager(this) { platform, _, page ->
            pages.add(platform to page)
            if (page == 1) (1..20).map { song(platform, it.toString()) } else listOf(song(platform, "20"), song(platform, "21"))
        }
        manager.submit("歌曲"); advanceUntilIdle()
        manager.loadMore("wy"); advanceUntilIdle()
        assertEquals(21, manager.platformStates["wy"]!!.items.size)
        assertEquals(20, manager.platformStates["tx"]!!.items.size)
        assertFalse(manager.platformStates["wy"]!!.hasMore)
        assertEquals(listOf("wy" to 1, "tx" to 1, "wy" to 2), pages)
    }
    @Test fun staleRequestsCannotOverwriteNewSearch() = runTest {
        val manager = SearchStateManager(this) { platform, query, _ ->
            withContext(NonCancellable) { delay(if (query == "旧搜索") 200 else 10) }
            listOf(song(platform, query))
        }
        manager.selectedPlatform = Platform.NETEASE
        manager.submit("旧搜索"); runCurrent()
        manager.submit("新搜索"); advanceUntilIdle()
        assertEquals("新搜索", manager.searchResults.single().songId)
    }
    @Test fun clearingWhileLoadingKeepsThePageEmpty() = runTest {
        val manager = SearchStateManager(this) { platform, _, _ -> withContext(NonCancellable) { delay(100) }; listOf(song(platform, "1")) }
        manager.submit("歌曲"); runCurrent(); manager.clear(); advanceUntilIdle()
        assertTrue(manager.searchResults.isEmpty()); assertFalse(manager.isSearching); assertEquals("", manager.submittedQuery)
    }
}
