package com.pickaudio.download

typealias DetectedAudioFormat = com.pickaudio.data.model.AudioInfo

object AudioFormatProbe {
    fun detect(bytes: ByteArray): DetectedAudioFormat? {
        fun magic(offset: Int, value: String) = bytes.size >= offset + value.length &&
            value.indices.all { (bytes[offset + it].toInt() and 255) == value[it].code }
        if (magic(0, "fLaC")) {
            if (bytes.size < 42 || (bytes[4].toInt() and 127) != 0) return null
            val depth = (((bytes[20].toInt() and 1) shl 4) or ((bytes[21].toInt() and 255) shr 4)) + 1
            val rate = ((bytes[18].toInt() and 255) shl 12) or ((bytes[19].toInt() and 255) shl 4) or ((bytes[20].toInt() and 240) shr 4)
            val channels = ((bytes[20].toInt() and 14) shr 1) + 1
            return DetectedAudioFormat("flac", "audio/flac", true, depth, rate.takeIf { it > 0 }, channels, codec = "FLAC")
        }
        if (magic(0, "RIFF") && magic(8, "WAVE")) {
            fun number(offset: Int, count: Int): Long = (0 until count).fold(0L) { value, index ->
                value or ((bytes[offset + index].toLong() and 255) shl (8 * index))
            }
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val size = number(offset + 4, 4)
                if (magic(offset, "fmt ") && size >= 16 && offset + 24 <= bytes.size) {
                    var code = number(offset + 8, 2).toInt()
                    if (code == 65534 && size >= 40 && offset + 48 <= bytes.size) code = number(offset + 32, 2).toInt()
                    val codec = when (code) { 1 -> "PCM"; 3 -> "PCM FLOAT"; 2, 17 -> "ADPCM"; 6 -> "A-LAW"; 7 -> "MU-LAW"; else -> null }
                    return DetectedAudioFormat("wav", "audio/wav", codec?.let { it in setOf("PCM", "PCM FLOAT") },
                        number(offset + 22, 2).toInt().takeIf { it > 0 }, number(offset + 12, 4).toInt().takeIf { it > 0 },
                        number(offset + 10, 2).toInt().takeIf { it > 0 }, number(offset + 16, 4) * 8, codec)
                }
                val next = offset.toLong() + 8 + size + size % 2
                if (next > bytes.size || next <= offset) break
                offset = next.toInt()
            }
            return DetectedAudioFormat("wav", "audio/wav", null)
        }
        if (magic(0, "OggS")) {
            val text = String(bytes, Charsets.ISO_8859_1)
            val codec = when { text.contains("OpusHead") -> "OPUS"; text.contains("vorbis") -> "VORBIS"; else -> null }
            return DetectedAudioFormat("ogg", "audio/ogg", codec?.let { false }, codec = codec)
        }
        if (magic(4, "ftyp")) return DetectedAudioFormat("m4a", "audio/mp4", null)
        if (magic(0, "ID3")) return DetectedAudioFormat("mp3", "audio/mpeg", false, codec = "MP3")
        if (bytes.size >= 4 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 224) == 224) {
            val second = bytes[1].toInt() and 255
            if ((second and 246) == 240) return DetectedAudioFormat("aac", "audio/aac", false, codec = "AAC")
            if ((second and 6) != 0 && (bytes[2].toInt() and 240) != 240) return DetectedAudioFormat("mp3", "audio/mpeg", false, codec = "MP3")
        }
        return null
    }

    fun requireRequestedQuality(requested: String, format: DetectedAudioFormat) {
        require(format.qualityFailure(requested) == null) { format.qualityFailure(requested).orEmpty() + "，请更换音质或音源" }
    }
}
