package com.pickaudio.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pickaudio.data.model.Platform
import com.pickaudio.data.model.SearchSongItem

class SearchStateManager {
    var query by mutableStateOf("")
    var selectedPlatform by mutableStateOf(Platform.ALL)
    var isSearching by mutableStateOf(false)
    var searchResults by mutableStateOf<List<SearchSongItem>>(emptyList())

    fun clear() {
        query = ""
        searchResults = emptyList()
        isSearching = false
    }
}
