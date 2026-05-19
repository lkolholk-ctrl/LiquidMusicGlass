package com.liquidmusicglass

import android.app.Application
import com.kyant.fishnet.Fishnet
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.logging.CrashHandler
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this) // Java крэши
        val logDir = File(filesDir, "crash_logs").apply { mkdirs() }
        Fishnet.init(this, logDir.absolutePath) // Native/ANR крэши

        // Initialize auth repo first so partner_user_id is available before the
        // ICM API client is configured.
        IcmAuthRepository.init(this)

        // Initialize ICM Music API if key is saved
        val prefs = getSharedPreferences("icm", MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", null)
        if (!savedKey.isNullOrBlank() && savedKey.startsWith("pk_")) {
            IcmRepository.init(savedKey, IcmAuthRepository.ensurePartnerUserId())
            IcmAuthRepository.getSessionToken()?.let { IcmRepository.setSessionToken(it) }
        }
    }
}