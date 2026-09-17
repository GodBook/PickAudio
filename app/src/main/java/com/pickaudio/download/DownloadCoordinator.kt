package com.pickaudio.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pickaudio.data.db.*
import com.pickaudio.data.model.DownloadStatus
import com.pickaudio.data.model.Quality
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadCoordinator(
    private val context: Context,
    private val database: PickAudioDatabase,
    private val sourceManager: LxSourceManager
) {
    private val downloadDao = database.downloadDao()
    private val trackDao = database.trackDao()
    private val localAssetDao = database.localAssetDao()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val semaphore = Semaphore(2) // Max 2 concurrent downloads
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getAllTasks(): Flow<List<DownloadTaskEntity>> = downloadDao.getAllTasks()

    suspend fun enqueueDownload(
        trackId: String,
        title: String,
        artist: String,
        album: String,
        coverUri: String?,
        platform: String,
        platformSongId: String,
        quality: String
    ): String = withContext(Dispatchers.IO) {
        val existing = downloadDao.getTaskByPlatformSong(platform, platformSongId, quality)
        if (existing != null) {
            if (existing.status == DownloadStatus.COMPLETED.name) {
                return@withContext existing.id
            }
            if (existing.status == DownloadStatus.PAUSED.name || existing.status == DownloadStatus.FAILED.name) {
                resumeTask(existing.id)
                return@withContext existing.id
            }
            return@withContext existing.id
        }

        val taskId = UUID.randomUUID().toString()
        val task = DownloadTaskEntity(
            id = taskId,
            trackId = trackId,
            title = title,
            artist = artist,
            album = album,
            coverUri = coverUri,
            platform = platform,
            platformSongId = platformSongId,
            targetQuality = quality,
            status = DownloadStatus.PENDING.name
        )
        downloadDao.insertOrUpdate(task)

        // Make sure track exists in db
        if (trackDao.getTrackById(trackId) == null) {
            trackDao.insertOrUpdate(
                TrackEntity(
                    id = trackId,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = 0L,
                    coverUri = coverUri
                )
            )
        }

        startTaskJob(task)
        taskId
    }

    private fun startTaskJob(task: DownloadTaskEntity) {
        val job = scope.launch {
            semaphore.withPermit {
                executeDownload(task.id)
            }
        }
        activeJobs[task.id] = job
    }

    fun pauseTask(taskId: String) {
        val job = activeJobs.remove(taskId)
        job?.cancel()
        scope.launch(Dispatchers.IO) {
            downloadDao.updateStatus(taskId, DownloadStatus.PAUSED.name)
        }
    }

    fun resumeTask(taskId: String) {
        scope.launch(Dispatchers.IO) {
            val task = downloadDao.getTaskById(taskId) ?: return@launch
            downloadDao.updateStatus(taskId, DownloadStatus.PENDING.name)
            startTaskJob(task)
        }
    }

    fun cancelTask(taskId: String) {
        val job = activeJobs.remove(taskId)
        job?.cancel()
        scope.launch(Dispatchers.IO) {
            val task = downloadDao.getTaskById(taskId)
            if (task?.tempFilePath != null) {
                File(task.tempFilePath).delete()
            }
            downloadDao.deleteTask(taskId)
        }
    }

    private suspend fun executeDownload(taskId: String) = withContext(Dispatchers.IO) {
        var task = downloadDao.getTaskById(taskId) ?: return@withContext
        if (task.status == DownloadStatus.PAUSED.name || task.status == DownloadStatus.CANCELLED.name) {
            return@withContext
        }

        try {
            // 1. Resolve URL
            downloadDao.updateStatus(taskId, DownloadStatus.RESOLVING.name)
            val downloadUrl = sourceManager.resolveMusicUrl(task.platform, task.platformSongId, task.targetQuality, task.title, task.artist)
            if (downloadUrl.isBlank()) {
                throw IllegalStateException("解析下载地址为空")
            }

            // 2. Prepare temp file
            val tempDir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val tempFile = File(tempDir, "temp_${task.id}.part")
            var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L

            downloadDao.updateStatus(taskId, DownloadStatus.DOWNLOADING.name)

            val reqBuilder = Request.Builder().url(downloadUrl)
            if (downloadedBytes > 0) {
                reqBuilder.header("Range", "bytes=$downloadedBytes-")
            }

            val resp = okHttpClient.newCall(reqBuilder.build()).execute()
            if (!resp.isSuccessful && resp.code != 206) {
                // If range failed, restart from 0
                tempFile.delete()
                downloadedBytes = 0L
                val retryResp = okHttpClient.newCall(Request.Builder().url(downloadUrl).build()).execute()
                if (!retryResp.isSuccessful) {
                    throw IllegalStateException("HTTP 请求失败: ${retryResp.code}")
                }
                processDownloadStream(taskId, retryResp, tempFile, 0L)
            } else {
                val isRange = (resp.code == 206)
                val startByte = if (isRange) downloadedBytes else 0L
                processDownloadStream(taskId, resp, tempFile, startByte)
            }

            // 3. Verify media file
            downloadDao.updateStatus(taskId, DownloadStatus.VERIFYING.name)
            if (!tempFile.exists() || tempFile.length() < 1024) {
                throw IllegalStateException("下载文件过小或为空")
            }

            // Check if HTML or JSON
            val headerBytes = ByteArray(512)
            RandomAccessFile(tempFile, "r").use { it.read(headerBytes) }
            val headerStr = String(headerBytes, Charsets.ISO_8859_1).lowercase()
            if (headerStr.contains("<!doctype html") || headerStr.contains("<html") || (headerStr.trim().startsWith("{") && headerStr.contains("\"code\""))) {
                tempFile.delete()
                throw IllegalStateException("下载返回了网页或错误 JSON，不是有效音频文件")
            }

            // Determine extension
            val ext = if (task.targetQuality.contains("flac", ignoreCase = true)) "flac" else "mp3"

            // 4. Publish to MediaStore Music/PickAudio
            downloadDao.updateStatus(taskId, DownloadStatus.PUBLISHING.name)
            val publishedUri = publishToPublicMusic(tempFile, task.artist, task.title, task.platform, task.platformSongId, task.targetQuality, ext)

            // 5. Link LocalAsset to track
            val asset = LocalAssetEntity(
                trackId = task.trackId,
                uri = publishedUri.toString(),
                sourceType = "DOWNLOADED",
                fileSize = tempFile.length(),
                mimeType = if (ext == "flac") "audio/flac" else "audio/mpeg",
                format = ext,
                isAvailable = true
            )
            localAssetDao.insertOrUpdate(asset)

            // 6. Complete task
            task = task.copy(
                status = DownloadStatus.COMPLETED.name,
                downloadedBytes = tempFile.length(),
                totalBytes = tempFile.length(),
                targetUri = publishedUri.toString(),
                updatedAt = System.currentTimeMillis()
            )
            downloadDao.insertOrUpdate(task)

            // Delete temp file
            tempFile.delete()
        } catch (e: Exception) {
            if (e is CancellationException) {
                downloadDao.updateStatus(taskId, DownloadStatus.PAUSED.name)
            } else {
                downloadDao.updateStatus(taskId, DownloadStatus.FAILED.name, e.message ?: "下载未知错误")
            }
        } finally {
            activeJobs.remove(taskId)
        }
    }

    private fun processDownloadStream(taskId: String, resp: okhttp3.Response, file: File, startOffset: Long) {
        val body = resp.body ?: throw IllegalStateException("响应体为空")
        val contentLength = body.contentLength()
        val totalBytes = if (contentLength > 0) startOffset + contentLength else 0L

        val fos = if (startOffset > 0) FileOutputStream(file, true) else FileOutputStream(file, false)
        val buffer = ByteArray(8192)
        var bytesRead: Int
        var currentBytes = startOffset
        val inputStream = body.byteStream()

        var lastUpdateTime = System.currentTimeMillis()

        fos.use { out ->
            inputStream.use { input ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    currentBytes += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime > 500) {
                        lastUpdateTime = now
                        runBlocking {
                            downloadDao.updateProgress(taskId, currentBytes, totalBytes)
                        }
                    }
                }
            }
        }
        runBlocking {
            downloadDao.updateProgress(taskId, currentBytes, totalBytes)
        }
    }

    private fun publishToPublicMusic(
        sourceFile: File,
        artist: String,
        title: String,
        platform: String,
        songId: String,
        quality: String,
        extension: String
    ): Uri {
        val sanitizedArtist = artist.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(30)
        val sanitizedTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
        val fileName = "$sanitizedArtist - $sanitizedTitle [$platform-$songId-$quality].$extension"

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.MIME_TYPE, if (extension == "flac") "audio/flac" else "audio/mpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/PickAudio")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("无法在公共音乐目录创建媒体项")

        context.contentResolver.openOutputStream(itemUri)?.use { outStream ->
            sourceFile.inputStream().use { inStream ->
                inStream.copyTo(outStream)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            context.contentResolver.update(itemUri, values, null, null)
        }

        return itemUri
    }

    suspend fun deleteDownloadForTrack(trackId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val activeKeys = activeJobs.keys.toList()
            for (k in activeKeys) {
                val task = downloadDao.getTaskById(k)
                if (task?.trackId == trackId) {
                    activeJobs.remove(k)?.cancel()
                }
            }

            val assets = localAssetDao.getAssetsForTrack(trackId)
            for (asset in assets) {
                if (asset.sourceType == "DOWNLOADED") {
                    try {
                        val uri = Uri.parse(asset.uri)
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        // ignore
                    }
                    localAssetDao.deleteByUri(asset.uri)
                }
            }

            downloadDao.deleteTasksByTrackId(trackId)
            true
        } catch (e: Exception) {
            false
        }
    }
}
