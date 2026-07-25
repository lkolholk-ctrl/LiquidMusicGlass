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

        httpDataSourceFactory = httpFactory
        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        android.util.Log.d(
            "MediaCacheManager",
            "Cache initialized at ${dir.absolutePath}, maxBytes=${maxBytes / 1024 / 1024}MB (LRU evictor)"
        )
    }

    @Volatile private var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null

    private fun releaseInternal() {
        try {
            cache?.release()
        } catch (e: Exception) {
            android.util.Log.e("MediaCacheManager", "release failed: ${e.message}")
        }
        cache = null
        cacheDataSourceFactory = null
        httpDataSourceFactory = null
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
    private fun cacheKey(trackId: String) = "icm_$trackId"

    /** F5: экспорт одного трека не должен идти в двух потоках одновременно. */
    private val exportLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * Полностью ли трек лежит в кэше. Проверяем ДО экспорта: частичный файл
     * бесполезен — у m4a/mp4 moov-атом в конце, и MediaExtractor такой файл
     * просто не откроет (признаки трека вырождаются в тишину).
     *
     * exo_len проставляется в первые миллисекунды закачки, поэтому проверка
     * работает и для недокачанного трека. isCached меряет НЕПРЕРЫВНОЕ покрытие
     * [0, len) и бросает на LENGTH_UNSET — поэтому сначала длина, потом проверка.
     */
    /** Полная длина контента в кэше или 0, если неизвестна. */
    fun cachedContentLength(trackId: String): Long {
        val c = cache ?: return 0L
        val len = androidx.media3.datasource.cache.ContentMetadata.getContentLength(
            c.getContentMetadata(cacheKey(trackId))
        )
        return if (len > 0L) len else 0L
    }

    /**
     * Длина НЕПРЕРЫВНОГО закэшированного куска от начала трека.
     *
     * Мерить полноту по метаданным (exo_len) нельзя: ICM отдаёт поток без
     * Content-Length, и для играющего трека длина так и остаётся неизвестной —
     * проверка «закэширован целиком» тогда всегда ложно-отрицательна. Фактическое
     * покрытие кэша от этого не зависит.
     */
    fun cachedPrefixLength(trackId: String): Long = runCatching {
        val c = cache ?: return@runCatching 0L
        val n = c.getCachedLength(
            cacheKey(trackId), 0L, androidx.media3.common.C.LENGTH_UNSET.toLong()
        )
        if (n > 0L) n else 0L
    }.getOrDefault(0L)

    /**
     * Настоящий размер файла: запрашиваем один байт и читаем Content-Range.
     * ICM отдаёт поток без Content-Length, поэтому иначе полноту кэша знать
     * неоткуда — а без неё анализатору можно скормить обрезок вместо трека.
     */
    private fun probeDocumentLength(url: android.net.Uri): Long {
        val ds = (httpDataSourceFactory ?: return -1L).createDataSource()
        return try {
            ds.open(
                androidx.media3.datasource.DataSpec.Builder()
                    .setUri(url).setPosition(0L).setLength(1L).build()
            )
            fun header(name: String) = ds.responseHeaders.entries
                .firstOrNull { it.key?.equals(name, ignoreCase = true) == true }
                ?.value?.firstOrNull()
            val fromRange = androidx.media3.datasource.HttpUtil.getDocumentSize(
                header("Content-Range")
            )
            if (fromRange > 0L) {
                fromRange
            } else {
                // Сервер проигнорировал Range и отдал файл целиком (200): тогда
                // размер документа — это Content-Length, мы ведь просили с нуля.
                header("Content-Length")?.toLongOrNull() ?: -1L
            }
        } catch (e: Exception) {
            com.liquidmusicglass.debug.DebugLog.add("AutoMix.probe failed: ${e.message}")
            -1L
        } finally {
            runCatching { ds.close() }
        }
    }

    /**
     * Возвращает длину трека, дописывая её в метаданные кэша, если media3 её не
     * узнал. После этого isFullyCached снова осмыслен и самолечится: он читает
     * фактическое покрытие кэша, а не запоминает былые успехи.
     */
    /** Когда можно снова спрашивать длину: неудачный probe открывает отдачу файла. */
    private val probeRetryAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun ensureContentLength(trackId: String, url: android.net.Uri): Long {
        val known = cachedContentLength(trackId)
        if (known > 0L) return known
        val c = cache ?: return 0L
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < (probeRetryAtMs[trackId] ?: 0L)) return 0L
        val size = probeDocumentLength(url)
        if (size <= 0L) {
            probeRetryAtMs[trackId] = now + 60_000L
            return 0L
        }
        probeRetryAtMs.remove(trackId)
        runCatching {
            val mutations = androidx.media3.datasource.cache.ContentMetadataMutations()
            androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(
                mutations, size
            )
            c.applyContentMetadataMutations(cacheKey(trackId), mutations)
        }
        return size
    }

    fun isFullyCached(trackId: String): Boolean {
        val c = cache ?: return false
        val key = cacheKey(trackId)
        val len = androidx.media3.datasource.cache.ContentMetadata.getContentLength(
            c.getContentMetadata(key)
        )
        if (len <= 0L) return false
        return try {
            c.isCached(key, 0L, len)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Выгружает ЦЕЛИКОМ закэшированный трек в обычный файл — для анализа AutoMix.
     *
     * Анализатор использует [android.media.MediaExtractor], который не умеет читать
     * media3-кэш и по сетевому URL тянет файл почти целиком (чтобы дойти до хвоста
     * трека) — на 4G это ~60 c. Данные уже лежат в кэше после preCacheTrack.
     *
     * ВАЖНО: читаем СТРОГО из кэша. createDataSourceForDownloading() для этого не
     * годится — он сохраняет upstream и на дырке уходит в сеть. Здесь берём
     * источник без upstream (внутри PlaceholderDataSource, который физически не
     * может открыть сеть) и сверяем длину: недописанный файл удаляем, а не отдаём.
     */
    fun exportCachedTrackToFile(trackId: String, url: android.net.Uri, out: File): Boolean {
        val lock = exportLocks.computeIfAbsent(trackId) { Any() }
        synchronized(lock) { return exportCachedTrackToFileLocked(trackId, url, out) }
    }

    private fun exportCachedTrackToFileLocked(
        trackId: String,
        url: android.net.Uri,
        out: File
    ): Boolean {
        val c = cache ?: return false
        val factory = getCacheDataSourceFactory() ?: return false
        val key = cacheKey(trackId)
        val contentLength = androidx.media3.datasource.cache.ContentMetadata.getContentLength(
            c.getContentMetadata(key)
        )
        // Без известной длины «полнота» проверялась бы сама собой: сколько
        // прочитали из кэша, столько и ожидали. Тогда обрезок проходит как годный.
        val exportLength = contentLength
        if (exportLength <= 0L) {
            com.liquidmusicglass.debug.DebugLog.add("AutoMix.export NOLEN $trackId")
            return false
        }
        val prefix = cachedPrefixLength(trackId)
        if (prefix < contentLength) {
            com.liquidmusicglass.debug.DebugLog.add(
                "AutoMix.export PARTIAL $trackId $prefix/$contentLength"
            )
            return false // ещё качается — анализировать нечего
        }
        val spec = androidx.media3.datasource.DataSpec.Builder()
            .setUri(url)
            .setKey(key)
            .setPosition(0)
            .setLength(exportLength)
            .build()
        // upstream принудительно null → в сеть уйти невозможно.
        val source = factory.createDataSourceForRemovingDownload()
        var written = 0L
        // Пишем во временный файл и переименовываем: иначе частично записанный
        // файл виден как «готовый» (и переиспользуется), а два параллельных
        // экспорта одного трека писали бы в один поток вперемешку.
        val tmp = File(out.parentFile, out.name + ".part")
        return try {
            source.open(spec)
            tmp.outputStream().use { fos ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buf, 0, buf.size)
                    if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                    fos.write(buf, 0, n)
                    written += n
                }
            }
            // Строгая сверка: LRU мог вытеснить спаны прямо во время чтения.
            val ok = written == exportLength && tmp.length() == exportLength
            if (ok) {
                runCatching { out.delete() }
                tmp.renameTo(out)
            } else {
                // Через DebugLog, а не Log.d: R8 вырезает Log.d в релизе,
                // и причина провала экспорта была бы не видна на устройстве.
                com.liquidmusicglass.debug.DebugLog.add(
                    "AutoMix.export INCOMPLETE $trackId $written/$exportLength"
                )
                runCatching { tmp.delete() }
            }
            ok
        } catch (e: Exception) {
            com.liquidmusicglass.debug.DebugLog.add("AutoMix.export FAILED $trackId: ${e.message}")
            runCatching { tmp.delete() }
            false
        } finally {
            runCatching { source.close() }
        }
    }

    @JvmOverloads
    fun preCacheTrack(
        trackId: String,
        url: android.net.Uri,
        /**
         * false — докачка «для себя» (анализ AutoMix): не отменяет фоновую
         * предзагрузку и не занимает её слот. Иначе два контура глушили друг
         * друга: анализ отменял PRELOAD следующего трека, PRELOAD — докачку анализа.
         */
        exclusive: Boolean = true
    ): Boolean {
        val factory = getCacheDataSourceFactory() ?: return false
        val key = "icm_$trackId"
        // Тот же трек уже качается — не отменяем чужой writer и не выдаём это
        // за провал: данные всё равно появятся.
        if (activePreCacheKey == key) return isFullyCached(trackId)
        if (exclusive) {
            // Раньше отменялся любой активный writer — новый префетч обрывал
            // докачку предыдущего трека, которую сам же и ждал.
            try { activePreCache?.cancel() } catch (_: Throwable) {}
        }

        val spec = androidx.media3.datasource.DataSpec.Builder()
            .setUri(url)
            .setKey(key)
            .build()
        val writer = androidx.media3.datasource.cache.CacheWriter(
            factory.createDataSourceForDownloading(), spec, null, null
        )
        if (exclusive) {
            activePreCache = writer
            activePreCacheKey = key
        }
        return try {
            writer.cache()
            true
        } catch (e: Exception) {
            // отмена/сеть — не страшно: вызывающий ретраит, а недокачанное
            // доберётся при воспроизведении (CacheWriter пропускает готовые куски).
            com.liquidmusicglass.debug.DebugLog.add("AutoMix.precache stopped $trackId: ${e.message}")
            // Не гадаем по коду ответа: 416 media3 сам обрабатывает, когда мы
            // действительно на EOF (DefaultHttpDataSource сверяет Content-Range и
            // проставляет exo_len). Долетевший сюда 416 — признак рассогласования
            // кэша с документом, и полнотой его считать нельзя.
            isFullyCached(trackId)
        } finally {
            if (exclusive && activePreCacheKey == key) {
                activePreCache = null
                activePreCacheKey = null
            }
        }
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
