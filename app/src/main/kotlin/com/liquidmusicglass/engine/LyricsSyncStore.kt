package com.liquidmusicglass.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * Ручная подстройка синхронизации лирики: сдвиг в мс ДЛЯ КОНКРЕТНОГО трека.
 * Положительный сдвиг = лирика раньше (позиция плеера смещается вперёд).
 * Лекарство от кривых таймкодов источника (LRCLIB/ICM), которые мы не можем
 * править: пользователь один раз выставил ±, значение живёт навсегда.
 */
object LyricsSyncStore {

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("lyrics_sync", Context.MODE_PRIVATE)

    fun get(context: Context, trackId: String): Long =
        if (trackId.isBlank()) 0L else prefs(context).getLong("off_$trackId", 0L)

    fun set(context: Context, trackId: String, offsetMs: Long) {
        if (trackId.isBlank()) return
        prefs(context).edit().apply {
            if (offsetMs == 0L) remove("off_$trackId") else putLong("off_$trackId", offsetMs)
        }.apply()
    }
}
