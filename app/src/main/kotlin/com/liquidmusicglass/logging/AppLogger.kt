package com.liquidmusicglass.logging

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val LOG_FILE_NAME = "liquidmusic_crash_log.txt"

    fun append(context: Context, tag: String, message: String) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            val timestamp = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            ).format(Date())

            file.appendText("[$timestamp][$tag] $message\n")
        } catch (_: Throwable) {
        }
    }

    fun overwrite(context: Context, text: String) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            file.writeText(text)
        } catch (_: Throwable) {
        }
    }

    fun read(context: Context): String {
        return try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.readText() else "Лог пуст."
        } catch (t: Throwable) {
            "Не удалось прочитать лог: ${t.message}"
        }
    }

    fun clear(context: Context) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Throwable) {
        }
    }
}