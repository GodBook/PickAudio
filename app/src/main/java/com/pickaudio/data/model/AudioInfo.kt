package com.pickaudio.data.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Requested quality, a source's capability, and detected audio properties are separate facts. */
data class AudioInfo(
    val extension: String, val mimeType: String, val lossless: Boolean?, val bitDepth: Int? = null,
    val sampleRate: Int? = null, val channels: Int? = null, val bitrate: Long? = null,
    val codec: String? = null, val confidence: String = "HEADER"
) {
    val label: String get() = buildList {
        add(codec ?: extension.uppercase())
        bitDepth?.let { add("${it}bit") }
        sampleRate?.let { add("${it / 1000.0} kHz") }
        if (lossless == false) bitrate?.let { add("${it / 1000} kbps") }
        if (codec == null || lossless == null) add("编码未验证")
    }.joinToString(" · ")

    fun qualityFailure(requested: String?): String? = when {
        requested?.startsWith("flac") == true && codec != "FLAC" -> "实际 ${label} 未达到所选 FLAC 规格"
        requested == "flac24bit" && (bitDepth ?: 0) < 24 -> "实际 ${label} 未验证达到 24bit"
        else -> null
    }
    fun qualityNotice(requested: String?): String? {
        val requestedBitrate = when (requested) { "320k" -> 320000; "128k" -> 128000; else -> return null }
        return if (lossless == false && bitrate != null && bitrate < requestedBitrate * 0.8)
            "实际码率低于所选 ${requestedBitrate / 1000}K" else null
    }
    fun encode(): String = JsonObject().apply {
        addProperty("extension", extension); addProperty("mimeType", mimeType); addProperty("lossless", lossless)
        addProperty("bitDepth", bitDepth); addProperty("sampleRate", sampleRate); addProperty("channels", channels)
        addProperty("bitrate", bitrate); addProperty("codec", codec); addProperty("confidence", confidence)
    }.toString()
    companion object {
        fun decode(value: String?): AudioInfo? = runCatching {
            val json = JsonParser.parseString(requireNotNull(value)).asJsonObject
            fun int(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asInt?.takeIf { it > 0 }
            val codec = json.get("codec")?.takeUnless { it.isJsonNull }?.asString
            AudioInfo(json.get("extension").asString, json.get("mimeType").asString,
                json.get("lossless")?.takeUnless { it.isJsonNull }?.asBoolean, int("bitDepth"),
                int("sampleRate"), int("channels"), json.get("bitrate")?.takeUnless { it.isJsonNull }?.asLong,
                codec, json.get("confidence")?.asString ?: "UNVERIFIED")
        }.getOrNull()
        fun fromMime(mime: String, header: AudioInfo? = null, bitDepth: Int? = header?.bitDepth,
            sampleRate: Int? = header?.sampleRate, channels: Int? = header?.channels, bitrate: Long? = header?.bitrate): AudioInfo {
            val codec = when (mime) {
                "audio/flac" -> "FLAC"; "audio/alac" -> "ALAC"; "audio/raw" -> "PCM"
                "audio/mpeg" -> "MP3"; "audio/mp4a-latm", "audio/aac" -> "AAC"
                "audio/opus" -> "OPUS"; "audio/vorbis" -> "VORBIS"
                "audio/g711-alaw" -> "A-LAW"; "audio/g711-mlaw" -> "MU-LAW"
                else -> header?.codec
            }
            val lossless = when (codec) {
                "FLAC", "ALAC", "PCM", "PCM FLOAT" -> true
                "MP3", "AAC", "OPUS", "VORBIS", "ADPCM", "A-LAW", "MU-LAW" -> false
                else -> null
            }
            val extension = header?.extension ?: when (codec) {
                "FLAC" -> "flac"; "ALAC", "AAC" -> "m4a"; "PCM" -> "wav"; "MP3" -> "mp3"
                "OPUS", "VORBIS" -> "ogg"; else -> "audio"
            }
            // A compressed WAV's container must not turn its actual codec into PCM.
            return AudioInfo(extension, header?.mimeType ?: mime, if (header?.extension == "wav") header.lossless else lossless,
                bitDepth?.takeIf { it > 0 }, sampleRate?.takeIf { it > 0 }, channels?.takeIf { it > 0 },
                bitrate?.takeIf { it > 0 }, if (header?.extension == "wav") header.codec ?: codec else codec, "DECODER_FORMAT")
        }
    }
}
