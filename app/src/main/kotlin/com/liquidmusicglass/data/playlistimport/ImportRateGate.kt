package com.liquidmusicglass.data.playlistimport

import android.content.Context

/**
 * Само-троттлинг импорта из scrape-источников (Яндекс, Spotify), чтобы НЕ
 * дёргать их часто и не словить блок по velocity (урок с ICM-баном:
 * блокирует не количество, а частота с одного «клиента»).
 *
 * Механика (согласована с юзером): щедрый дневной лимит + пауза.
 *  - COOLDOWN между двумя импортами одного источника — глушит rapid-fire;
 *  - DAILY_CAP на источник за скользящие 24ч — потолок от спама, но с запасом
 *    на миграцию целой библиотеки за вечер.
 *
 * Apple НЕ троттлим — он идёт через официальный ICM, не скрейп.
 * Хранение — своя SharedPreferences, ключи по источнику. Per-устройство
 * (аккаунта у scrape-импорта нет).
 */
object ImportRateGate {

    private const val PREFS = "import_gate"
    private const val COOLDOWN_MS = 45_000L                 // пауза между импортами
    private const val DAILY_CAP = 15                        // импортов на источник / 24ч
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** null = можно импортировать; иначе — текст ошибки для юзера. */
    fun check(ctx: Context, source: String): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val last = p.getLong("last_$source", 0L)
        if (last > 0L && now - last < COOLDOWN_MS) {
            val sec = ((COOLDOWN_MS - (now - last)) / 1000L) + 1
            return "Please wait ${sec}s before the next import — we throttle requests so the source doesn't block us."
        }

        val windowStart = p.getLong("window_$source", 0L)
        val count = if (now - windowStart >= DAY_MS) 0 else p.getInt("count_$source", 0)
        if (count >= DAILY_CAP) {
            val hrs = ((DAY_MS - (now - windowStart)) / (60L * 60 * 1000)) + 1
            return "Daily import limit reached ($DAILY_CAP/day). Try again in ~${hrs}h."
        }
        return null
    }

    /** Отметить факт импорта (после прохождения [check]). */
    fun record(ctx: Context, source: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val windowStart = p.getLong("window_$source", 0L)
        val (newWindow, newCount) =
            if (now - windowStart >= DAY_MS) now to 1
            else windowStart to (p.getInt("count_$source", 0) + 1)
        p.edit()
            .putLong("last_$source", now)
            .putLong("window_$source", newWindow)
            .putInt("count_$source", newCount)
            .apply()
    }
}
