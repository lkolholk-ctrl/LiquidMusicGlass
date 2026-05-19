package com.liquidmusicglass.engine

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Асинхронный менеджер медиа-кэша.
 *
 * Инициализация SimpleCache происходит лениво в фоновом потоке (Dispatchers.IO),
 * чтобы не блокировать onCreate() сервиса и Application.
 *
 * При ошибке кэша (база данных заблокирована, повреждена и т.д.)
 * автоматически переключается на чистый DefaultHttpDataSource без кэша.
 */
@OptIn(UnstableApi::class)
object MediaCacheManager {

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var cacheDataSourceFactory: CacheDataSource.Factory? = null

    @Volatile
    private var isInitializing = false

    @Volatile
    private var initFailed = false

    /**
     * Ленивая асинхронная инициализация кэша.
     * Безопасно вызывать многократно — idempotent.
     */
    suspend fun init(context: Context) {
        if (cache != null || initFailed) return
        if (isInitializing) return

        isInitializing = true
        try {
            withContext(Dispatchers.IO) {
                val cacheDir = File(context.cacheDir, "media3_cache").apply {
                    if (!exists()) mkdirs()
                }
                val dbProvider = StandaloneDatabaseProvider(context)
                val simpleCache = SimpleCache(
                    cacheDir,
                    NoOpCacheEvictor(),
                    dbProvider
                )
                cache = simpleCache

                val httpFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(5_000)
                    .setReadTimeoutMs(5_000)
                    .setDefaultRequestProperties(mapOf(
                        "User-Agent" to "LiquidMusicGlass/1.0"
                    ))

                cacheDataSourceFactory = CacheDataSource.Factory()
                    .setCache(simpleCache)
                    .setUpstreamDataSourceFactory(httpFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

                android.util.Log.d("MediaCacheManager", "Cache initialized at ${cacheDir.absolutePath}")
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaCacheManager", "Cache init failed, falling back to no-cache: ${e.message}")
            initFailed = true
            cache = null
            cacheDataSourceFactory = null
        } finally {
            isInitializing = false
        }
    }

    /**
     * Возвращает CacheDataSource.Factory если кэш доступен,
     * иначе чистый DefaultHttpDataSource.Factory.
     *
     * Thread-safe — может вызываться с Main dispatcher.
     */
    fun getDataSourceFactory(): androidx.media3.datasource.DataSource.Factory {
        return cacheDataSourceFactory ?: DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(5_000)
            .setDefaultRequestProperties(mapOf(
                "User-Agent" to "LiquidMusicGlass/1.0"
            ))
    }

    /**
     * Возвращает CacheDataSource.Factory или null если кэш не инициализирован.
     * Для передачи в ExoPlayer.Builder.setMediaSourceFactory().
     */
    fun getCacheDataSourceFactory(): CacheDataSource.Factory? = cacheDataSourceFactory

    fun release() {
        try {
            cache?.release()
        } catch (e: Exception) {
            android.util.Log.e("MediaCacheManager", "Error releasing cache: ${e.message}")
        }
        cache = null
        cacheDataSourceFactory = null
        initFailed = false
    }
}
