package com.liquidmusicglass.data.playlistimport

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-based logger for Yandex playlist import.
 * Writes to context.filesDir/yandex_import.log for Honor devices
 * where system logcat is encrypted.
 */
class ImportFileLogger(context: Context) {

    private val logFile = File(context.filesDir, "yandex_import.log")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(level: String, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] $level/$tag: $message\n"
        try {
            logFile.appendText(line)
        } catch (_: Exception) {
            // Silently fail — logging should not crash import
        }
    }

    @Synchronized
    fun clear() {
        try {
            logFile.writeText("")
        } catch (_: Exception) {}
    }

    fun getLogPath(): String = logFile.absolutePath

    fun getRecentLogs(maxLines: Int = 500): String {
        return try {
            logFile.readLines().takeLast(maxLines).joinToString("\n")
        } catch (_: Exception) {
            "No logs available"
        }
    }
}
