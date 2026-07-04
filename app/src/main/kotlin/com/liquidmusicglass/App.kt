package com.liquidmusicglass

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.kyant.fishnet.Fishnet
import com.liquidmusicglass.api.icm.IcmApiFileLogger
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.data.local.HomeCacheManager
import com.liquidmusicglass.data.local.LocalAuthManager
import com.liquidmusicglass.data.local.db.LibraryRepository
import com.liquidmusicglass.engine.AppSettings
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.logging.CrashHandler
import com.liquidmusicglass.ui.glass.CoverSigningInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

import com.liquidmusicglass.engine.PlaylistManager

class App : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Свой OkHttp для обложек, ОТДЕЛЬНЫЙ от IcmApi и С ТАЙМАУТАМИ: зависший
    // mzstatic/CDN не должен держать соединения вечно и копить потоки (в ANR дампе
    // обложки висели в TLS-handshake). callTimeout рубит висяк за 20с. Держим ссылку,
    // чтобы эвиктить пул при смене сети (VPN/Wi-Fi↔моб.).
    private val coverHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            // maxRequests=6 — ГЛОБАЛЬНЫЙ потолок одновременных загрузок обложек: на
            // первом экране не выходим пачкой в TLS-handshake (в ANR-дампе обложки
            // висели именно там). maxRequestsPerHost=4 — на один CDN.
            .dispatcher(Dispatcher().apply { maxRequests = 6; maxRequestsPerHost = 4 })
            .build()
    }

    /** Эвиктнуть пул соединений загрузчика обложек при смене сети. */
    fun evictImageConnections() {
        try {
            coverHttpClient.dispatcher.cancelAll()
            coverHttpClient.connectionPool.evictAll()
        } catch (_: Throwable) {}
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { coverHttpClient }
            .components {
                add(CoverSigningInterceptor())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.30) // 30% available memory
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(512L * 1024 * 1024) // 512 MB for album art
                    .build()
            }
            .respectCacheHeaders(false) // Ignore server cache headers, manage ourselves
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this) // Java крэши
        val logDir = File(filesDir, "crash_logs").apply { mkdirs() }
        Fishnet.init(this, logDir.absolutePath) // Native/ANR крэши

        // Ловушка зависаний UI: Fishnet-дампы ANR приходят БЕЗ стека main —
        // вачдог сам пишет полный Java-дамп в crash_logs/ui_freeze_*.txt,
        // когда main-лупер молчит > 6с (полевой кейс: тап по поиску).
        com.liquidmusicglass.debug.UiWatchdog.start(this)

        // Initialize file logger for IcmApi (works even when system logcat is encrypted)
        IcmApiFileLogger.init(this)
        IcmApiFileLogger.log("I", "App", "App started, IcmApiFileLogger initialized at ${IcmApiFileLogger.getLogPath()}")

        // Удалённая карта аудио-причуд — ДО AppSettings: auto-режим выхода должен
        // резолвиться уже с учётом кэша карты (сетевое обновление уйдёт в фон само).
        com.liquidmusicglass.engine.automix.RemoteQuirks.preload(this)
        com.liquidmusicglass.engine.automix.AudioTelemetry.init(this)

        // Initialize AppSettings (SharedPreferences) — лёгкая, можно на main
        AppSettings.init(this)

        // Оффлайн-очередь сигналов волны (wave/feedback + wave/playback):
        // недоставленное копится и досылается (drain — в фоне ниже).
        com.liquidmusicglass.api.icm.WaveSignalQueue.init(this)

        // Haptic Music: тактильные удары в такт музыке (цикл живёт только пока
        // тумблер включён и музыка играет — иначе спит, батарею не тратит)
        com.liquidmusicglass.engine.automix.HapticMusicEngine.init(this)

        // Player settings (DataStore) — лёгкая инициализация
        com.liquidmusicglass.engine.PlayerSettings.init(this)

        // Аудио-обработка (DataStore) — загрузка сохранённых EQ/эффектов
        com.liquidmusicglass.engine.AudioFxController.init(this)

        // Настройки пословной подсветки лирики (эффект/плавность)
        com.liquidmusicglass.engine.LyricsFxController.init(this)

        // Монитор маршрута вывода (BT/гарнитура) — переоткрывает Oboe на смене устройства
        com.liquidmusicglass.engine.AudioRouteMonitor.init(this)

        // Реактивный детектор энергосбережения (упрощает эффекты, не выключает)
        com.liquidmusicglass.ui.PowerSaveMonitor.init(this)

        // Классификация железа: на слабых устройствах AGSL-эффекты работают в
        // облегчённом режиме (меньше октав дыма, 30 Гц клоки)
        com.liquidmusicglass.ui.DeviceTier.init(this)

        // Initialize PlayerController — просто сохраняет context
        PlayerController.init(this)

        // Всё тяжёлое — в IO корутину
        appScope.launch {
            // Initialize PlaylistManager (JSON parse из SharedPreferences)
            PlaylistManager.init(this@App)

            // Initialize auth repositories
            IcmAuthRepository.init(this@App)
            LocalAuthManager.init(this@App)

            // Initialize local database (SQLite + initial load)
            LibraryRepository.getInstance(this@App)

            // Initialize home content cache
            HomeCacheManager.init(this@App)

            // Initialize ICM Music API if key is saved
            val prefs = getSharedPreferences("icm", MODE_PRIVATE)
            val savedKey = prefs.getString("api_key", null)
            if (!savedKey.isNullOrBlank() && savedKey.startsWith("pk_")) {
                IcmRepository.init(savedKey, IcmAuthRepository.ensurePartnerUserId())
                IcmAuthRepository.getSessionToken()?.let { IcmRepository.setSessionToken(it) }

                // Дослать сигналы волны, не доставленные в прошлой сессии,
                // и подтянуть серверные лайки в локальное избранное (синк
                // раньше жил только на открытии вкладки Library).
                runCatching { com.liquidmusicglass.api.icm.WaveSignalQueue.drain() }
                if (IcmAuthRepository.isLoggedIn.value) {
                    runCatching { LibraryRepository.getInstance(this@App).syncWithCloud() }
                }
            }
        }
    }

    /**
     * Давление на память: сбрасываем крупные in-memory кэши (обложки Coil —
     * до 30% heap, кэш лирики), чтобы LMK не выбивал процесс с играющей
     * музыкой из фона. Диск-кэши не трогаем — обложки перечитаются с диска.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
            runCatching { com.liquidmusicglass.engine.LyricsParser.trimCache() }
        }
    }
}