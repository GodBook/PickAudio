package com.pickaudio.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.StatFs
import android.provider.MediaStore
import androidx.room.withTransaction
import com.pickaudio.data.db.*
import com.pickaudio.data.model.*
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.online.AlternativeVersionException
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadCoordinator(
    private val context: Context,
    private val database: PickAudioDatabase,
    private val sourceManager: LxSourceManager,
    private val preferences: UserPreferences = UserPreferences(context)
) {
    private val downloadDao = database.downloadDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()
    private val semaphore = Semaphore(2)
    private val recoveryMutex = Mutex()
    private var recovered = false
    @Volatile private var running = false
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val _versionCandidates = MutableStateFlow<Map<String, List<SearchSongItem>>>(emptyMap())
    val versionCandidates = _versionCandidates.asStateFlow()
    val partialDirectory: File get() = File(context.filesDir, "download_parts").apply { mkdirs() }

    init { scope.launch { recoverInterrupted() } }

    fun getAllTasks(): Flow<List<DownloadTaskEntity>> = downloadDao.getAllTasks()

    suspend fun enqueueDownload(
        trackId: String, title: String, artist: String, album: String, coverUri: String?,
        platform: String, platformSongId: String, quality: String? = null, durationMs: Long = 0
    ): String {
        val desired = quality ?: preferences.defaultDownloadQuality.first().value
        val supported = sourceManager.supportedQualities(platform)
        require(desired in supported) { "当前音源不支持所选音质，请选择支持的音质或更换音源" }
        recoverInterrupted()
        val existing = downloadDao.getTaskByPlatformSong(platform, platformSongId, desired)
        if (existing != null) {
            if (existing.status in listOf("PAUSED", "FAILED")) resumeTask(existing.id)
            return existing.id
        }
        val id = UUID.randomUUID().toString()
        database.withTransaction {
            if (database.trackDao().getTrackById(trackId) == null) database.trackDao().insertOrUpdate(
                TrackEntity(trackId, title, artist, album, durationMs, coverUri)
            )
            if (database.onlineRefDao().getByTrackId(trackId) == null) database.onlineRefDao().insertOrUpdate(
                OnlineRefEntity(trackId = trackId, platform = platform, platformSongId = platformSongId, platformMetadataJson = "{}")
            )
            downloadDao.insertOrUpdate(DownloadTaskEntity(id, trackId, title, artist, album, coverUri, platform, platformSongId, desired, "PENDING", durationMs = durationMs))
        }
        schedule()
        return id
    }

    private suspend fun schedule() {
        if (running) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, DownloadJobService::class.java)).setUserInitiated(true)
        if (preferences.wifiOnlyDownload.first()) builder.setRequiredNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
        else builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        val job = builder.build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            downloadDao.getAllTasksSync().filter { it.status == "PENDING" }.forEach {
                downloadDao.updateStatus(it.id, "PAUSED", "系统暂未允许下载，请返回下载页点击继续")
            }
        }
    }

    suspend fun recoverInterrupted() = recoveryMutex.withLock {
        if (recovered) return@withLock
        val tasks = downloadDao.getAllTasksSync()
        tasks.filter { it.status != "COMPLETED" }.forEach { task ->
            val destination = File(partialDirectory, "temp_${task.id}.part")
            val legacy = File(context.cacheDir, "downloads/temp_${task.id}.part")
            if (!destination.exists() && legacy.isFile) {
                if (!legacy.renameTo(destination)) {
                    legacy.copyTo(destination)
                    check(legacy.length() == destination.length()) { "下载进度迁移未完成" }
                    legacy.delete()
                }
            }
            if (destination.isFile) {
                downloadDao.updateResource(task.id, destination.path, task.resourceEtag)
                downloadDao.updateTransfer(task.id, destination.length(), task.totalBytes, 0, null)
            }
        }
        tasks.filter { it.status in ACTIVE_STATES }.forEach { task ->
            task.targetUri?.takeIf { task.status == "PUBLISHING" }?.let { uri ->
                runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
            }
            downloadDao.updateStatus(task.id, "PAUSED", "上次下载已中断，点击继续可恢复")
        }
        recovered = true
    }

    suspend fun runPending() = coroutineScope {
        recoverInterrupted()
        running = true
        try {
            while (isActive) {
                val pending = downloadDao.getAllTasksSync().filter { it.status == "PENDING" && !jobs.containsKey(it.id) }
                if (pending.isEmpty()) break
                pending.map { task ->
                    launch {
                        jobs[task.id] = currentCoroutineContext().job
                        try { semaphore.withPermit { executeDownload(task.id) } }
                        finally { jobs.remove(task.id) }
                    }
                }.joinAll()
            }
        } finally { running = false }
    }

    fun pauseTask(id: String) {
        calls.remove(id)?.cancel()
        jobs.remove(id)?.cancel()
        scope.launch { if (downloadDao.getTaskById(id) != null) downloadDao.updateStatus(id, "PAUSED") }
    }

    fun resumeTask(id: String) {
        scope.launch {
            recoverInterrupted()
            if (downloadDao.getTaskById(id)?.status !in listOf("PAUSED", "FAILED")) return@launch
            downloadDao.updateStatus(id, "PENDING")
            schedule()
        }
    }

    fun cancelTask(id: String) {
        calls.remove(id)?.cancel()
        jobs.remove(id)?.cancel()
        scope.launch {
            val task = downloadDao.getTaskById(id) ?: return@launch
            task.tempFilePath?.let { File(it).delete() }
            _versionCandidates.value = _versionCandidates.value - id
            downloadDao.deleteTask(id)
        }
    }

    fun pauseAll(ids: List<String>) = ids.forEach(::pauseTask)
    fun resumeAll(ids: List<String>) = ids.forEach(::resumeTask)
    fun cancelAll(ids: List<String>) = ids.forEach(::cancelTask)
    fun dismissCandidates(id: String) { _versionCandidates.value = _versionCandidates.value - id }

    suspend fun confirmVersion(taskId: String, item: SearchSongItem) {
        val original = downloadDao.getTaskById(taskId) ?: return
        val track = item.toTrack()
        enqueueDownload(track.id, track.title, track.artist, track.album, track.coverUri,
            item.platform, item.songId, original.targetQuality, item.durationMs)
        cancelTask(taskId)
    }

    private suspend fun executeDownload(id: String) {
        var task = downloadDao.getTaskById(id) ?: return
        try {
            currentCoroutineContext().ensureActive()
            downloadDao.updateStatus(id, "RESOLVING")
            val url = sourceManager.resolveMusicUrl(task.platform, task.platformSongId, task.targetQuality, task.title, task.artist)
            currentCoroutineContext().ensureActive()
            val file = File(partialDirectory, "temp_${id}.part")
            if (StatFs(context.filesDir.path).availableBytes < 8 * 1024 * 1024) error("手机空间不足，请释放空间后继续")
            downloadDao.updateResource(id, file.path, task.resourceEtag)
            downloadDao.updateStatus(id, "DOWNLOADING")
            var offset = file.takeIf { it.exists() }?.length() ?: 0L
            val builder = Request.Builder().url(url)
            if (offset > 0) {
                builder.header("Range", "bytes=${offset}-")
                task.resourceEtag?.let { builder.header("If-Range", it) }
            }
            var response = request(id, builder.build())
            if (offset > 0 && !ResumePolicy.canResume(offset, task.resourceEtag, response.code, response.header("Content-Range"), response.header("ETag"))) {
                response.close()
                offset = 0
                response = request(id, Request.Builder().url(url).build())
            }
            response.use { resp ->
                check(resp.isSuccessful) { "下载服务返回 ${resp.code}，请稍后重试或更换音源" }
                val body = resp.body ?: error("音源返回空内容")
                downloadDao.updateResource(id, file.path, resp.header("ETag"))
                val length = body.contentLength()
                val total = if (length > 0) offset + length else 0L
                check(total <= 0 || StatFs(context.filesDir.path).availableBytes > total - offset + 4 * 1024 * 1024) { "手机空间不足，请释放空间后继续" }
                var bytes = offset
                var lastBytes = bytes
                var lastTime = System.nanoTime()
                FileOutputStream(file, offset > 0).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            bytes += count
                            val now = System.nanoTime()
                            if (now - lastTime >= 500_000_000L) {
                                val speed = ((bytes - lastBytes) * 1_000_000_000L / (now - lastTime)).coerceAtLeast(0)
                                val eta = if (total > bytes && speed > 0) (total - bytes) / speed else null
                                downloadDao.updateTransfer(id, bytes, total, speed, eta)
                                lastTime = now
                                lastBytes = bytes
                            }
                        }
                    }
                }
                check(total <= 0 || bytes == total) { "下载尚未完整，请继续下载" }
                downloadDao.updateTransfer(id, bytes, total, 0, null)
            }
            currentCoroutineContext().ensureActive()
            downloadDao.updateStatus(id, "VERIFYING")
            val header = file.inputStream().use { it.readNBytes(512) }
            val format = AudioFormatProbe.detect(header) ?: error("音源返回了无效音频内容，请换源后重试")
            AudioFormatProbe.requireRequestedQuality(task.targetQuality, format)
            val metadata = MediaMetadataRetriever()
            val duration: Long
            val bitrate: Long?
            try {
                metadata.setDataSource(file.path)
                duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                bitrate = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                check(duration > 0) { "文件无法解码为有效歌曲，请换源后重试" }
            } finally { metadata.release() }
            val actual = format.label + (bitrate?.takeIf { !format.lossless && it > 0 }?.let { " · ${it / 1000} kbps" } ?: "")
            downloadDao.updateStatus(id, "PUBLISHING")
            val uri = publish(id, file, task, format)
            database.withTransaction {
                database.localAssetDao().insertOrUpdate(LocalAssetEntity(
                    trackId = task.trackId, uri = uri.toString(), sourceType = "DOWNLOADED", fileSize = file.length(),
                    mimeType = format.mimeType, format = format.extension, folderName = "Music/PickAudio"
                ))
                database.trackDao().getTrackById(task.trackId)?.let { database.trackDao().insertOrUpdate(it.copy(durationMs = duration)) }
                task = downloadDao.getTaskById(id) ?: error("任务已取消")
                downloadDao.insertOrUpdate(task.copy(status = "COMPLETED", targetUri = uri.toString(),
                    actualQuality = actual, durationMs = duration, downloadedBytes = file.length(), totalBytes = file.length(),
                    bytesPerSecond = 0, etaSeconds = null, errorMessage = null, updatedAt = System.currentTimeMillis()))
            }
            file.delete()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (downloadDao.getTaskById(id) != null) downloadDao.updateStatus(id, "PAUSED", "已暂停，下载进度已保留")
            }
            throw e
        } catch (e: AlternativeVersionException) {
            _versionCandidates.value = _versionCandidates.value + (id to e.candidates)
            downloadDao.updateStatus(id, "FAILED", "原版本暂不可用，可确认其他版本或配置专用音源")
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) {
                withContext(NonCancellable) { if (downloadDao.getTaskById(id) != null) downloadDao.updateStatus(id, "PAUSED", "已暂停，下载进度已保留") }
                throw CancellationException("下载已暂停", e)
            }
            if (downloadDao.getTaskById(id) != null) downloadDao.updateStatus(id, "FAILED", e.message ?: "下载失败，请检查网络后重试")
        } finally { calls.remove(id)?.cancel() }
    }

    private suspend fun request(id: String, request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        calls[id] = call
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resumeWith(Result.success(response)) else response.close()
            }
        })
    }

    private suspend fun publish(id: String, file: File, task: DownloadTaskEntity, format: DetectedAudioFormat): Uri {
        val safe = { text: String -> text.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60) }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${safe(task.artist)} - ${safe(task.title)} [${task.platform}-${task.platformSongId}].${format.extension}")
            put(MediaStore.Audio.Media.TITLE, task.title)
            put(MediaStore.Audio.Media.ARTIST, task.artist)
            put(MediaStore.Audio.Media.ALBUM, task.album)
            put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/PickAudio")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("无法创建音乐文件")
        try {
            downloadDao.getTaskById(id)?.let { downloadDao.insertOrUpdate(it.copy(targetUri = uri.toString())) } ?: error("任务已取消")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: error("无法写入音乐文件，请检查存储空间")
            currentCoroutineContext().ensureActive()
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    suspend fun deleteDownloadForTrack(trackId: String): Boolean {
        val assets = database.localAssetDao().getAssetsForTrack(trackId).filter { it.sourceType == "DOWNLOADED" }
        if (assets.isEmpty()) return false
        for (asset in assets) {
            if (context.contentResolver.delete(Uri.parse(asset.uri), null, null) <= 0) return false
            database.localAssetDao().deleteByUri(asset.uri)
        }
        downloadDao.deleteTasksByTrackId(trackId)
        return true
    }

    companion object {
        const val JOB_ID = 2040
        val ACTIVE_STATES = setOf("RESOLVING", "DOWNLOADING", "VERIFYING", "PUBLISHING")
    }
}
