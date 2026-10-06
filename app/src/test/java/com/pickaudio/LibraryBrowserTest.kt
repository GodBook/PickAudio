package com.pickaudio

import com.pickaudio.data.model.Track
import com.pickaudio.ui.screens.*
import org.junit.Assert.*
import org.junit.Test

class LibraryBrowserTest {
    @Test fun sameNamedFoldersAndAlbumsKeepDistinctIdentities() {
        val first = Track("one", "歌曲一", "歌手甲", "同名专辑", 1000, folderName = "音乐", folderId = "tree:first")
        val second = first.copy(id = "two", artist = "歌手乙", folderId = "tree:second")
        assertEquals(2, buildLibraryView(listOf(first, second), LibraryQuery(view = "文件夹"), emptyList()).groups.size)
        assertEquals(2, buildLibraryView(listOf(first, second), LibraryQuery(view = "专辑"), emptyList()).groups.size)
    }
    @Test fun missingFileFilterAndRecentOrderUseProductionProjection() {
        val first = Track("one", "文件已失效", "", "", 1000, repairReason = "需要授权")
        val second = first.copy(id = "two", title = "正常文件", repairReason = null)
        assertEquals(listOf("one"), buildLibraryView(listOf(first, second), LibraryQuery(filter = "待修复"), emptyList()).tracks.map { it.id })
        assertEquals(listOf("two", "one"), buildLibraryView(listOf(first, second), LibraryQuery(view = "最近播放"), listOf("two", "one")).tracks.map { it.id })
    }
}
