package com.pickaudio.download

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/** Opaque durable publication identity and the verified contents it owns. */
internal data class PublicationTicket(val id: String, val sha256: String) {
    val pendingName: String get() = "_pickaudio_pending_$id"
    fun encode(): String = JsonObject().apply { addProperty("id", id); addProperty("sha256", sha256) }.toString()
    companion object {
        fun create(sha256: String) = PublicationTicket(UUID.randomUUID().toString(), sha256)
        fun decode(value: String?): PublicationTicket? = runCatching {
            val json = JsonParser.parseString(requireNotNull(value)).asJsonObject
            PublicationTicket(json.get("id").asString, json.get("sha256").asString).takeIf {
                it.id.matches(Regex("[a-fA-F0-9-]{36}")) && it.sha256.matches(Regex("[a-fA-F0-9]{64}"))
            }
        }.getOrNull()
    }
}

internal data class ResourceValidator(val url: String, val etag: String?, val representationUrl: String? = url) {
    fun encode(): String = JsonObject().apply {
        addProperty("url", url); addProperty("etag", etag); addProperty("representationUrl", representationUrl)
    }.toString()
    companion object {
        fun decode(value: String?): ResourceValidator? = runCatching {
            val json = JsonParser.parseString(requireNotNull(value)).asJsonObject
            ResourceValidator(json.get("url").asString,
                json.get("etag")?.takeUnless { it.isJsonNull }?.asString,
                json.get("representationUrl")?.takeUnless { it.isJsonNull }?.asString)
                .takeIf { it.url.startsWith("https://") || it.url.startsWith("http://") }
        }.getOrNull()
    }
}
