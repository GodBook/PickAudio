package com.pickaudio

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An isolated provider measures directory enumeration separately from metadata/descriptor requests. */
class OptimizationDocumentsProvider : DocumentsProvider() {
    private var songs = 0
    private var childQueries = 0
    private val directory get() = File(requireNotNull(context).cacheDir, "optimization-documents").apply { mkdirs() }
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = when (method) {
        "seed" -> {
            songs = extras?.getInt("songs") ?: 0; childQueries = 0
            val size = 44 + 88200
            val audio = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(size - 8); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(size - 44)
            }.array()
            File(directory, "audio.wav").writeBytes(audio)
            File(directory, "lyrics.lrc").writeText("[00:00.00]同目录歌词")
            Bundle()
        }
        "stats" -> Bundle().apply { putInt("childQueries", childQueries) }
        "clear" -> { directory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }; songs = 0; Bundle() }
        else -> super.call(method, arg, extras) ?: Bundle()
    }
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)
    private fun row(id: String): Array<Any> {
        val dir = id == "root"
        val lyric = id.startsWith("lyric")
        val name = if (dir) "验证目录" else "song_${id.substringAfter('_')}.${if (lyric) "lrc" else "wav"}"
        return arrayOf(id, name, if (dir) DocumentsContract.Document.MIME_TYPE_DIR else if (lyric) "text/plain" else "audio/wav",
            if (dir) 0L else File(directory, if (lyric) "lyrics.lrc" else "audio.wav").length(), 0)
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val fields = projection?.let { p -> Array(p.size) { p[it] } } ?: columns
        val values = row(documentId)
        return MatrixCursor(fields).apply { addRow(fields.map { values[columns.indexOf(it)] }) }
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        childQueries++
        val fields = projection?.let { p -> Array(p.size) { p[it] } } ?: columns
        return MatrixCursor(fields).apply {
            if (parentDocumentId == "root") repeat(songs) { index ->
                listOf("audio_$index", "lyric_$index").forEach { id ->
                    val values = row(id); addRow(fields.map { values[columns.indexOf(it)] })
                }
            }
        }
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        parentDocumentId == "root" && (documentId == "root" || documentId.startsWith("audio_") || documentId.startsWith("lyric_"))
    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection?.let { p -> Array(p.size) { p[it] } } ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    override fun openDocument(documentId: String, mode: String, signal: android.os.CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(directory, if (documentId.startsWith("lyric")) "lyrics.lrc" else "audio.wav"), ParcelFileDescriptor.MODE_READ_ONLY)
}
