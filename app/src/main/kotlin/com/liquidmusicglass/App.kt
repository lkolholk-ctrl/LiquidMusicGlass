package com.liquidmusicglass

import android.app.Application
import com.kyant.fishnet.Fishnet
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.data.local.LocalAuthManager
import com.liquidmusicglass.data.local.db.LibraryRepository
import com.liquidmusicglass.engine.AppSettings
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.logging.CrashHandler
import java.io.File

import com.liquidmusicglass.engine.PlaylistManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this) // Java крэши
        val logDir = File(filesDir, "crash_logs").apply { mkdirs() }
        Fishnet.init(this, logDir.absolutePath) // Native/ANR крэши

        // Initialize AppSettings (SharedPreferences) before any UI access
        AppSettings.init(this)

        // Initialize PlayerController
        PlayerController.init(this)

        // Initialize PlaylistManager
        PlaylistManager.init(this)

        // Initialize auth repositories
        IcmAuthRepository.init(this)
        LocalAuthManager.init(this)

        // Initialize local database
        LibraryRepository.getInstance(this)

        // Initialize ICM Music API if key is saved
        val prefs = getSharedPreferences("icm", MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", null)
        if (!savedKey.isNullOrBlank() && savedKey.startsWith("pk_")) {
            IcmRepository.init(savedKey, IcmAuthRepository.ensurePartnerUserId())
            IcmAuthRepository.getSessionToken()?.let { IcmRepository.setSessionToken(it) }
        }
    }
}