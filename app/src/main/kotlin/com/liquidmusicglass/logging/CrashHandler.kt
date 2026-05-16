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

    fun hasCrashLog(context: Context): Boolean {
        return try {
            val dir = File(context.filesDir, "crash_logs")
            dir.exists() && dir.listFiles()?.any { it.isFile } == true
        } catch (_: Throwable) {
            false
        }
    }

    fun readAndClearAll(context: Context): String? {
        return try {
            val dir = File(context.filesDir, "crash_logs")
            if (!dir.exists()) return null
            val files = dir.listFiles()?.filter { it.isFile } ?: return null
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