package com.pickaudio.download

data class DetectedAudioFormat(val extension: String, val mimeType: String, val lossless: Boolean, val bitDepth: Int? = null) {
    val label: String get() = when {
        extension == "flac" && bitDepth != null -> "FLAC · ${bitDepth}bit"
        else -> extension.uppercase()
    }
}

object AudioFormatProbe {
    fun detect(bytes: ByteArray): DetectedAudioFormat? {
        fun magic(offset: Int, value: String) = bytes.size >= offset + value.length &&
            value.indices.all { (bytes[offset + it].toInt() and 255) == value[it].code }
        if (magic(0, "fLaC")) {
            if (bytes.size < 42 || (bytes[4].toInt() and 127) != 0) return null
            val depth = (((bytes[20].toInt() and 1) shl 4) or ((bytes[21].toInt() and 255) shr 4)) + 1
            return DetectedAudioFormat("flac", "audio/flac", true, depth)
        }
        if (magic(0, "RIFF") && magic(8, "WAVE")) return DetectedAudioFormat("wav", "audio/wav", true)
        if (magic(0, "OggS")) return DetectedAudioFormat("ogg", "audio/ogg", false)
        if (magic(4, "ftyp")) return DetectedAudioFormat("m4a", "audio/mp4", false)
        if (magic(0, "ID3")) return DetectedAudioFormat("mp3", "audio/mpeg", false)
        if (bytes.size >= 4 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 224) == 224) {
            val second = bytes[1].toInt() and 255
            if ((second and 246) == 240) return DetectedAudioFormat("aac", "audio/aac", false)
            if ((second and 6) != 0 && (bytes[2].toInt() and 240) != 240) return DetectedAudioFormat("mp3", "audio/mpeg", false)
        }
        return null
    }

    fun requireRequestedQuality(requested: String, format: DetectedAudioFormat) {
        require(!requested.startsWith("flac") || format.extension == "flac") {
            "音源返回 ${format.label}，未达到所选无损音质。请改选标准或高品质后重试"
        }
        require(requested != "flac24bit" || (format.bitDepth ?: 0) >= 24) {
            "音源返回 ${format.label}，未达到 24bit。请改选 FLAC 后重试"
        }
    }
}
