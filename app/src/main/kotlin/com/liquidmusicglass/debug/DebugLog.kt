package com.liquidmusicglass.debug

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * ВРЕМЕННЫЙ on-screen логгер для отладки без logcat/WiFi.
 *
 * Кольцевой буфер строк, который рисуется оверлеем поверх UI
 * ([com.liquidmusicglass.ui.debug.DebugOverlay]). Каждая запись параллельно
 * уходит в logcat (tag JUCELocalDbg), так что дома логи тоже есть.
 *
 * Снять после стабилизации JUCE-воспроизведения.
 */
object DebugLog {
    private const val TAG = "JUCELocalDbg"
    private const val MAX = 120
    private const val MAX_HISTORY = 2000

    private val main = Handler(Looper.getMainLooper())
    private val t0 = SystemClock.elapsedRealtime()

    /** Compose-наблюдаемый список строк (новые внизу). */
    val lines = mutableStateListOf<String>()
    /** Полная история для LCAT-просмотрщика/экспорта (панель — только хвост).
     *  Правится и читается ТОЛЬКО на главном потоке (как и [lines]). */
    private val history = ArrayDeque<String>()
    /** Счётчик версий истории — Compose пересобирает список LCAT по нему. */
    val version = mutableStateOf(0L)
    /** Видимость оверлея (тумблер в самом оверлее). По умолчанию свёрнут — UI чистый,
     *  но инструмент под рукой (чип «LOG» сверху). Снять после стабилизации. */
    val visible = mutableStateOf(false)

    fun add(msg: String) {
        val ts = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val line = String.format("%7.2f %s", ts, msg)
        android.util.Log.e(TAG, msg)
        // mutableStateList правится только на главном потоке (snapshot).
        main.post {
            lines.add(line)
            while (lines.size > MAX) lines.removeAt(0)
            history.addLast(line)
            while (history.size > MAX_HISTORY) history.removeFirst()
            version.value++
        }
    }

    /** Снимок полной истории — звать с главного потока (Compose/клик). */
    fun snapshotHistory(): List<String> = history.toList()

    fun clear() = main.post {
        lines.clear()
        history.clear()
        version.value++
    }

    /**
     * Короткий снимок вызывающих кадров — «кто дёрнул». Отбрасываем собственные
     * кадры DebugLog; показываем первые [max] значимых (класс.метод), новейший слева.
     */
    fun caller(max: Int = 6): String {
        val frames = Throwable().stackTrace
        val sb = StringBuilder()
        var count = 0
        for (f in frames) {
            if (f.className.startsWith("com.liquidmusicglass.debug.DebugLog")) continue
            val short = f.className.substringAfterLast('.') + "." + f.methodName
            if (sb.isNotEmpty()) sb.append(" <- ")
            sb.append(short)
            if (++count >= max) break
        }
        return sb.toString()
    }
}
