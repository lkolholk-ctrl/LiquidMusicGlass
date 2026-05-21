package com.liquidmusicglass.engine

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.DefaultHttpDataSource
import com.liquidmusicglass.api.icm.IcmAuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Асинхронный менеджер медиа-кэша с LRU-очисткой.
 *
 * Инициализация SimpleCache происходит лениво в фоновом потоке (Dispatchers.IO),
 * чтобы не блокировать onCreate() сервиса и Application.
 *
 * При ошибке кэша (база данных заблокирована, повреждена и т.д.)
 * автоматически переключается на чистый DefaultHttpDataSource без кэша.
 *
 * LRU cleanup:
 * - Лимит зависит от качества стрима (128K→350MB, 256K→250MB, 320K→150MB, ALAC→100MB)
 * - Треки не слушавшиеся >7 дней удаляются всегда
 * - Запускается при init и после каждого добавления в кэш
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

    private var cacheDir: File? = null

    /**
     * Возвращает лимит кэша в байтах на основе текущего качества стрима.
     */
    fun getCacheLimitBytes(): Long {
        val quality = IcmAuthRepository.maxQuality.value
            ?: "256K"
        return when (quality.uppercase()) {
            "128K" -> 350L * 1024 * 1024
            "256K" -> 250L * 1024 * 1024
            "320K" -> 150L * 1024 * 1024
            "ALAC" -> 100L * 1024 * 1024
            else -> 250L * 1024 * 1024 // default
        }
    }

    /**
     * Ленивая асинхронная инициализация кэша.
     * Безопасно вызывать многократно — idempotent.
     * После инициализации запускает LRU-очистку.
     */
    suspend fun init(context: Context) {
        if (cache != null || initFailed) {
            // Cache already ready — run cleanup in case quality changed
            runCacheCleanup()
            return
        }
        if (isInitializing) return

        isInitializing = true
        try {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "media3_cache").apply {
                    if (!exists()) mkdirs()
                }
                cacheDir = dir
                val dbProvider = StandaloneDatabaseProvider(context)
                val simpleCache = SimpleCache(
                    dir,
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

                android.util.Log.d("MediaCacheManager", "Cache initialized at ${dir.absolutePath}")

                // Run initial LRU cleanup
                performLruCleanup(simpleCache, dir)
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
     * Запускает LRU-очистку кэша если он инициализирован.
     * Безопасно вызывать из любого потока.
     */
    suspend fun runCacheCleanup() {
        val simpleCache = cache ?: return
        val dir = cacheDir ?: return
        withContext(Dispatchers.IO) {
            performLruCleanup(simpleCache, dir)
        }
    }

    /**
     * LRU-очистка кэша:
     * 1. Удалить треки не слушавшиеся >7 дней (всегда)
     * 2. Если всё ещё превышен лимит — удалять самые старые по lastTouch
     */
    private fun performLruCleanup(simpleCache: SimpleCache, dir: File) {
        try {
            // Safety check: only clean media3_cache, never downloads/
            if (!dir.absolutePath.contains("media3_cache")) {
                android.util.Log.w("MediaCacheManager", "LRU cleanup skipped: unexpected dir ${dir.absolutePath}")
                return
            }
            val limitBytes = getCacheLimitBytes()
            val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L

            // Collect all cache files with metadata
            data class CacheFile(
                val file: File,
                val size: Long,
                val lastModified: Long
            )

            val files = dir.walkTopDown()
                .filter { it.isFile }
                .map { CacheFile(it, it.length(), it.lastModified()) }
                .toList()

            if (files.isEmpty()) return

            var totalSize = files.sumOf { it.size }

            // Phase 1: Remove files not accessed in 7+ days (always)
            val staleFiles = files.filter { it.lastModified < sevenDaysAgo }
            for (f in staleFiles.sortedBy { it.lastModified }) {
                if (f.file.delete()) {
                    totalSize -= f.size
                    android.util.Log.d("MediaCacheManager", "LRU removed stale file: ${f.file.name} (${f.size / 1024}KB)")
                }
            }

            // Phase 2: If still over limit, remove oldest by lastModified until under limit
            if (totalSize > limitBytes) {
                val remaining = files.filter { it.file.exists() }
                    .sortedBy { it.lastModified }

                for (f in remaining) {
                    if (totalSize <= limitBytes) break
                    if (f.file.delete()) {
                        totalSize -= f.size
                        android.util.Log.d("MediaCacheManager", "LRU removed old file: ${f.file.name} (${f.size / 1024}KB), total=${totalSize / 1024 / 1024}MB")
                    }
                }
            }

            // Release cache spans for deleted files so ExoPlayer knows they're gone
            try {
                simpleCache.keys.forEach { key ->
                    val cachedSpans = simpleCache.getCachedSpans(key)
                    if (cachedSpans.isEmpty()) {
                        simpleCache.removeResource(key)
                    }
                }
            } catch (_: Exception) { /* ignore */ }

            android.util.Log.d("MediaCacheManager",
                "LRU cleanup done. Limit=${limitBytes/1024/1024}MB, Current=${totalSize/1024/1024}MB, Files=${files.count { it.file.exists() }}"
            )
        } catch (e: Exception) {
            android.util.Log.e("MediaCacheManager", "LRU cleanup failed: ${e.message}")
        }
    }

    /**
     * Вызывается после кэширования нового трека — запускает фоновую очистку.
     */
    fun onCacheUpdated() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCacheCleanup()
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
        cacheDir = null
        initFailed = false
    }
}
