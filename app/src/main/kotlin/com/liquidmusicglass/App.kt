package com.liquidmusicglass

import android.app.Application
import com.kyant.fishnet.Fishnet
import com.liquidmusicglass.logging.CrashHandler
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this) // Java крэши
        val logDir = File(filesDir, "crash_logs").apply { mkdirs() }
        Fishnet.init(this, logDir.absolutePath) // Native/ANR крэши
    }
}