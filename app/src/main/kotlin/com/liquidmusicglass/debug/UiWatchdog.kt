package com.liquidmusicglass.debug

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
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
    private const val CHECK_MS = 1500L
    private const val FREEZE_THRESHOLD_MS = 3000L   // было 6000 — ловим и короткие фризы
    private const val COOLDOWN_MS = 15000L
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
            var lastCheckAt = SystemClock.uptimeMillis()
            while (dumps < MAX_DUMPS) {
                try {
                    Thread.sleep(CHECK_MS)
                    val now = SystemClock.uptimeMillis()
                    val checkGap = now - lastCheckAt
                    lastCheckAt = now

                    // Страж №1: наш СОБСТВЕННЫЙ цикл проспал сильно дольше
                    // запрошенного → процесс был заморожен целиком (cached-app
                    // freezer / доза при уходе в фон). Это не фриз UI — весь
                    // процесс стоял. Сбрасываем базу и даём main очнуться.
                    if (checkGap > CHECK_MS * 3) {
                        lastBeat.set(now)
                        continue
                    }

                    val silentMs = now - lastBeat.get()
                    if (silentMs > FREEZE_THRESHOLD_MS) {
                        val mainStack = Looper.getMainLooper().thread.stackTrace
                        val top = mainStack.firstOrNull()
                        // main стоит в холостом nativePollOnce (лупер ПУСТ) —
                        // либо разморозка после фона, либо системный/фоновый
                        // столл (аудио/io/gpu), а не блок main-кодом.
                        val mainIdle = top != null &&
                            top.className == "android.os.MessageQueue" &&
                            top.methodName == "nativePollOnce"

                        // КОРОТКАЯ сводка в in-app LCAT — доступна сразу после
                        // разморозки, без файлов и ПК: видно, что держало main
                        // (или какой фон молотил при idle-main). Именно этого не
                        // хватало на серии фризов (импорт-плейлист, лок-альбом).
                        logFreezeSummary(appContext, silentMs, mainIdle, mainStack)

                        // Полный файловый дамп — только для НАСТОЯЩЕГО блока main
                        // (застрял в коде/локе/биндере). При idle-main файл не
                        // пишем (спам), сводки в LCAT достаточно.
                        if (!mainIdle) {
                            dumpAllThreads(appContext, silentMs)
                            dumps++
                        }
                        Thread.sleep(COOLDOWN_MS)
                        lastCheckAt = SystemClock.uptimeMillis()
                        lastBeat.set(SystemClock.uptimeMillis())
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

    /**
     * Короткая строка причины фриза. Пишем В ДВА места:
     *  - in-app LCAT (DebugLog) — читается сразу после разморозки;
     *  - постоянный файл crash_logs/ui_freeze_summary.txt (append) — переживает
     *    перезапуск и открывается файловым менеджером, если фриз намертво.
     */
    private fun logFreezeSummary(
        context: Context,
        silentMs: Long,
        mainIdle: Boolean,
        mainStack: Array<StackTraceElement>
    ) {
        runCatching {
            fun short(e: StackTraceElement) = "${e.className.substringAfterLast('.')}.${e.methodName}"
            val sb = StringBuilder(320)
            sb.append("UI FREEZE ${silentMs}мс — main: ")
            if (mainIdle) {
                sb.append("idle(nativePollOnce)")
            } else {
                sb.append(mainStack.take(6).joinToString(" <- ") { short(it) })
            }
            // Самые горячие фон-потоки (RUNNABLE) — при idle-main виновник тут
            // (аудио-sink / io / декодер / сеть), как было в ANR-дампе.
            val main = Looper.getMainLooper().thread
            val hot = Thread.getAllStackTraces().entries
                .filter { it.key !== main && it.key.state == Thread.State.RUNNABLE && it.value.isNotEmpty() }
                .take(4)
                .joinToString("; ") { (t, st) -> "${t.name}:${short(st[0])}" }
            if (hot.isNotEmpty()) sb.append(" | hot: ").append(hot)
            val line = sb.toString()
            DebugLog.add(line)
            // Публичная папка Downloads (без Shizuku): одна строка на фриз.
            writeToDownloads(context, "LMG_ui_freeze.txt", "${System.currentTimeMillis()} $line\n", append = true)
        }
    }

    /**
     * Пишет/дописывает текст в ПУБЛИЧНУЮ папку Downloads через MediaStore
     * (minSdk 29). Открывается любым файловым менеджером без Shizuku — в отличие
     * от Android/data. append=true дозаписывает ("wa") в существующий файл.
     */
    private fun writeToDownloads(context: Context, fileName: String, text: String, append: Boolean) {
        runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            var uri: Uri? = null
            if (append) {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=?",
                    arrayOf(fileName),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) uri = ContentUris.withAppendedId(collection, c.getLong(0))
                }
            }
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                uri = resolver.insert(collection, values)
            }
            uri?.let { u ->
                resolver.openOutputStream(u, if (append) "wa" else "wt")?.use {
                    it.write(text.toByteArray())
                }
            }
        }
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

            // Полный дамп — тоже в Downloads (без Shizuku), отдельным файлом.
            writeToDownloads(context, "LMG_ui_freeze_${System.currentTimeMillis()}.txt", sb.toString(), append = false)
        }
    }
}
