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

    private val main = Handler(Looper.getMainLooper())
    private val t0 = SystemClock.elapsedRealtime()

    /** Compose-наблюдаемый список строк (новые внизу). */
    val lines = mutableStateListOf<String>()
    /** Видимость оверлея (тумблер в самом оверлее). */
    val visible = mutableStateOf(true)

    fun add(msg: String) {
        val ts = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val line = String.format("%7.2f %s", ts, msg)
        android.util.Log.e(TAG, msg)
        // mutableStateList правится только на главном потоке (snapshot).
        main.post {
            lines.add(line)
            while (lines.size > MAX) lines.removeAt(0)
        }
    }

    fun clear() = main.post { lines.clear() }

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
