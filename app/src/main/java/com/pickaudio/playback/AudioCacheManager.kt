package com.pickaudio.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import okhttp3.OkHttpClient
import com.pickaudio.network.NetworkPolicy

@OptIn(UnstableApi::class)
object AudioCacheManager {
    private const val MAX_CACHE_SIZE = 256 * 1024 * 1024L // 256 MB LRU Cache

    @Volatile
    private var simpleCache: SimpleCache? = null
    private var evictor: AdjustableCacheEvictor? = null
    @Volatile private var capacityBytes = MAX_CACHE_SIZE
    private val validators = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128
    }

    fun registerValidator(key: String, etag: String?) {
        synchronized(validators) { if (etag != null && !etag.startsWith("W/", true)) validators[key] = etag }
    }
    fun configureCapacity(context: Context, megabytes: Int) {
        val requested = megabytes.takeIf { it in listOf(64, 256, 1024) } ?: 256
        val cache = getCache(context)
        val available = android.os.StatFs(context.cacheDir.path).availableBytes
        val limit = minOf(requested * 1024 * 1024L, (available + cache.cacheSpace - 32 * 1024 * 1024L).coerceAtLeast(0))
        capacityBytes = limit
        evictor?.resize(cache, limit)
    }

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        if (simpleCache == null) {
            val cacheDir = File(context.cacheDir, "audio_media_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val databaseProvider = StandaloneDatabaseProvider(context)
            val policy = AdjustableCacheEvictor(capacityBytes).also { evictor = it }
            simpleCache = SimpleCache(cacheDir, policy, databaseProvider)
        }
        return simpleCache!!
    }

    fun createDataSourceFactory(context: Context, client: OkHttpClient = NetworkPolicy.Default.client()): DataSource.Factory {
        val httpDataSourceFactory = OkHttpDataSource.Factory(client)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

        val validatedHttp = DataSource.Factory {
            val delegate = httpDataSourceFactory.createDataSource()
            object : DataSource by delegate {
                override fun open(dataSpec: DataSpec): Long {
                    val etag = dataSpec.key?.let { synchronized(validators) { validators[it] } }
                    return delegate.open(if (etag == null) dataSpec else dataSpec.buildUpon()
                        .setHttpRequestHeaders(dataSpec.httpRequestHeaders + ("If-Match" to etag)).build())
                }
            }
        }
        val defaultDataSourceFactory = DefaultDataSource.Factory(context, validatedHttp)

        val cachedFactory = CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(defaultDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) { PlaybackDiagnostics.cached(cachedBytesRead) }
                override fun onCacheIgnored(reason: Int) = Unit
            })
        return DataSource.Factory {
            object : DataSource {
                private var delegate: DataSource? = null
                private val listeners = mutableListOf<TransferListener>()
                private var remote = false
                override fun open(dataSpec: DataSpec): Long {
                    check(delegate == null)
                    remote = dataSpec.uri.scheme in listOf("http", "https")
                    // Local files already occupy storage; do not copy them into the network cache.
                    val source = if (remote) cachedFactory.createDataSource() else defaultDataSourceFactory.createDataSource()
                    delegate = source
                    listeners.forEach(source::addTransferListener)
                    return source.open(dataSpec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(delegate).read(buffer, offset, length).also {
                    if (remote) PlaybackDiagnostics.bytes(it)
                }
                override fun addTransferListener(transferListener: TransferListener) {
                    listeners.add(transferListener); delegate?.addTransferListener(transferListener)
                }
                override fun getUri() = delegate?.uri
                override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
                override fun close() { try { delegate?.close() } finally { delegate = null } }
            }
        }
    }

    fun size(context: Context): Long = getCache(context).cacheSpace

    fun clear(context: Context) {
        val cache = getCache(context)
        cache.keys.toList().forEach { cache.removeResource(it) }
    }
}
