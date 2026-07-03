package com.liquidmusicglass.engine.automix

import android.os.SystemClock
import com.liquidmusicglass.debug.DebugLog
import com.liquidmusicglass.engine.AppSettings

/**
 * Watchdog аудио-выхода: «звук должен идти, а прогресса нет» → автоматический
 * уход на AudioTrack-выход (режим 6) — гарантированный путь через системный
 * микшер (им играет ExoPlayer, работает на всём парке).
 *
 * Ловит МЕХАНИЧЕСКИЕ отказы выхода: аудио-колбэк перестал тикать или позиция
 * замерла при активном воспроизведении дольше [STALL_MS] (мёртвый поток после
 * смены маршрута, не поднявшийся бэкенд, зависший реопен и т.п.).
 *
 * Принципиальный предел: случай «HAL принимает данные и рапортует успех, но
 * портит/глотает их» (шип vivo, тишина Honor на None) изнутри приложения
 * НЕОБНАРУЖИМ — колбэк тикает, level здоровый. Эти случаи закрываются
 * вендорными дефолтами (AppSettings.defaultAudioCompatModeForDevice) и ручным
 * тумблером; watchdog — третий эшелон.
 *
 * Кормится из тикера JuceLocalPlayer (~100мс). Эскалация: сразу в AUDIOTRACK
 * (терминальный заведомо-рабочий), без прогулки по промежуточным режимам —
 * они уже могли быть источником проблемы. Выбор персистится: следующий запуск
 * стартует сразу в рабочем режиме.
 */
object AudioOutputWatchdog {

    private const val STALL_MS = 3_000L        // столько «без прогресса» = отказ выхода
    private const val COOLDOWN_MS = 15_000L    // не чаще одной эскалации в кулдаун
    private const val MAX_ESCALATIONS = 2      // защита от бесконечного цикла за сессию

    private var lastMode = -1
    private var lastPosMs = Long.MIN_VALUE
    private var lastPosProgressAt = 0L
    private var lastCbCount = Long.MIN_VALUE
    private var lastCbProgressAt = 0L
    private var lastEscalationAt = 0L
    private var escalations = 0

    /**
     * Тик наблюдения. [playingAudibly] — прямо сейчас ДОЛЖЕН идти звук
     * (play + трек загружен + не буферизация/переход); [positionMs] — позиция
     * текущего дека движка.
     */
    fun noteTick(playingAudibly: Boolean, positionMs: Long) {
        val now = SystemClock.elapsedRealtime()

        // Смена режима (пользователь/мы сами) — начать наблюдение заново:
        // реопену устройства нужно время, это не отказ.
        val mode = AppSettings.audioCompatMode.value
        if (mode != lastMode) {
            lastMode = mode
            reset(now, positionMs)
            return
        }

        if (!playingAudibly) {
            reset(now, positionMs)
            return
        }

        if (positionMs != lastPosMs) {
            lastPosMs = positionMs
            lastPosProgressAt = now
        }
        val cb = AutoMixNativeEngine.callbackCount()
        if (cb != lastCbCount) {
            lastCbCount = cb
            lastCbProgressAt = now
        }

        val stalled = now - lastPosProgressAt > STALL_MS || now - lastCbProgressAt > STALL_MS
        if (!stalled) return

        if (mode == AutoMixNativeEngine.OBOE_MODE_AUDIOTRACK) {
            // Терминальный режим тоже встал — эскалировать некуда, только сигналим.
            if (now - lastEscalationAt > COOLDOWN_MS) {
                DebugLog.add("WATCHDOG: stall в AUDIOTRACK (pos=$positionMs cb=$cb) — эскалировать некуда")
                lastEscalationAt = now
            }
            reset(now, positionMs)
            return
        }
        if (escalations >= MAX_ESCALATIONS || now - lastEscalationAt < COOLDOWN_MS) return

        escalations++
        lastEscalationAt = now
        DebugLog.add(
            "WATCHDOG: играем, но прогресса нет >${STALL_MS}мс " +
                "(mode=$mode pos=$positionMs cb=$cb) → AUDIOTRACK"
        )
        AudioTelemetry.onWatchdogEscalated()
        // Персистится: следующий запуск на этом устройстве сразу в рабочем режиме.
        AppSettings.setAudioCompatMode(AutoMixNativeEngine.OBOE_MODE_AUDIOTRACK)
        reset(now, positionMs)
    }

    private fun reset(now: Long, positionMs: Long) {
        lastPosMs = positionMs
        lastPosProgressAt = now
        lastCbCount = Long.MIN_VALUE
        lastCbProgressAt = now
    }
}
