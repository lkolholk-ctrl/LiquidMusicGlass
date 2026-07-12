package com.liquidmusicglass.debug

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.os.HandlerCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Вердикт вачдога — вынесен вместе с [classifyFreeze] для юнит-тестов (без Android). */
internal enum class WatchdogVerdict { OK, RECOVERED_DELAY, CONFIRMED_FREEZE }

/**
 * ЧИСТАЯ логика решения вачдога (тестируемая, без Android/времени/потоков).
 *
 * @param monitoring foreground + есть RESUMED-Activity
 * @param inGrace внутри startup/resumed grace-периода
 * @param loopGapMs фактический интервал собственного цикла демона
 * @param checkMs штатный интервал цикла
 * @param silentMs сколько main-heartbeat не выполнялся на момент детекта
 * @param thresholdMs порог тишины
 * @param heartbeatExecAtDetect отметка выполнения heartbeat в момент детекта
 * @param heartbeatExecAtRecheck отметка выполнения heartbeat после re-check паузы
 * @param mainIdleAtRecheck main на re-check стоит в холостом nativePollOnce
 */
internal fun classifyFreeze(
    monitoring: Boolean,
    inGrace: Boolean,
    loopGapMs: Long,
    checkMs: Long,
    silentMs: Long,
    thresholdMs: Long,
    heartbeatExecAtDetect: Long,
    heartbeatExecAtRecheck: Long,
    mainIdleAtRecheck: Boolean,
): WatchdogVerdict {
    // Наш собственный цикл проспал сильно дольше запрошенного → ВЕСЬ процесс был
    // заморожен (cached-app freezer / доза в фоне). Это не фриз UI.
    if (loopGapMs > checkMs * 3) return WatchdogVerdict.OK
    // Мониторим только активный foreground и не в grace-периоде.
    if (!monitoring || inGrace) return WatchdogVerdict.OK
    // Порог не превышен — всё хорошо.
    if (silentMs <= thresholdMs) return WatchdogVerdict.OK
    // Порог превышен → ПОДТВЕРЖДЕНИЕ вторым замером: если heartbeat успел
    // выполниться за re-check паузу ИЛИ main уже в холостом nativePollOnce —
    // это восстановившаяся задержка, а не подтверждённый фриз.
    val recovered = heartbeatExecAtRecheck != heartbeatExecAtDetect || mainIdleAtRecheck
    return if (recovered) WatchdogVerdict.RECOVERED_DELAY else WatchdogVerdict.CONFIRMED_FREEZE
}

/**
 * Ловушка зависаний UI (ANR-подобных фризов главного потока).
 *
 * Полевой кейс, из-за которого переписано: на СТАРТЕ создавался ЛОЖНЫЙ отчёт
 * «main-лупер молчит 6005мс», хотя main в момент дампа стоял в холостом
 * nativePollOnce. Две причины:
 *  1) heartbeat шёл ОБЫЧНЫМ Handler'ом и залипал за sync-барьером Choreographer
 *     на старте — отметка устаревала, хотя main реально не висел;
 *  2) полный дамп (getAllStackTraces) снимался ПОЗЖЕ детекта — main успевал
 *     восстановиться, и в отчёт попадал idle-стек.
 *
 * Новая схема:
 *  - heartbeat — АСИНХРОННЫЙ Handler ([HandlerCompat.createAsync]): async-сообщения
 *    не блокируются sync-барьером; один пост в секунду, ноль аллокаций;
 *  - единый источник времени — [SystemClock.uptimeMillis];
 *  - фриз ПОДТВЕРЖДАЕТСЯ двумя проверками (см. [classifyFreeze]): при превышении
 *    порога сразу снимаем стек ТОЛЬКО main; через ~400мс повторная проверка;
 *  - ОБА вида (CONFIRMED_FREEZE и RECOVERED_DELAY) → диагностика в watchdog_diag/
 *    БЕЗ показа пользователю: фриз ≠ крэш и НЕ должен хайджекать старт экраном
 *    краша (полевой фидбек: «лог вылезает при первом открытии»). Реальные крэши
 *    (CrashHandler java_crash / нативные) показываются как раньше;
 *  - только foreground + есть RESUMED-Activity; в фоне — стоп/сброс; startup- и
 *    resumed-grace глушат ложные срабатывания на инициализации;
 *  - cooldown + дедуп по сигнатуре стека: один отчёт на один фриз.
 */
object UiWatchdog {

    private const val BEAT_MS = 1000L
    private const val CHECK_MS = 2000L
    private const val FREEZE_THRESHOLD_MS = 6000L
    private const val RECHECK_MS = 400L               // 300–500мс: окно подтверждения
    private const val COOLDOWN_MS = 30_000L
    private const val RECOVERED_COOLDOWN_MS = 10_000L
    private const val STARTUP_GRACE_MS = 10_000L      // после старта процесса
    private const val RESUMED_GRACE_MS = 8_000L       // после первого RESUMED
    private const val MAX_REPORTS = 5
    private const val MAX_DIAG_FILES = 10

    @Volatile private var started = false

    // ── Время: ВСЁ через SystemClock.uptimeMillis() ──
    private val processStartAt = AtomicLong(SystemClock.uptimeMillis())
    private val firstResumedAt = AtomicLong(0L)
    private val heartbeatPostedAt = AtomicLong(0L)
    private val heartbeatExecutedAt = AtomicLong(SystemClock.uptimeMillis())

    // ── Lifecycle ──
    private val resumedActivities = AtomicInteger(0)
    @Volatile private var currentActivityName: String = "-"
    private val monitoring = AtomicBoolean(false)

    private lateinit var appContext: Context
    private lateinit var mainHandler: Handler

    private val beat = object : Runnable {
        override fun run() {
            heartbeatExecutedAt.set(SystemClock.uptimeMillis())
            postBeat()
        }
    }

    private fun postBeat() {
        heartbeatPostedAt.set(SystemClock.uptimeMillis())
        mainHandler.postDelayed(beat, BEAT_MS)
    }

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        // АСИНХРОННЫЙ handler: heartbeat не встаёт за sync-барьер Choreographer.
        mainHandler = HandlerCompat.createAsync(Looper.getMainLooper())

        val app = appContext as? Application
        if (app != null) {
            app.registerActivityLifecycleCallbacks(lifecycle)
        } else {
            // Нет Application (напр. в тестах) — считаем, что экран активен.
            firstResumedAt.set(SystemClock.uptimeMillis())
            beginMonitoring()
        }

        watchdogThread.start()
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            currentActivityName = activity.javaClass.simpleName
            if (firstResumedAt.get() == 0L) firstResumedAt.set(SystemClock.uptimeMillis())
            if (resumedActivities.incrementAndGet() == 1) beginMonitoring()
        }
        override fun onActivityPaused(activity: Activity) {
            if (resumedActivities.decrementAndGet() <= 0) {
                resumedActivities.set(0)
                endMonitoring()
            }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** Свежий цикл: сбрасываем отметку, чтобы старый timestamp из фона не дал мгновенный ложный фриз. */
    private fun beginMonitoring() {
        heartbeatExecutedAt.set(SystemClock.uptimeMillis())
        mainHandler.removeCallbacks(beat)
        postBeat()
        monitoring.set(true)
    }

    private fun endMonitoring() {
        monitoring.set(false)
        mainHandler.removeCallbacks(beat)   // убираем pending heartbeat
    }

    private fun inGrace(now: Long): Boolean {
        if (now - processStartAt.get() < STARTUP_GRACE_MS) return true
        val fr = firstResumedAt.get()
        return fr == 0L || now - fr < RESUMED_GRACE_MS
    }

    private val watchdogThread = Thread {
        var reports = 0
        var lastLoopAt = SystemClock.uptimeMillis()
        var consecutiveMissed = 0
        var lastReportAt = 0L
        var lastSignature = ""
        while (reports < MAX_REPORTS) {
            try {
                Thread.sleep(CHECK_MS)
                val now = SystemClock.uptimeMillis()
                val loopGap = now - lastLoopAt
                lastLoopAt = now

                val silentMs = now - heartbeatExecutedAt.get()

                // Первый проход classify: только «стоит ли вообще подтверждать».
                val prelim = classifyFreeze(
                    monitoring = monitoring.get(),
                    inGrace = inGrace(now),
                    loopGapMs = loopGap,
                    checkMs = CHECK_MS,
                    silentMs = silentMs,
                    thresholdMs = FREEZE_THRESHOLD_MS,
                    // На первом проходе re-check ещё не делали — передаём равные
                    // отметки и mainIdle=false, чтобы получить не-OK при превышении.
                    heartbeatExecAtDetect = 0L,
                    heartbeatExecAtRecheck = 0L,
                    mainIdleAtRecheck = false,
                )
                if (prelim == WatchdogVerdict.OK) {
                    consecutiveMissed = 0
                    // Если процесс стоял целиком — освежаем базу, чтобы не копить долг.
                    if (loopGap > CHECK_MS * 3) heartbeatExecutedAt.set(now)
                    continue
                }

                // ── Порог превышен → подтверждение ──
                consecutiveMissed++
                val freezeDetectedAt = now
                val execAtDetect = heartbeatExecutedAt.get()
                val firstStack = safeMainStack()
                val firstStackCapturedAt = SystemClock.uptimeMillis()

                Thread.sleep(RECHECK_MS)
                val secondCheckAt = SystemClock.uptimeMillis()
                val execAtRecheck = heartbeatExecutedAt.get()

                val verdict = classifyFreeze(
                    monitoring = monitoring.get(),
                    inGrace = inGrace(secondCheckAt),
                    loopGapMs = loopGap,
                    checkMs = CHECK_MS,
                    silentMs = secondCheckAt - execAtDetect,
                    thresholdMs = FREEZE_THRESHOLD_MS,
                    heartbeatExecAtDetect = execAtDetect,
                    heartbeatExecAtRecheck = execAtRecheck,
                    mainIdleAtRecheck = mainIsIdle(),
                )
                val measuredDelayMs = secondCheckAt - execAtDetect

                when (verdict) {
                    WatchdogVerdict.RECOVERED_DELAY -> {
                        saveRecoveredDelay(
                            measuredDelayMs, freezeDetectedAt, firstStackCapturedAt,
                            secondCheckAt, consecutiveMissed, firstStack
                        )
                        heartbeatExecutedAt.set(SystemClock.uptimeMillis())
                        consecutiveMissed = 0
                        Thread.sleep(RECOVERED_COOLDOWN_MS)
                        lastLoopAt = SystemClock.uptimeMillis()
                    }
                    WatchdogVerdict.CONFIRMED_FREEZE -> {
                        val signature = firstStack.firstOrNull()
                            ?.let { "${it.className}.${it.methodName}" } ?: "?"
                        val dupe = signature == lastSignature && now - lastReportAt < COOLDOWN_MS
                        if (!dupe) {
                            dumpConfirmedFreeze(
                                measuredDelayMs, freezeDetectedAt, firstStackCapturedAt,
                                secondCheckAt, consecutiveMissed
                            )
                            reports++
                            lastReportAt = now
                            lastSignature = signature
                        }
                        consecutiveMissed = 0
                        Thread.sleep(COOLDOWN_MS)
                        lastLoopAt = SystemClock.uptimeMillis()
                    }
                    WatchdogVerdict.OK -> {
                        // main восстановился между детектом и re-check, но не по
                        // heartbeat/idle (напр. ушёл в фон / grace) — просто сбрасываем.
                        consecutiveMissed = 0
                        heartbeatExecutedAt.set(SystemClock.uptimeMillis())
                    }
                }
            } catch (_: InterruptedException) {
                return@Thread
            } catch (_: Throwable) {
                // вачдог не имеет права ронять приложение
            }
        }
    }.apply {
        isDaemon = true
        name = "lmg-ui-watchdog"
        priority = Thread.MIN_PRIORITY
    }

    private fun safeMainStack(): Array<StackTraceElement> =
        try { Looper.getMainLooper().thread.stackTrace } catch (_: Throwable) { emptyArray() }

    private fun mainIsIdle(): Boolean {
        val top = safeMainStack().firstOrNull() ?: return false
        return top.className == "android.os.MessageQueue" && top.methodName == "nativePollOnce"
    }

    private fun header(
        eventType: String,
        measuredDelayMs: Long,
        freezeDetectedAt: Long,
        firstStackCapturedAt: Long,
        secondCheckAt: Long,
        consecutiveMissed: Int,
    ): String {
        val now = SystemClock.uptimeMillis()
        val fg = monitoring.get()
        return buildString {
            append("=== LMG $eventType ===\n")
            append("eventType=$eventType\n")
            append("processUptimeMs=${now - processStartAt.get()}\n")
            append("lifecycle=${if (fg) "FOREGROUND/RESUMED" else "BACKGROUND"}\n")
            append("activity=$currentActivityName\n")
            append("heartbeatPostedAt=${heartbeatPostedAt.get()}\n")
            append("heartbeatExecutedAt=${heartbeatExecutedAt.get()}\n")
            append("freezeDetectedAt=$freezeDetectedAt\n")
            append("firstStackCapturedAt=$firstStackCapturedAt\n")
            append("secondCheckAt=$secondCheckAt\n")
            append("measuredDelayMs=$measuredDelayMs\n")
            append("consecutiveMissed=$consecutiveMissed\n")
            append("foreground=$fg\n")
            append("time=${System.currentTimeMillis()}\n\n")
        }
    }

    /** RECOVERED_DELAY: диагностика ВНЕ crash_logs — экран краша НЕ триггерится. */
    private fun saveRecoveredDelay(
        measuredDelayMs: Long,
        freezeDetectedAt: Long,
        firstStackCapturedAt: Long,
        secondCheckAt: Long,
        consecutiveMissed: Int,
        mainStack: Array<StackTraceElement>,
    ) {
        runCatching {
            val sb = StringBuilder(2048)
            sb.append(header("RECOVERED_DELAY", measuredDelayMs, freezeDetectedAt, firstStackCapturedAt, secondCheckAt, consecutiveMissed))
            sb.append("Main recovered before confirmation (heartbeat resumed or main idle at nativePollOnce).\n")
            sb.append("### MAIN STACK AT DETECTION ###\n")
            mainStack.forEach { sb.append("  at ").append(it).append('\n') }
            val dir = File(appContext.filesDir, "watchdog_diag").apply { mkdirs() }
            trimDir(dir)
            File(dir, "recovered_${System.currentTimeMillis()}.txt").writeText(sb.toString())
        }
    }

    /**
     * CONFIRMED_FREEZE: полный дамп в watchdog_diag/ — ДИАГНОСТИКА, а НЕ экран
     * краша. Фриз (даже подтверждённый) не крэш и не должен хайджекать старт
     * приложения. Реальные крэши (CrashHandler / нативные) показываются как раньше.
     */
    private fun dumpConfirmedFreeze(
        measuredDelayMs: Long,
        freezeDetectedAt: Long,
        firstStackCapturedAt: Long,
        secondCheckAt: Long,
        consecutiveMissed: Int,
    ) {
        runCatching {
            val sb = StringBuilder(16 * 1024)
            sb.append(header("CONFIRMED_FREEZE", measuredDelayMs, freezeDetectedAt, firstStackCapturedAt, secondCheckAt, consecutiveMissed))
            val all = Thread.getAllStackTraces()
            val main = Looper.getMainLooper().thread
            all[main]?.let { st ->
                sb.append("### MAIN THREAD (${main.state}) ###\n")
                st.forEach { sb.append("  at ").append(it).append('\n') }
                sb.append('\n')
            }
            for ((t, st) in all) {
                if (t === main) continue
                sb.append("--- ").append(t.name).append(" (").append(t.state).append(")\n")
                st.take(24).forEach { sb.append("  at ").append(it).append('\n') }
            }
            val dir = File(appContext.filesDir, "watchdog_diag").apply { mkdirs() }
            trimDir(dir)
            File(dir, "confirmed_freeze_${System.currentTimeMillis()}.txt").writeText(sb.toString())
        }
    }

    private fun trimDir(dir: File, max: Int = MAX_DIAG_FILES) {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            if (files.size > max) files.take(files.size - max).forEach { it.delete() }
        }
    }
}
