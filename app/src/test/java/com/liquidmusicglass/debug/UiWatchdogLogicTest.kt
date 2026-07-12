package com.liquidmusicglass.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты ЧИСТОЙ логики вачдога [classifyFreeze] (без Android/времени/потоков).
 * Покрывают сценарии из спеки: on-time, recovered-delay, confirmed-freeze,
 * background, grace, замороженный процесс, main-idle.
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
    fun `heartbeat on time -> OK`() {
        assertEquals(WatchdogVerdict.OK, classify(silent = 1500L))
    }

    // 2) один heartbeat задержался и восстановился — RECOVERED_DELAY (без уведомления).
    @Test
    fun `delayed then recovered heartbeat -> RECOVERED_DELAY`() {
        assertEquals(
            WatchdogVerdict.RECOVERED_DELAY,
            classify(silent = 6500L, execDetect = 1000L, execRecheck = 6400L),
        )
    }

    // 2b) main на re-check стоит в холостом nativePollOnce — тоже восстановление.
    @Test
    fun `main idle at recheck -> RECOVERED_DELAY`() {
        assertEquals(
            WatchdogVerdict.RECOVERED_DELAY,
            classify(silent = 7000L, execDetect = 1000L, execRecheck = 1000L, mainIdle = true),
        )
    }

    // 3) main заблокирован на ДВУХ проверках — CONFIRMED_FREEZE.
    @Test
    fun `still blocked on both checks -> CONFIRMED_FREEZE`() {
        assertEquals(
            WatchdogVerdict.CONFIRMED_FREEZE,
            classify(silent = 7000L, execDetect = 1000L, execRecheck = 1000L, mainIdle = false),
        )
    }

    // 4) Activity в background — отчёта нет.
    @Test
    fun `background -> OK`() {
        assertEquals(WatchdogVerdict.OK, classify(monitoring = false, silent = 9000L))
    }

    // 5) foreground после background: beginMonitoring сбросил отметку → silent мал → OK.
    @Test
    fun `fresh timestamp after foreground -> OK`() {
        assertEquals(WatchdogVerdict.OK, classify(silent = 200L))
    }

    // 6) grace-период (старт/первый RESUMED) — отчёта нет.
    @Test
    fun `in grace -> OK`() {
        assertEquals(WatchdogVerdict.OK, classify(inGrace = true, silent = 9000L))
    }

    // 7) весь процесс стоял (гигантский loopGap) — не фриз UI, OK.
    @Test
    fun `whole process frozen -> OK`() {
        assertEquals(WatchdogVerdict.OK, classify(loopGap = checkMs * 4, silent = 9000L))
    }

    // 8) ровно на пороге — ещё OK (строгое превышение).
    @Test
    fun `exactly at threshold -> OK`() {
        assertEquals(WatchdogVerdict.OK, classify(silent = thresholdMs))
    }
}
