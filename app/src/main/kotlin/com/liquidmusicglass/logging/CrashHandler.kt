package com.liquidmusicglass.logging

import android.content.Context
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {

    fun install(context: Context) {
        val appContext = context.applicationContext

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val text = buildCrashReport(thread, throwable)
                val dir = File(appContext.filesDir, "crash_logs").apply { mkdirs() }
                val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                File(dir, "java_crash_$ts.txt").writeText(text)
            } catch (_: Throwable) {}

            Process.killProcess(Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    /**
     * Реальный крэш-лог (java_crash / нативный дамп Fishnet) — то, что ДОЛЖНО
     * показываться экраном краша. ui_freeze — это отчёт о ЗАВИСАНИИ (не крэш): он
     * не должен хайджекать старт приложения, поэтому в расчёт не берётся.
     */
    private fun File.isRealCrashLog(): Boolean =
        isFile && !name.startsWith("ui_freeze_")

    fun hasCrashLog(context: Context): Boolean {
        return try {
            val dir = File(context.filesDir, "crash_logs")
            dir.exists() && dir.listFiles()?.any { it.isRealCrashLog() } == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Удалить УСТАРЕВШИЕ ui_freeze-логи из crash_logs/. Раньше UiWatchdog писал туда
     * отчёты о фризах, и они всплывали ЭКРАНОМ КРАША на старте (в т.ч. ЛОЖНЫЕ —
     * полевой фидбек: «лог вылезает при первом открытии, с этим многие столкнулись»).
     * Теперь фризы — только диагностика в watchdog_diag/; старые чистим, чтобы не
     * показывались после апдейта. Зовём синхронно и рано из App.onCreate (до
     * MainActivity, которая проверяет hasCrashLog).
     */
    fun purgeStaleFreezeLogs(context: Context) {
        runCatching {
            val dir = File(context.filesDir, "crash_logs")
            dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("ui_freeze_") }
                ?.forEach { it.delete() }
        }
    }

    fun readAndClearAll(context: Context): String? {
        return try {
            val dir = File(context.filesDir, "crash_logs")
            if (!dir.exists()) return null
            // Только реальные крэши; ui_freeze не показываем (их чистит purgeStaleFreezeLogs).
            val files = dir.listFiles()?.filter { it.isRealCrashLog() } ?: return null
            if (files.isEmpty()) return null
            val combined = files
                .sortedBy { it.lastModified() }
                .joinToString("\n\n${"-".repeat(60)}\n\n") { file ->
                    val text = file.readText()
                    file.delete()
                    "=== ${file.name} ===\n$text"
                }
            combined.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val writer = StringWriter()
        val pw = PrintWriter(writer)
        pw.println("LiquidMusicGlass Java Crash")
        pw.println("Thread: ${thread.name}")
        pw.println("Exception: ${throwable::class.java.name}")
        pw.println("Message: ${throwable.message}")
        pw.println()
        throwable.printStackTrace(pw)
        var cause = throwable.cause
        while (cause != null) {
            pw.println()
            pw.println("Caused by: ${cause::class.java.name}")
            cause.printStackTrace(pw)
            cause = cause.cause
        }
        pw.flush()
        return writer.toString()
    }
}