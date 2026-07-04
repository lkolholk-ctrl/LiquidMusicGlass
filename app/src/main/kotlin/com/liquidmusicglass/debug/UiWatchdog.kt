package com.liquidmusicglass.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Ловушка зависаний UI. Полевой кейс (Honor, тап по поиску): UI замирает
 * намертво, музыка играет, а в ANR-дампе Fishnet НЕТ стека главного потока —
 * виновника не видно.
 *
 * Схема: heartbeat-Runnable на main-лупере обновляет отметку раз в секунду;
 * фоновый демон замечает тишину > 6с и пишет ПОЛНЫЙ Java-дамп всех потоков
 * (Thread.getAllStackTraces — main всегда внутри) в files/crash_logs/
 * ui_freeze_*.txt — туда же, где лежат отчёты Fishnet. Стоимость в норме —
 * один post в секунду, ноль аллокаций.
 */
object UiWatchdog {

    private const val BEAT_MS = 1000L
    private const val CHECK_MS = 2500L
    private const val FREEZE_THRESHOLD_MS = 6000L
    private const val COOLDOWN_MS = 30000L
    private const val MAX_DUMPS = 5

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        val lastBeat = AtomicLong(SystemClock.uptimeMillis())
        val handler = Handler(Looper.getMainLooper())

        val beat = object : Runnable {
            override fun run() {
                lastBeat.set(SystemClock.uptimeMillis())
                handler.postDelayed(this, BEAT_MS)
            }
        }
        handler.post(beat)

        Thread {
            var dumps = 0
            while (dumps < MAX_DUMPS) {
                try {
                    Thread.sleep(CHECK_MS)
                    val silentMs = SystemClock.uptimeMillis() - lastBeat.get()
                    if (silentMs > FREEZE_THRESHOLD_MS) {
                        dumpAllThreads(appContext, silentMs)
                        dumps++
                        Thread.sleep(COOLDOWN_MS)
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
        }.start()
    }

    private fun dumpAllThreads(context: Context, silentMs: Long) {
        runCatching {
            val sb = StringBuilder(16 * 1024)
            sb.append("=== LMG UI FREEZE: main-лупер молчит ${silentMs}мс ===\n")
            sb.append("time=${System.currentTimeMillis()}\n\n")

            val all = Thread.getAllStackTraces()
            // Главный поток — первым: ради него всё и затевалось.
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

            val dir = File(context.filesDir, "crash_logs").apply { mkdirs() }
            File(dir, "ui_freeze_${System.currentTimeMillis()}.txt")
                .writeText(sb.toString())
        }
    }
}
