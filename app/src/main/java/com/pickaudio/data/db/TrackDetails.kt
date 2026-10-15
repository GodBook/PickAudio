package com.pickaudio.data.db

import androidx.room.Embedded
import androidx.room.Relation

/** Scoped song projections avoid constructing the whole library for one playlist. */
data class TrackDetails(
    @Embedded val track: TrackEntity,
    @Relation(parentColumn = "id", entityColumn = "trackId") val assets: List<LocalAssetEntity>,
    @Relation(parentColumn = "id", entityColumn = "trackId") val refs: List<OnlineRefEntity>,
    @Relation(parentColumn = "id", entityColumn = "trackId") val favorites: List<FavoriteEntity>
)
data class PlaylistSummaryRow(val id: String, val songCount: Int, val coverUri: String?)
data class DownloadStateCount(val status: String, val count: Int)
data class DownloadNotificationRow(val id: String, val title: String)
