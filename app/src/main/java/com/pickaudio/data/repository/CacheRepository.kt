package com.pickaudio.data.repository

import android.content.Context
import coil.imageLoader
import com.pickaudio.data.db.PickAudioDatabase
import com.pickaudio.download.DownloadCoordinator
import com.pickaudio.playback.AudioCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CacheUsage(val audio: Long = 0, val covers: Long = 0, val partials: Long = 0, val unusedPartials: Long = 0)

@OptIn(coil.annotation.ExperimentalCoilApi::class)
class CacheRepository(private val context: Context, private val database: PickAudioDatabase, private val downloads: DownloadCoordinator) {
    private fun size(file: File): Long = if (file.isFile) file.length() else file.listFiles().orEmpty().sumOf(::size)
    private suspend fun unusedParts(): List<File> {
        val tasks = database.downloadDao().getAllTasksSync()
        val protectedNames = tasks.filter { it.status != "COMPLETED" }.map { "temp_${it.id}.part" }.toSet()
        return downloads.partialDirectory.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("temp_") && it.name.endsWith(".part") && it.name !in protectedNames }
    }
    suspend fun usage(): CacheUsage = withContext(Dispatchers.IO) {
        CacheUsage(AudioCacheManager.size(context), context.imageLoader.diskCache?.size ?: 0,
            size(downloads.partialDirectory), unusedParts().sumOf { it.length() })
    }
    suspend fun clearCovers() = withContext(Dispatchers.IO) {
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()
    }
    suspend fun clearUnusedParts() = withContext(Dispatchers.IO) { unusedParts().forEach { it.delete() } }
}
