package com.liquidmusicglass.engine

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Размер держит штатный [LeastRecentlyUsedCacheEvictor] (maxBytes из DataStore
 * [PlayerSettings.audioCacheBytes]) — он вытесняет наименее используемое прямо при
 * записи. Кэш ДИСКОВЫЙ (File в context.cacheDir), не в RAM. 0 = кэш выключен.
 * Смена размера применяется немедленно — кэш пересобирается с новым лимитом.
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
    private var appContext: Context? = null

    @Volatile
    private var currentMaxBytes: Long = 0L

    // Сериализует (пере)сборку SimpleCache — нельзя иметь два инстанса на одну папку.
    private val cacheLock = Mutex()

    /**
     * Лимит кэша в байтах — теперь из пользовательской настройки
     * [PlayerSettings.audioCacheBytes] (селектор 0/200МБ/500МБ/1/2/5ГБ).
     * 0 = кэш выключен (см. [isCacheEnabled]).
     */
    fun getCacheLimitBytes(): Long = PlayerSettings.audioCacheBytes.value

    /** Кэш включён (пользователь выбрал ненулевой размер). */
    fun isCacheEnabled(): Boolean = PlayerSettings.isCacheEnabled()

    /**
     * Ленивая асинхронная инициализация кэша.
     * Безопасно вызывать многократно — idempotent.
     * После инициализации запускает LRU-очистку.
     */
    suspend fun init(context: Context) {
        appContext = context.applicationContext
        if (cache != null || initFailed) return
        if (isInitializing) return

        isInitializing = true
        try {
            withContext(Dispatchers.IO) {
                cacheLock.withLock {
                    if (cache != null) return@withLock
                    val dir = File(context.cacheDir, "media3_cache").apply {
                        if (!exists()) mkdirs()
                    }
                    cacheDir = dir

                    val maxBytes = getCacheLimitBytes()
                    if (maxBytes <= 0L) {
                        // Кэш выключен пользователем → чистим папку, инстанс не создаём.
                        dir.walkTopDown().filter { it.isFile }.forEach { it.delete() }
                        cache = null
                        cacheDataSourceFactory = null
                        currentMaxBytes = 0L
                        android.util.Log.d("MediaCacheManager", "Cache disabled by user (0)")
                        return@withLock
                    }

                    buildCacheLocked(context, dir, maxBytes)
                }
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

    /** Создаёт SimpleCache с LRU-эвиктором на maxBytes и фабрику. Вызывать на IO. */
    private fun buildCacheLocked(context: Context, dir: File, maxBytes: Long) {
        val dbProvider = StandaloneDatabaseProvider(context)
        val simpleCache = SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(maxBytes),
            dbProvider
        )
        cache = simpleCache
        currentMaxBytes = maxBytes

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(mapOf(
                "User-Agent" to "LiquidMusicGlass/1.0"
            ))

        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        android.util.Log.d(
            "MediaCacheManager",
            "Cache initialized at ${dir.absolutePath}, maxBytes=${maxBytes / 1024 / 1024}MB (LRU evictor)"
        )
    }

    private fun releaseInternal() {
        try {
            cache?.release()
        } catch (e: Exception) {
            android.util.Log.e("MediaCacheManager", "release failed: ${e.message}")
        }
        cache = null
        cacheDataSourceFactory = null
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
     * Размер кэша держит [LeastRecentlyUsedCacheEvictor] — он вытесняет наименее
     * используемые ресурсы прямо при записи через CacheDataSource. Ручная очистка
     * по размеру больше не нужна (и опасна — рассинхронит учёт эвиктора при
     * удалении файлов «за его спиной»), поэтому здесь намеренно ничего не делаем.
     */
    private fun performLruCleanup(simpleCache: SimpleCache, dir: File) {
        // no-op: эвиктор сам поддерживает лимит.
    }

    /**
     * Раньше дёргалось после кэширования трека для ручной очистки. Теперь размер
     * держит эвиктор — оставлено как no-op для совместимости с вызовами.
     */
    fun onCacheUpdated() {
        // no-op
    }

    /**
     * Возвращает CacheDataSource.Factory если кэш доступен,
     * иначе чистый DefaultHttpDataSource.Factory.
     *
     * Thread-safe — может вызываться с Main dispatcher.
     */
    fun getDataSourceFactory(): androidx.media3.datasource.DataSource.Factory {
        val cf = if (isCacheEnabled()) cacheDataSourceFactory else null
        return cf ?: DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(mapOf(
                "User-Agent" to "LiquidMusicGlass/1.0"
            ))
    }

    /**
     * Возвращает CacheDataSource.Factory или null если кэш не инициализирован
     * ИЛИ выключен пользователем (размер 0) — тогда поток идёт мимо кэша.
     * Для передачи в ExoPlayer.Builder.setMediaSourceFactory().
     */
    fun getCacheDataSourceFactory(): CacheDataSource.Factory? =
        if (isCacheEnabled()) cacheDataSourceFactory else null

    // ── Предзагрузка аудио в кэш ──
    @Volatile private var activePreCache: androidx.media3.datasource.cache.CacheWriter? = null
    @Volatile private var activePreCacheKey: String? = null

    /**
     * Скачивает аудио трека в кэш ЦЕЛИКОМ (дока ICM: Range-запросы и полная
     * загрузка поддерживаются из коробки). Ключ — "icm_<trackId>", тот же, что
     * использует плеер: закэшированное играет мгновенно и без сети.
     *
     * Раньше «предзагрузка» только резолвила URL, не качая ни байта аудио —
     * на переходе плеер начинал загрузку с нуля и стопорился.
     *
     * Блокирующий вызов — звать с Dispatchers.IO. Повторный вызов для уже
     * закэшированного трека выходит быстро (CacheWriter пропускает готовые
     * куски). Новый префетч отменяет предыдущий незавершённый.
     */
    fun preCacheTrack(trackId: String, url: android.net.Uri): Boolean {
        val factory = getCacheDataSourceFactory() ?: return false
        val key = "icm_$trackId"
        if (activePreCacheKey == key) return false
        try { activePreCache?.cancel() } catch (_: Throwable) {}

        val spec = androidx.media3.datasource.DataSpec.Builder()
            .setUri(url)
            .setKey(key)
            .build()
        val writer = androidx.media3.datasource.cache.CacheWriter(
            factory.createDataSourceForDownloading(), spec, null, null
        )
        activePreCache = writer
        activePreCacheKey = key
        return try {
            writer.cache()
            android.util.Log.d("MediaCacheManager", "Pre-cached audio for $trackId")
            true
        } catch (e: Exception) {
            // отмена/сеть — не страшно: вызывающий ретраит, а недокачанное
            // доберётся при воспроизведении (CacheWriter пропускает готовые куски).
            android.util.Log.d("MediaCacheManager", "Pre-cache stopped for $trackId: ${e.message}")
            false
        } finally {
            if (activePreCacheKey == key) {
                activePreCache = null
                activePreCacheKey = null
            }
        }
    }

    /**
     * Немедленно оборвать текущую фоновую предзагрузку, если она идёт.
     *
     * Ключевой момент: [CacheWriter.cache] — БЛОКИРУЮЩИЙ, поэтому отмена
     * корутины-джоба сама по себе его не останавливает (проверка isActive
     * происходит только ПОСЛЕ возврата из write). Без явного [cancel] недокачка
     * продолжала тянуть трек по сети параллельно с плеером/переходом/JUCE —
     * лишняя media/io-нагрузка, зафиксированная в ANR-дампе. Этот вызов
     * дёргает CacheWriter.cancel(), из-за чего cache() бросает и выходит сразу.
     */
    fun cancelActivePreCache() {
        try { activePreCache?.cancel() } catch (_: Throwable) {}
        activePreCache = null
        activePreCacheKey = null
    }

    /** Реально занятый объём кэша в байтах (для строки «В данный момент: X МБ»). */
    suspend fun getCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        try {
            cache?.cacheSpace ?: run {
                val dir = cacheDir ?: return@withContext 0L
                dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        } catch (_: Exception) {
            0L
        }
    }

    /** Полностью очистить кэш аудио (кнопка «Очистить»). */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        cacheLock.withLock {
            val simpleCache = cache
            try {
                if (simpleCache != null) {
                    // removeResource синхронизирует учёт эвиктора (в отличие от удаления
                    // файлов «за его спиной»).
                    simpleCache.keys.toList().forEach { key -> simpleCache.removeResource(key) }
                } else {
                    cacheDir?.walkTopDown()?.filter { it.isFile }?.forEach { it.delete() }
                }
                android.util.Log.d("MediaCacheManager", "Cache cleared by user")
            } catch (e: Exception) {
                android.util.Log.e("MediaCacheManager", "clearCache failed: ${e.message}")
            }
        }
    }

    /**
     * Применить смену размера кэша немедленно. maxBytes зашит в эвиктор при сборке
     * SimpleCache, поэтому новый лимит = пересборка кэша: освобождаем старый инстанс
     * и создаём новый с [LeastRecentlyUsedCacheEvictor] на новом размере (он сам
     * подрежет содержимое под лимит). При 0 — освобождаем и чистим папку.
     */
    fun applyCacheSizeChange() {
        val ctx = appContext ?: return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            cacheLock.withLock {
                val maxBytes = getCacheLimitBytes()
                // Уже на нужном размере и в согласованном состоянии — выходим.
                if (maxBytes == currentMaxBytes && (cache != null || maxBytes <= 0L)) return@withLock

                releaseInternal()
                val dir = cacheDir ?: File(ctx.cacheDir, "media3_cache").apply { mkdirs() }.also { cacheDir = it }

                if (maxBytes <= 0L) {
                    dir.walkTopDown().filter { it.isFile }.forEach { it.delete() }
                    currentMaxBytes = 0L
                    android.util.Log.d("MediaCacheManager", "Cache disabled (0) — released and cleared")
                    return@withLock
                }

                initFailed = false
                try {
                    buildCacheLocked(ctx, dir, maxBytes)
                } catch (e: Exception) {
                    android.util.Log.e("MediaCacheManager", "Rebuild after size change failed: ${e.message}")
                    initFailed = true
                }
            }
        }
    }

    fun release() {
        try {
            cache?.release()
        } catch (e: Exception) {
            android.util.Log.e("MediaCacheManager", "Error releasing cache: ${e.message}")
        }
        cache = null
        cacheDataSourceFactory = null
        cacheDir = null
        currentMaxBytes = 0L
        initFailed = false
    }
}
