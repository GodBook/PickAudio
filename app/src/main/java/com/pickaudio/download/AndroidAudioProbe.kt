package com.pickaudio.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.pickaudio.data.model.AudioInfo
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer

data class InspectedAudio(val info: AudioInfo, val durationMs: Long, val title: String?, val artist: String?,
    val album: String?, val artwork: ByteArray? = null)

object AndroidAudioProbe {
    suspend fun inspect(context: Context, uri: Uri, validatePackets: Boolean = false, artwork: Boolean = false): InspectedAudio =
        withContext(Dispatchers.IO) {
            val header = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(65536) } ?: error("无法读取音频文件")
            val container = AudioFormatProbe.detect(header)
            val retriever = MediaMetadataRetriever()
            val extractor = MediaExtractor()
            try {
                retriever.setDataSource(context, uri)
                extractor.setDataSource(context, uri, emptyMap())
                val audioIndex = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("文件没有可识别的音频轨道")
                val format = extractor.getTrackFormat(audioIndex)
                fun int(key: String) = if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull()?.takeIf { it > 0 } else null
                val bits = container?.bitDepth ?: int("bits-per-sample") ?: int(MediaFormat.KEY_PCM_ENCODING)?.let {
                    when (it) { 2 -> 16; 3 -> 8; 4 -> 32; 21 -> 24; 22 -> 32; else -> null }
                }
                val info = AudioInfo.fromMime(format.getString(MediaFormat.KEY_MIME).orEmpty(), container, bits,
                    int(MediaFormat.KEY_SAMPLE_RATE), int(MediaFormat.KEY_CHANNEL_COUNT),
                    int(MediaFormat.KEY_BIT_RATE)?.toLong()
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull())
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
                require(duration > 0) { "文件没有有效音频时长" }
                if (validatePackets) {
                    if (container?.extension == "wav" && header.size >= 12 && uri.scheme == "file") {
                        val declared = (0 until 4).fold(0L) { value, index -> value or ((header[4 + index].toLong() and 255) shl (8 * index)) }
                        require(declared + 8 <= File(uri.path.orEmpty()).length()) { "WAV 文件内容被截断" }
                    }
                    extractor.selectTrack(audioIndex)
                    val capacity = (int(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 1024 * 1024).coerceIn(65536, 4 * 1024 * 1024)
                    val buffer = ByteBuffer.allocate(capacity)
                    var packets = 0L
                    var previous = 0L
                    var lastTime = 0L
                    var largestGap = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        buffer.clear()
                        val bytes = extractor.readSampleData(buffer, 0)
                        if (bytes < 0) break
                        require(bytes > 0 && bytes <= capacity) { "音频数据帧无效" }
                        val time = extractor.sampleTime.coerceAtLeast(0)
                        largestGap = maxOf(largestGap, time - previous)
                        previous = time; lastTime = time; packets++
                        if (!extractor.advance()) break
                    }
                    require(packets > 0) { "音频文件没有完整数据帧" }
                    if (duration > 2000 && lastTime > 0)
                        require(lastTime / 1000 + maxOf(1000, largestGap / 500) >= duration) { "音频末尾不完整，请重新下载" }
                }
                InspectedAudio(info.copy(confidence = if (validatePackets) "PACKETS" else "DECODER_FORMAT"), duration,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    if (artwork) retriever.embeddedPicture?.takeIf { it.size <= 2 * 1024 * 1024 } else null)
            } finally {
                extractor.release()
                retriever.release()
            }
        }
}
