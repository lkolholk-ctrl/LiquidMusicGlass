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
import java.io.File

import com.liquidmusicglass.engine.PlaylistManager

class App : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
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