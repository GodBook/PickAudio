package com.pickaudio.online

import com.pickaudio.data.model.SearchSongItem

const val SEARCH_PAGE_SIZE = 20
data class SearchPage(val items: List<SearchSongItem>, val nextPage: Int?, val total: Int? = null)
