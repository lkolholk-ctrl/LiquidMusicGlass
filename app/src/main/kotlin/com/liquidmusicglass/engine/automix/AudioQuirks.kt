package com.liquidmusicglass.engine.automix

import android.os.Build

/**
 * Таблица «причуд» вендоров: какой аудио-выход стартовый для этого устройства,
 * пока пользователь/watchdog не выбрали явно.
 *
 * Двухуровневая адресация — как у QuirksManager в Oboe и таблиц Poweramp:
 *  1. МОДЕЛЬ (Build.MODEL / DEVICE) — точечные правила для конкретных аппаратов;
 *  2. СЕМЕЙСТВО (MANUFACTURER/BRAND) — правило на вендора целиком.
 * Побеждает первое совпадение сверху вниз; нет совпадений → быстрый нативный
 * путь (NORMAL), механические отказы которого подхватит AudioOutputWatchdog.
 *
 * Полевая карта (обновляй при каждом новом подтверждённом устройстве):
 *   03.07.2026  Honor (владелец): AAudio Shared+LowLatency ✓; Shared+None — ТИШИНА.
 *   03.07.2026  vivo Y35 (SD680): AAudio портит Float И I16 (fast — шип,
 *               deep — тишина); AudioTrack ✓ (путь ExoPlayer, чист).
 *   02.07.2026  Xiaomi (друг): exclusive-MMAP — шум; стриминг (AudioTrack) чист;
 *               Shared-режимы не подтверждены → AudioTrack до подтверждения.
 */
object AudioQuirks {

    private val AUDIOTRACK = AutoMixNativeEngine.OBOE_MODE_AUDIOTRACK
    private val NORMAL = AutoMixNativeEngine.OBOE_MODE_NORMAL

    /** Точечные правила по модели (подстрока в MODEL/DEVICE, lowercase). */
    private val modelRules: List<Pair<String, Int>> = listOf(
        // vivo Y35: V2205 — подтверждён полевыми дампами (оба AAudio-пути битые).
        "v2205" to AUDIOTRACK,
    )

    /** Правила по семейству вендора (подстрока в MANUFACTURER+BRAND, lowercase). */
    private val familyRules: List<Pair<String, Int>> = listOf(
        "vivo" to AUDIOTRACK,
        "iqoo" to AUDIOTRACK,
        "xiaomi" to AUDIOTRACK,
        "redmi" to AUDIOTRACK,
        "poco" to AUDIOTRACK,
    )

    /** Стартовый режим для ЭТОГО устройства (когда явного выбора ещё не было).
     *  Удалённая карта (RemoteQuirks) сильнее встроенной на каждом уровне:
     *  правка quirks.json чинит вендора у всего парка без релиза. */
    fun defaultMode(): Int {
        val model = (Build.MODEL + " " + Build.DEVICE).lowercase()
        RemoteQuirks.modeForModel(model)?.let { return it }
        modelRules.firstOrNull { (sub, _) -> sub in model }?.let { return it.second }

        val family = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        RemoteQuirks.modeForFamily(family)?.let { return it }
        familyRules.firstOrNull { (sub, _) -> sub in family }?.let { return it.second }

        return NORMAL
    }

    /** Строка для диагностики: что за устройство и какой дефолт ему выпал. */
    fun describe(): String =
        "${Build.MANUFACTURER}/${Build.BRAND} ${Build.MODEL} (${Build.DEVICE}) " +
            "-> default=${defaultMode()} [${RemoteQuirks.describe()}]"
}
