package com.liquidmusicglass.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты ЧИСТОЙ логики вачдога [classifyFreeze] (без Android/времени/потоков).
 * Покрывают сценарии из спеки: on-time, recovered-delay, confirmed-freeze,
 * background, grace, замороженный процесс, main-idle.
 *
 * ВНИМАНИЕ: в именах бэктик-функций НЕЛЬЗЯ '->' — символ '>' запрещён в именах
 * JVM-методов даже в бэктиках (Kotlin: «Name contains illegal characters»).
 */
class UiWatchdogLogicTest {

    private val checkMs = 2000L
    private val thresholdMs = 6000L

    private fun classify(
        monitoring: Boolean = true,
        inGrace: Boolean = false,
        loopGap: Long = checkMs,
        silent: Long = 0L,
        execDetect: Long = 1000L,
        execRecheck: Long = 1000L,
        mainIdle: Boolean = false,
    ) = classifyFreeze(
        monitoring = monitoring,
        inGrace = inGrace,
        loopGapMs = loopGap,
        checkMs = checkMs,
        silentMs = silent,
        thresholdMs = thresholdMs,
        heartbeatExecAtDetect = execDetect,
        heartbeatExecAtRecheck = execRecheck,
        mainIdleAtRecheck = mainIdle,
    )

    // 1) heartbeat выполняется вовремя — отчёта нет.
    @Test
    fun heartbeat_on_time_is_OK() {
        assertEquals(WatchdogVerdict.OK, classify(silent = 1500L))
    }

    // 2) один heartbeat задержался и восстановился — RECOVERED_DELAY (без уведомления).
    @Test
    fun delayed_then_recovered_is_RECOVERED_DELAY() {
        assertEquals(
            WatchdogVerdict.RECOVERED_DELAY,
            classify(silent = 6500L, execDetect = 1000L, execRecheck = 6400L),
        )
    }

    // 2b) main на re-check стоит в холостом nativePollOnce — тоже восстановление.
    @Test
    fun main_idle_at_recheck_is_RECOVERED_DELAY() {
        assertEquals(
            WatchdogVerdict.RECOVERED_DELAY,
            classify(silent = 7000L, execDetect = 1000L, execRecheck = 1000L, mainIdle = true),
        )
    }

    // 3) main заблокирован на ДВУХ проверках — CONFIRMED_FREEZE.
    @Test
    fun still_blocked_on_both_checks_is_CONFIRMED_FREEZE() {
        assertEquals(
            WatchdogVerdict.CONFIRMED_FREEZE,
            classify(silent = 7000L, execDetect = 1000L, execRecheck = 1000L, mainIdle = false),
        )
    }

    // 4) Activity в background — отчёта нет.
    @Test
    fun background_is_OK() {
        assertEquals(WatchdogVerdict.OK, classify(monitoring = false, silent = 9000L))
    }

    // 5) foreground после background: beginMonitoring сбросил отметку → silent мал → OK.
    @Test
    fun fresh_timestamp_after_foreground_is_OK() {
        assertEquals(WatchdogVerdict.OK, classify(silent = 200L))
    }

    // 6) grace-период (старт/первый RESUMED) — отчёта нет.
    @Test
    fun in_grace_is_OK() {
        assertEquals(WatchdogVerdict.OK, classify(inGrace = true, silent = 9000L))
    }

    // 7) весь процесс стоял (гигантский loopGap) — не фриз UI, OK.
    @Test
    fun whole_process_frozen_is_OK() {
        assertEquals(WatchdogVerdict.OK, classify(loopGap = checkMs * 4, silent = 9000L))
    }

    // 8) ровно на пороге — ещё OK (строгое превышение).
    @Test
    fun exactly_at_threshold_is_OK() {
        assertEquals(WatchdogVerdict.OK, classify(silent = thresholdMs))
    }
}
