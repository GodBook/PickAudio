package com.pickaudio.data.model

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUri: String? = null,
    val trackNumber: Int = 0,
    val localUri: String? = null,
    val isAvailable: Boolean = true,
    val isFavorite: Boolean = false,
    val platform: String? = null,
    val platformSongId: String? = null
)

enum class PlaybackMode(val displayName: String) {
    SEQUENTIAL("顺序播放"),
    LIST_LOOP("列表循环"),
    SINGLE_LOOP("单曲循环"),
    SHUFFLE("随机播放")
}

enum class Quality(val value: String, val label: String) {
    Q128K("128k", "标准 128K"),
    Q320K("320k", "高品 320K"),
    FLAC("flac", "无损 FLAC"),
    FLAC24BIT("flac24bit", "Hi-Res 24bit");

    companion object {
        fun fromValue(v: String): Quality {
            return entries.find { it.value.equals(v, ignoreCase = true) } ?: Q128K
        }
    }
}

enum class Platform(val id: String, val displayName: String) {
    ALL("all", "全部"),
    NETEASE("wy", "网易云"),
    QQ("tx", "QQ 音乐");

    companion object {
        fun fromId(id: String): Platform {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: ALL
        }
    }
}

data class SearchSongItem(
    val platform: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String? = null,
    val availableQualities: List<String> = listOf("128k", "320k", "flac"),
    val isDownloaded: Boolean = false,
    val isFavorite: Boolean = false
)

enum class DownloadStatus(val label: String) {
    PENDING("等待中"),
    RESOLVING("解析中"),
    DOWNLOADING("下载中"),
    VERIFYING("校验中"),
    PUBLISHING("发布中"),
    COMPLETED("已完成"),
    PAUSED("已暂停"),
    FAILED("失败"),
    CANCELLED("已取消")
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}
