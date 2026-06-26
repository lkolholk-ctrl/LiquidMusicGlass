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
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 4 })
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

        // Initialize file logger for IcmApi (works even when system logcat is encrypted)
        IcmApiFileLogger.init(this)
        IcmApiFileLogger.log("I", "App", "App started, IcmApiFileLogger initialized at ${IcmApiFileLogger.getLogPath()}")

        // Initialize AppSettings (SharedPreferences) — лёгкая, можно на main
        AppSettings.init(this)

        // Player settings (DataStore) + сетевой монитор (Wi-Fi/сотовая) — лёгкие
        com.liquidmusicglass.engine.PlayerSettings.init(this)
        com.liquidmusicglass.engine.NetworkMonitor.init(this)

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
            }
        }
    }
}