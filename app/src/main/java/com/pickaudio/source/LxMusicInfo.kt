package com.pickaudio.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Keep the original platform fields and supply the names used by LX mobile sources. */
internal fun lxMusicInfo(platform: String, songId: String, metadata: String, title: String?, artist: String?): JsonObject {
    val info = runCatching { JsonParser.parseString(metadata).asJsonObject.deepCopy() }.getOrElse { JsonObject() }
    fun text(key: String): String? = info.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    fun put(key: String, value: String?) {
        if (text(key) == null && !value.isNullOrBlank()) info.addProperty(key, value)
    }
    info.addProperty("source", platform)
    put("name", title ?: text("songname"))
    put("singer", artist)
    put("songmid", songId)
    val album = info.get("album")?.takeIf { it.isJsonObject }?.asJsonObject
        ?: info.get("al")?.takeIf { it.isJsonObject }?.asJsonObject
    put("albumName", text("albumname") ?: album?.get("name")?.asString)
    if (platform == "tx") {
        val file = info.get("file")?.takeIf { it.isJsonObject }?.asJsonObject
        put("strMediaMid", text("media_mid") ?: file?.get("media_mid")?.asString ?: songId)
        put("albumId", text("albummid") ?: album?.get("mid")?.asString)
        put("albumMid", text("albumId"))
        if (!info.has("songId")) (info.get("songid") ?: info.get("id"))?.let { info.add("songId", it) }
        if (!info.has("id")) (info.get("songId") ?: info.get("songmid"))?.let { info.add("id", it) }
        if (!info.has("_types")) {
            val types = JsonObject()
            val typeList = com.google.gson.JsonArray()
            listOf("128k" to ("size128" to "size_128mp3"), "320k" to ("size320" to "size_320mp3"),
                "flac" to ("sizeflac" to "size_flac"), "flac24bit" to ("sizehires" to "size_hires")).forEach { (quality, keys) ->
                val bytes = runCatching { (info.get(keys.first) ?: file?.get(keys.second))?.asLong ?: 0 }.getOrDefault(0)
                if (bytes > 0) {
                    val detail = JsonObject().apply { addProperty("size", "%.2f MB".format(java.util.Locale.ROOT, bytes / 1048576.0)) }
                    types.add(quality, detail)
                    typeList.add(detail.deepCopy().apply { addProperty("type", quality) })
                }
            }
            info.add("_types", types)
            if (!info.has("types")) info.add("types", typeList)
        }
    } else if (!info.has("id")) {
        songId.toLongOrNull()?.let { info.addProperty("id", it) } ?: info.addProperty("id", songId)
    }
    if (!info.has("typeUrl")) info.add("typeUrl", JsonObject())
    return info
}
