package com.pickaudio.data.repository

import com.pickaudio.data.db.FavoriteEntity
import com.pickaudio.data.db.PlaylistTrackEntity

data class AddTracksReport(val added: Int, val existing: Int, val duplicates: Int) {
    val summary get() = "新增 $added 首 · 已有 $existing 首 · 重复 $duplicates 首"
}
data class RemovedTracks(val playlistId: String, val originalOrder: List<String>,
    val members: List<PlaylistTrackEntity> = emptyList(), val favorites: List<FavoriteEntity> = emptyList())

/** Restore removed rows before their surviving original successor; later additions keep their order. */
internal fun restoreMemberOrder(current: List<String>, original: List<String>, removed: Set<String>): List<String> {
    val currentSet = current.toSet()
    val pending = mutableMapOf<String?, MutableList<String>>()
    var next: String? = null
    for (id in original.asReversed()) {
        if (id in currentSet) next = id
        else if (id in removed) pending.getOrPut(next) { mutableListOf() }.add(id)
    }
    return buildList {
        current.forEach { id -> addAll(pending[id].orEmpty().asReversed()); add(id) }
        addAll(pending[null].orEmpty().asReversed())
    }
}
