package com.pickaudio.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.*
import android.os.StatFs
import android.provider.MediaStore
import androidx.room.withTransaction
import com.pickaudio.data.db.*
import com.pickaudio.data.model.*
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.network.withResponse
import com.pickaudio.online.AlternativeVersionException
import com.pickaudio.source.LxSourceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadCoordinator(
    private val context: Context,
    private val database: PickAudioDatabase,
    private val sourceManager: LxSourceManager,
    private val preferences: UserPreferences = UserPreferences(context),
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build(),
    private val scheduleOverride: (suspend () -> Unit)? = null,
    private val publicationCheckpoint: (suspend (String) -> Unit)? = null
) {
    private val downloadDao = database.downloadDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val runnerMutex = Mutex()
    private val recoveryMutex = Mutex()
    private data class Worker(val generation: Long, val job: Job)
    private val workers = mutableMapOf<String, Worker>()
    private val blockedTracks = mutableMapOf<String, Int>()
    private val pendingGenerations = ConcurrentHashMap<String, Long>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var runnerJob: Job? = null
    private var recovered = false
    private val reservations = ConcurrentHashMap<String, Long>()
    private val budgetLock = Any()
    private val _versionCandidates = MutableStateFlow<Map<String, List<SearchSongItem>>>(emptyMap())
    val versionCandidates = _versionCandidates.asStateFlow()
    val partialDirectory: File get() = File(context.filesDir, "download_parts").apply { mkdirs() }

    init { scope.launch {
        try { recoverInterrupted() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.e("Downloads", "Interrupted download recovery failed", e) }
    } }
    private val tasksFlow = downloadDao.getAllTasks().distinctUntilChanged()
    val pendingCount = downloadDao.getPendingCount().distinctUntilChanged()
    fun getAllTasks(): Flow<List<DownloadTaskEntity>> = tasksFlow
    private fun part(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "下载任务编号无效" }
        return File(partialDirectory, "temp_${id}.part")
    }

    suspend fun enqueueDownload(trackId: String, title: String, artist: String, album: String, coverUri: String?,
        platform: String, platformSongId: String, quality: String? = null, durationMs: Long = 0, metadataJson: String = "{}"): String {
        val desired = quality ?: preferences.defaultDownloadQuality.first().value
        require(desired in sourceManager.supportedQualities(platform)) { "当前音源不支持所选音质，请选择支持的音质或更换音源" }
        recoverInterrupted()
        val id = commands.withLock {
            val existing = downloadDao.getTaskByPlatformSong(platform, platformSongId, desired)
            if (existing != null && existing.status == "COMPLETED" && isAccessible(existing.targetUri)) return@withLock existing.id
            database.withTransaction {
                val ref = database.onlineRefDao().getByPlatformId(platform, platformSongId)
                val canonicalId = ref?.trackId ?: trackId
                require(trackId !in blockedTracks && canonicalId !in blockedTracks) { "该歌曲正在整理，请稍后重试" }
                if (database.trackDao().getTrackById(canonicalId) == null)
                    database.trackDao().insertOrUpdate(TrackEntity(canonicalId, title, artist, album, durationMs, coverUri))
                if (ref == null) database.onlineRefDao().insertOrUpdate(OnlineRefEntity(
                    trackId = canonicalId, platform = platform, platformSongId = platformSongId, platformMetadataJson = metadataJson))
                else if (metadataJson.isNotBlank() && metadataJson != "{}" && ref.platformMetadataJson != metadataJson)
                    database.onlineRefDao().insertOrUpdate(ref.copy(platformMetadataJson = metadataJson))
                val task = if (existing == null) DownloadTaskEntity(UUID.randomUUID().toString(), canonicalId,
                    title, artist, album, coverUri, platform, platformSongId, desired, "PENDING", durationMs = durationMs)
                else if (existing.status in listOf("PAUSED", "FAILED", "COMPLETED", "CANCELLED")) {
                    if (existing.status == "COMPLETED") existing.targetUri?.let { uri ->
                        database.localAssetDao().getAssetByUri(uri)?.let { database.localAssetDao().updateAvailability(it.id, false) }
                    }
                    DownloadLease.invalidate(existing, "PENDING").let {
                        if (existing.status == "COMPLETED") it.copy(targetUri = null, publishToken = null, publishStage = null,
                            downloadedBytes = 0, totalBytes = 0, resourceEtag = null) else it
                    }
                } else existing
                downloadDao.insertOrUpdate(task)
                if (task.status == "PENDING") pendingGenerations[task.id] = task.executionGeneration
                task.id
            }
        }
        schedule()
        return id
    }

    private suspend fun schedule(force: Boolean = false) {
        if (commands.withLock { (runnerJob?.isActive == true).also { if (it) wake.trySend(Unit) } }) return
        if (scheduleOverride != null) { scheduleOverride.invoke(); return }
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!force && scheduler.getPendingJob(JOB_ID) != null) return
            val builder = JobInfo.Builder(JOB_ID, ComponentName(context, DownloadJobService::class.java)).setUserInitiated(true)
            if (preferences.wifiOnlyDownload.first()) builder.setRequiredNetwork(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
            else builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            check(scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS) { "系统暂未允许下载，请点击继续" }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { systemStopped(e.message ?: "系统暂未允许下载，请点击继续") }
    }
    suspend fun enqueueBatch(tracks: List<Track>, quality: String): DownloadBatchReport {
        var added = 0; var resumed = 0; var existing = 0; var skipped = 0
        val failures = mutableListOf<DownloadBatchFailure>()
        val seen = HashSet<Pair<String, String>>()
        for (track in tracks) {
            val platform = track.platform
            val songId = track.platformSongId
            if (platform == null || songId.isNullOrBlank() || !seen.add(platform to songId)) { skipped++; continue }
            try {
                val previous = downloadDao.getTaskByPlatformSong(platform, songId, quality)
                val id = enqueueDownload(track.id, track.title, track.artist, track.album, track.coverUri, platform, songId,
                    quality, track.durationMs, track.platformMetadataJson)
                val current = downloadDao.getTaskById(id) ?: error("任务已移除，请重试")
                when {
                    previous == null -> added++
                    current.executionGeneration != previous.executionGeneration -> resumed++
                    else -> existing++
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures.add(DownloadBatchFailure(track, e.message ?: "无法加入下载")) }
        }
        return DownloadBatchReport(added, resumed, existing, skipped, failures)
    }

    fun systemStopped(message: String = "系统已暂停下载，请点击继续") {
        val snapshot = pendingGenerations.toMap()
        scope.launch {
            commands.withLock {
                snapshot.forEach { (id, generation) ->
                    val task = downloadDao.getTaskById(id)
                    if (task?.status == "PENDING" && task.executionGeneration == generation)
                        downloadDao.insertOrUpdate(task.copy(errorMessage = message))
                }
            }
        }
    }

    suspend fun recoverInterrupted() = recoveryMutex.withLock {
        if (recovered) return@withLock
        downloadDao.getAllTasksSync().forEach { original ->
            try {
            val file = part(original.id)
            val legacy = File(context.cacheDir, "downloads/temp_${original.id}.part")
            if (!file.exists() && legacy.isFile) {
                if (!legacy.renameTo(file)) {
                    legacy.copyTo(file)
                    check(legacy.length() == file.length()) { "下载进度迁移未完成" }
                    legacy.delete()
                }
            }
            if (original.status == "COMPLETED") { reconcileCompleted(original); file.delete(); return@forEach }
            if (original.status == "CANCELLED") { cleanupOutput(original); file.delete(); downloadDao.deleteTask(original.id); return@forEach }
            var task = original
            val ticket = PublicationTicket.decode(task.publishToken)
            if (task.targetUri != null && (task.publishStage == "REGISTERED" || task.status == "PUBLISHING")) {
                val uri = Uri.parse(task.targetUri)
                val owned = ownedMedia(uri)
                if (owned != null && (task.publishStage == "REGISTERED" || !owned.pending) &&
                    task.status in ACTIVE_STATES && database.trackDao().getTrackById(task.trackId) != null) {
                    val verified = try { verifyOutput(uri, task.totalBytes, ticket?.sha256); true }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { false }
                    if (verified) {
                        finalizePublication(task, uri, null)
                        file.delete()
                        return@forEach
                    }
                }
            }
            if (task.publishStage != "REGISTERED" && task.targetUri != null) {
                cleanupOutput(task)
                task = task.copy(targetUri = null, publishStage = if (ticket != null && file.isFile) "VERIFIED" else null)
            }
            if (file.isFile) task = task.copy(tempFilePath = file.path, downloadedBytes = file.length(), bytesPerSecond = 0, etaSeconds = null)
            if (task.status in ACTIVE_STATES || task.status == "PENDING")
                task = DownloadLease.invalidate(task, "PAUSED", "上次下载已中断，点击继续可恢复")
            downloadDao.insertOrUpdate(task)
            // An insert may have preceded URI registration; only delete this ticket's pending file.
            if (ticket != null && task.targetUri == null) findPending(ticket)?.let { context.contentResolver.delete(it, null, null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("Downloads", "Unable to recover task ${original.id}", e)
                downloadDao.insertOrUpdate(DownloadLease.invalidate(original, "PAUSED",
                    "恢复失败：${e.message ?: "请检查文件授权后继续"}"))
            }
        }
        recovered = true
    }

    suspend fun runPending(network: Network? = null, enforceNetwork: Boolean = false) = runnerMutex.withLock {
        coroutineScope {
            recoverInterrupted()
            val owner = currentCoroutineContext().job
            commands.withLock { runnerJob = owner }
            try {
                while (isActive) {
                    val done = commands.withLock {
                        val pending = downloadDao.getAllTasksSync().filter { it.status == "PENDING" }
                        pending.forEach { pendingGenerations[it.id] = it.executionGeneration }
                        pending.filter { it.id !in workers }.take((2 - workers.size).coerceAtLeast(0)).forEach { task ->
                            val claimed = task.copy(status = "RESOLVING", executionGeneration = task.executionGeneration + 1,
                                errorMessage = null, bytesPerSecond = 0, etaSeconds = null)
                            downloadDao.insertOrUpdate(claimed)
                            pendingGenerations.remove(task.id)
                            val generation = claimed.executionGeneration
                            val job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                                try { executeDownload(task.id, generation, network, enforceNetwork) }
                                finally {
                                    withContext(NonCancellable) {
                                        commands.withLock {
                                            if (workers[task.id]?.generation == generation) workers.remove(task.id)
                                            wake.trySend(Unit)
                                        }
                                    }
                                }
                            }
                            workers[task.id] = Worker(generation, job)
                            job.start()
                        }
                        (workers.isEmpty() && pending.isEmpty()).also { if (it) runnerJob = null }
                    }
                    if (done) break
                    wake.receive()
                }
            } finally {
                withContext(NonCancellable) { commands.withLock { if (runnerJob === owner) runnerJob = null } }
            }
        }
    }

    private suspend fun mutate(id: String, generation: Long, change: (DownloadTaskEntity) -> DownloadTaskEntity): DownloadTaskEntity =
        commands.withLock {
            val current = downloadDao.getTaskById(id)
            if (!DownloadLease.canWrite(current, generation)) throw CancellationException("下载执行已失效")
            change(current!!).also { downloadDao.insertOrUpdate(it.copy(updatedAt = System.currentTimeMillis())) }
        }

    private suspend fun failLease(id: String, generation: Long, status: String, message: String) = commands.withLock {
        val task = downloadDao.getTaskById(id)
        if (DownloadLease.canWrite(task, generation)) downloadDao.insertOrUpdate(task!!.copy(status = status,
            errorMessage = message, bytesPerSecond = 0, etaSeconds = null, updatedAt = System.currentTimeMillis()))
    }

    fun pauseTask(id: String) { scope.launch { pauseAndWait(id) } }
    suspend fun pauseAndWait(id: String) {
        val job = commands.withLock {
            val task = downloadDao.getTaskById(id) ?: return@withLock null
            if (task.status != "PENDING" && task.status !in ACTIVE_STATES) return@withLock null
            downloadDao.insertOrUpdate(DownloadLease.invalidate(task, "PAUSED", "已暂停，下载进度已保留"))
            pendingGenerations.remove(id)
            workers[id]?.job?.also { it.cancel() }
        }
        job?.join()
    }
    fun resumeTask(id: String) { scope.launch { resumeAndSchedule(id) } }

    suspend fun refreshCompletedFiles() {
        downloadDao.getAllTasksSync().filter { it.status == "COMPLETED" }.forEach { reconcileCompleted(it) }
    }
    private suspend fun reconcileCompleted(task: DownloadTaskEntity) {
        val access = task.targetUri?.let { com.pickaudio.data.repository.checkAssetAccess(context, Uri.parse(it)) }
        if (access?.available == true) return
        commands.withLock { database.withTransaction {
            val current = downloadDao.getTaskById(task.id) ?: return@withTransaction
            if (current.status != "COMPLETED" || current.targetUri != task.targetUri) return@withTransaction
            task.targetUri?.let { uri -> database.localAssetDao().getAssetByUri(uri)?.let { asset ->
                database.localAssetDao().updateAvailability(asset.id, false, access?.reason ?: "下载文件地址缺失")
            } }
            downloadDao.insertOrUpdate(DownloadLease.invalidate(current, "FAILED", "下载文件不可用：${access?.reason ?: "文件地址缺失"}，可重新下载或关联文件")
                .copy(targetUri = null, publishToken = null, publishStage = null, tempFilePath = null,
                    downloadedBytes = 0, totalBytes = 0, resourceEtag = null))
        } }
    }
    suspend fun resumeAndSchedule(id: String) {
        commands.withLock {
            val task = downloadDao.getTaskById(id) ?: return@withLock
            if (task.status in listOf("PAUSED", "FAILED")) {
                val pending = DownloadLease.invalidate(task, "PENDING")
                downloadDao.insertOrUpdate(pending)
                pendingGenerations[id] = pending.executionGeneration
            } else if (task.status == "PENDING") downloadDao.insertOrUpdate(task.copy(errorMessage = null))
        }
        schedule(force = true)
        wake.trySend(Unit)
    }

    fun cancelTask(id: String) { scope.launch { cancelAndWait(id) } }
    suspend fun cancelAndWait(id: String) {
        val cancelled = commands.withLock {
            val task = downloadDao.getTaskById(id) ?: return@withLock null
            if (task.status == "COMPLETED") return@withLock null
            val result = DownloadLease.invalidate(task, "CANCELLED")
            downloadDao.insertOrUpdate(result)
            pendingGenerations.remove(id)
            workers[id]?.job?.cancel()
            result to workers[id]?.job
        } ?: return
        cancelled.second?.join()
        cancelled.first.targetUri?.let { value ->
            val uri = Uri.parse(value)
            val ticket = PublicationTicket.decode(cancelled.first.publishToken)
            if (ownedMedia(uri)?.pending == false && ticket != null &&
                runCatching { verifyOutput(uri, cancelled.first.totalBytes, ticket.sha256) }.isSuccess) {
                finalizePublication(cancelled.first, uri, null)
                return
            }
        }
        cleanupOutput(cancelled.first)
        part(id).delete()
        commands.withLock {
            if (downloadDao.getTaskById(id)?.status == "CANCELLED") downloadDao.deleteTask(id)
        }
        _versionCandidates.update { it - id }
    }

    suspend fun cancelForTrackAndWait(trackId: String) {
        downloadDao.getAllTasksSync().filter { it.trackId == trackId && it.status != "COMPLETED" }.forEach { cancelAndWait(it.id) }
    }
    suspend fun suspendTrackOperations(ids: Set<String>) {
        commands.withLock { ids.forEach { blockedTracks[it] = (blockedTracks[it] ?: 0) + 1 } }
        try { ids.forEach { cancelForTrackAndWait(it) } }
        catch (e: Exception) {
            withContext(NonCancellable) { releaseTrackOperations(ids) }
            throw e
        }
    }
    suspend fun releaseTrackOperations(ids: Set<String>) = commands.withLock {
        ids.forEach { id ->
            val count = (blockedTracks[id] ?: 1) - 1
            if (count <= 0) blockedTracks.remove(id) else blockedTracks[id] = count
        }
    }
    fun pauseAll(ids: List<String>) { scope.launch { ids.forEach { pauseAndWait(it) } } }
    fun resumeAll(ids: List<String>) { scope.launch { ids.forEach { resumeAndSchedule(it) } } }
    fun cancelAll(ids: List<String>) { scope.launch { ids.forEach { cancelAndWait(it) } } }
    fun dismissCandidates(id: String) { _versionCandidates.update { it - id } }

    suspend fun confirmVersion(taskId: String, item: SearchSongItem) {
        val original = downloadDao.getTaskById(taskId) ?: return
        val track = item.toTrack()
        enqueueDownload(track.id, track.title, track.artist, track.album, track.coverUri,
            item.platform, item.songId, original.targetQuality, item.durationMs)
        cancelAndWait(taskId)
    }

    private fun reserve(id: String, bytes: Long) = synchronized(budgetLock) {
        val others = reservations.filterKeys { it != id }.values.sum()
        val available = minOf(StatFs(context.filesDir.path).availableBytes,
            context.getExternalFilesDir(null)?.let { StatFs(it.path).availableBytes } ?: Long.MAX_VALUE)
        check(available > bytes.coerceAtLeast(0) + others + MIN_SPACE) { "空间不足以保存临时和公开文件，请释放空间后继续" }
        reservations[id] = bytes.coerceAtLeast(0)
    }

    private suspend fun executeDownload(id: String, generation: Long, network: Network?, enforceNetwork: Boolean) {
        val file = part(id)
        try {
            var task = downloadDao.getTaskById(id) ?: return
            if (enforceNetwork) {
                check(network != null) { "下载网络已断开，请连接后继续" }
                if (preferences.wifiOnlyDownload.first()) {
                    val capabilities = context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network)
                    check(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) { "仅允许 Wi-Fi 下载，请连接 Wi-Fi 后继续" }
                }
            }
            require(database.trackDao().getTrackById(task.trackId) != null) { "歌曲记录不存在，请重新添加歌曲" }
            if (task.publishStage == "REGISTERED" && task.targetUri != null) {
                val uri = Uri.parse(task.targetUri)
                verifyOutput(uri, task.totalBytes, PublicationTicket.decode(task.publishToken)?.sha256)
                finalizePublication(task, uri, generation)
                file.delete()
                return
            }
            val ticket = PublicationTicket.decode(task.publishToken)
            val verifiedPartial = task.publishStage == "VERIFIED" && file.isFile &&
                task.totalBytes > 0 && file.length() == task.totalBytes && ticket != null && digest(file) == ticket.sha256
            if (!verifiedPartial) {
                val url = sourceManager.resolveMusicUrl(task.platform, task.platformSongId, task.targetQuality, task.title, task.artist, network)
                val networkClient = sourceManager.audioClient(client, network)
                transfer(id, generation, task, file, url, networkClient)
            }
            mutate(id, generation) { it.copy(status = "VERIFYING") }
            val inspected = AndroidAudioProbe.inspect(context, Uri.fromFile(file), validatePackets = true)
            val format = inspected.info
            AudioFormatProbe.requireRequestedQuality(task.targetQuality, format)
            val duration = inspected.durationMs
            val actual = format.label + (format.qualityNotice(task.targetQuality)?.let { " · $it" } ?: "")
            val publication = ticket?.takeIf { verifiedPartial } ?: PublicationTicket.create(digest(file))
            task = mutate(id, generation) { it.copy(status = "PUBLISHING", publishStage = "VERIFIED",
                publishToken = publication.encode(), actualQuality = actual, durationMs = duration,
                downloadedBytes = file.length(), totalBytes = file.length(), bytesPerSecond = 0, etaSeconds = null) }
            publicationCheckpoint?.invoke("VERIFIED")
            publish(task, generation, file, format, publication)
            file.delete()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { failLease(id, generation, "PAUSED", "已暂停，下载进度已保留") }
            throw e
        } catch (e: AlternativeVersionException) {
            _versionCandidates.update { it + (id to e.candidates) }
            failLease(id, generation, "FAILED", "原版本暂不可用，可确认其他版本或配置专用音源")
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) {
                withContext(NonCancellable) { failLease(id, generation, "PAUSED", "下载已暂停，进度已保留") }
                throw CancellationException("下载已暂停", e)
            }
            failLease(id, generation, "FAILED", e.message ?: "下载失败，请重试")
        } finally { reservations.remove(id) }
    }

    private suspend fun transfer(id: String, generation: Long, original: DownloadTaskEntity, file: File,
        url: String, networkClient: OkHttpClient) {
        val validator = ResourceValidator.decode(original.resourceEtag)
        var offset = file.takeIf { it.isFile }?.length() ?: 0
        if (validator?.url != url || validator.etag == null || validator.representationUrl == null ||
            validator.etag.startsWith("W/", true)) offset = 0
        while (true) {
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
            if (offset > 0) request.header("Range", "bytes=${offset}-").header("If-Range", validator!!.etag!!)
            val received = networkClient.withResponse(request.build()) { response ->
                if (offset > 0 && (validator!!.representationUrl != response.request.url.toString() ||
                    !ResumePolicy.canResume(offset, validator.etag, response.code,
                        response.header("Content-Range"), response.header("ETag")))) return@withResponse false
                check(response.isSuccessful) { "下载服务返回 ${response.code}，请重试或换源" }
                val body = response.body ?: error("音源返回空内容")
                val length = body.contentLength()
                var total = if (length >= 0) offset + length else 0
                if (response.code == 206) {
                    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                        ?: error("服务器返回无效的文件范围")
                    require(match.groupValues[1].toLong() == offset &&
                        match.groupValues[2].toLong() + 1 == match.groupValues[3].toLong() &&
                        (length < 0 || total == match.groupValues[3].toLong())) { "服务器返回不完整的文件范围" }
                    total = match.groupValues[3].toLong()
                }
                require(total <= MAX_AUDIO_BYTES) { "音频文件超过允许大小" }
                reserve(id, if (total > 0) total - offset + total else MIN_SPACE)
                mutate(id, generation) { it.copy(status = "DOWNLOADING", tempFilePath = file.path,
                    resourceEtag = ResourceValidator(url, response.header("ETag"), response.request.url.toString()).encode(), downloadedBytes = offset,
                    totalBytes = total, publishToken = null, publishStage = null, targetUri = null) }
                var bytes = offset
                var lastBytes = bytes
                var lastTime = System.nanoTime()
                FileOutputStream(file, offset > 0).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            bytes += count
                            require(bytes <= MAX_AUDIO_BYTES) { "音频文件超过允许大小" }
                            val now = System.nanoTime()
                            if (now - lastTime >= 500_000_000L) {
                                reserve(id, if (total > 0) total - bytes + total else bytes)
                                val speed = (bytes - lastBytes) * 1_000_000_000L / (now - lastTime)
                                mutate(id, generation) { it.copy(downloadedBytes = bytes, totalBytes = total,
                                    bytesPerSecond = speed, etaSeconds = if (total > bytes && speed > 0) (total - bytes) / speed else null) }
                                lastBytes = bytes
                                lastTime = now
                            }
                        }
                    }
                    output.fd.sync()
                }
                check(bytes > 0 && (total <= 0 || bytes == total)) { "下载不完整，请继续" }
                mutate(id, generation) { it.copy(downloadedBytes = bytes, totalBytes = bytes, bytesPerSecond = 0, etaSeconds = null) }
                true
            }
            if (received) return
            offset = 0
        }
    }

    private suspend fun digest(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32768)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun publish(task: DownloadTaskEntity, generation: Long, file: File,
        format: DetectedAudioFormat, ticket: PublicationTicket) {
        reserve(task.id, file.length())
        var current = mutate(task.id, generation) { it.copy(publishStage = "ALLOCATED") }
        publicationCheckpoint?.invoke("ALLOCATED")
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, ticket.pendingName + "." + format.extension)
            put(MediaStore.Audio.Media.TITLE, task.title); put(MediaStore.Audio.Media.ARTIST, task.artist)
            put(MediaStore.Audio.Media.ALBUM, task.album); put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/PickAudio"); put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values) ?: error("无法创建音乐文件")
        try {
            current = mutate(task.id, generation) { it.copy(targetUri = uri.toString(), publishStage = "CREATED") }
            publicationCheckpoint?.invoke("CREATED")
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(32768)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        reservations[task.id] = (file.length() - copied).coerceAtLeast(0)
                    }
                }
            } ?: error("无法写入音乐文件")
            verifyOutput(uri, file.length(), ticket.sha256)
            current = mutate(task.id, generation) { it.copy(publishStage = "COPIED") }
            publicationCheckpoint?.invoke("COPIED")
            commands.withLock {
                val row = downloadDao.getTaskById(task.id)
                if (!DownloadLease.canWrite(row, generation)) throw CancellationException("任务已暂停")
                database.withTransaction {
                    require(database.trackDao().getTrackById(task.trackId) != null) { "歌曲记录已被移除" }
                    database.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = task.trackId, uri = uri.toString(),
                        sourceType = "DOWNLOADED", fileSize = file.length(), mimeType = format.mimeType,
                        format = format.extension, isAvailable = false, fileHash = ticket.sha256, folderName = "Music/PickAudio",
                        folderId = "media:external_primary:Music/PickAudio", audioInfoJson = format.encode()))
                    current = row!!.copy(publishStage = "REGISTERED")
                    downloadDao.insertOrUpdate(current)
                }
            }
            publicationCheckpoint?.invoke("REGISTERED")
            finalizePublication(current, uri, generation)
        } catch (e: Exception) {
            withContext(NonCancellable) {
                val row = downloadDao.getTaskById(task.id)
                if (row?.publishStage != "REGISTERED" && row?.status != "COMPLETED") {
                    if (ownedMedia(uri)?.pending == true) context.contentResolver.delete(uri, null, null)
                    database.localAssetDao().deleteByUri(uri.toString())
                    commands.withLock {
                        val latest = downloadDao.getTaskById(task.id)
                        if (latest?.targetUri == uri.toString()) downloadDao.insertOrUpdate(latest.copy(targetUri = null, publishStage = "VERIFIED"))
                    }
                }
            }
            throw e
        }
    }

    private suspend fun finalizePublication(task: DownloadTaskEntity, uri: Uri, generation: Long?) = withContext(NonCancellable) {
        commands.withLock {
            val row = downloadDao.getTaskById(task.id) ?: error("下载任务不存在")
            if (generation != null && !DownloadLease.canWrite(row, generation)) throw CancellationException("任务已暂停")
            require(ownedMedia(uri) != null) { "目标文件不属于此下载" }
            val safe = { value: String -> value.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60) }
            val extension = context.contentResolver.getType(uri)?.substringAfterLast('/')?.let { if (it == "mpeg") "mp3" else if (it == "mp4") "m4a" else it } ?: "audio"
            val update = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "${safe(row.artist)} - ${safe(row.title)} [${row.targetQuality}].$extension")
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            check(context.contentResolver.update(uri, update, null, null) > 0) { "无法公开音乐文件，请继续下载以重试" }
            publicationCheckpoint?.invoke("EXPOSED")
            database.withTransaction {
                val asset = database.localAssetDao().getAssetByUri(uri.toString())
                if (asset != null) database.localAssetDao().updateAvailability(asset.id, true)
                else database.localAssetDao().insertOrUpdate(LocalAssetEntity(trackId = row.trackId, uri = uri.toString(),
                    sourceType = "DOWNLOADED", fileSize = row.totalBytes, mimeType = context.contentResolver.getType(uri),
                    format = extension, fileHash = PublicationTicket.decode(row.publishToken)?.sha256, folderName = "Music/PickAudio"))
                database.trackDao().getTrackById(row.trackId)?.let { database.trackDao().insertOrUpdate(it.copy(durationMs = row.durationMs.takeIf { it > 0 } ?: it.durationMs)) }
                downloadDao.insertOrUpdate(row.copy(status = "COMPLETED", targetUri = uri.toString(), publishStage = "COMPLETED",
                    downloadedBytes = row.totalBytes, bytesPerSecond = 0, etaSeconds = null, errorMessage = null, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    private data class OwnedMedia(val pending: Boolean)
    private val collection get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private fun ownedMedia(uri: Uri): OwnedMedia? {
        if (uri.scheme != "content" || uri.authority != "media") return null
        return context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
            if (!it.moveToFirst() || it.getString(0) != context.packageName) null else OwnedMedia(it.getInt(1) != 0)
        }
    }
    private fun findPending(ticket: PublicationTicket): Uri? = context.contentResolver.query(collection,
        arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
        arrayOf(ticket.pendingName + ".%", context.packageName), null)?.use {
        if (it.moveToFirst()) android.content.ContentUris.withAppendedId(collection, it.getLong(0)) else null
    }

    private suspend fun verifyOutput(uri: Uri, expectedBytes: Long, expectedHash: String?) {
        require(ownedMedia(uri) != null) { "音乐文件已移除或身份不匹配" }
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= MAX_AUDIO_BYTES) { "文件超过允许大小" }
                hash.update(buffer, 0, read)
            }
        } ?: error("无法读取音乐文件")
        require(count > 0 && expectedBytes > 0 && count == expectedBytes) { "公开文件尚未完整" }
        expectedHash?.let { expected -> require(hash.digest().joinToString("") { "%02x".format(it) } == expected) { "公开文件校验失败" } }
    }

    private suspend fun cleanupOutput(task: DownloadTaskEntity) {
        task.targetUri?.let {
            val uri = Uri.parse(it)
            if (ownedMedia(uri)?.pending == true) context.contentResolver.delete(uri, null, null)
            database.localAssetDao().getAssetByUri(it)?.takeIf { !it.isAvailable }?.let { database.localAssetDao().deleteByUri(it.uri) }
        }
        PublicationTicket.decode(task.publishToken)?.let { ticket ->
            findPending(ticket)?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun isAccessible(value: String?): Boolean {
        if (value == null) return false
        val uri = Uri.parse(value)
        if (ownedMedia(uri)?.pending != false) return false
        return try { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }
        catch (_: FileNotFoundException) { false }
    }

    suspend fun deleteDownloadForTrack(trackId: String): Boolean {
        suspendTrackOperations(setOf(trackId))
        try {
            val dao = database.localAssetDao()
            val assets = dao.getAssetsForTrack(trackId).filter { it.sourceType == "DOWNLOADED" }
            if (assets.isEmpty()) return false
            for (asset in assets) {
                dao.updateAvailability(asset.id, false, com.pickaudio.data.repository.DELETION_PENDING)
                try {
                    com.pickaudio.data.repository.deleteAudioAsset(context, asset.uri)
                    withContext(NonCancellable) { database.withTransaction {
                        dao.deleteByUri(asset.uri)
                        downloadDao.getAllTasksSync().filter { it.trackId == trackId && it.targetUri == asset.uri }
                            .forEach { downloadDao.deleteTask(it.id) }
                    } }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    val access = com.pickaudio.data.repository.checkAssetAccess(context, Uri.parse(asset.uri))
                    dao.finishDeletion(asset.id, access.available, access.reason)
                    return false
                }
            }
            downloadDao.deleteTasksByTrackId(trackId)
            return true
        } finally {
            withContext(NonCancellable) { releaseTrackOperations(setOf(trackId)) }
        }
    }

    companion object {
        const val JOB_ID = 2040
        val ACTIVE_STATES = DownloadLease.activeStates
        private const val MIN_SPACE = 4 * 1024 * 1024L
        private const val MAX_AUDIO_BYTES = 2 * 1024 * 1024 * 1024L
    }
}
