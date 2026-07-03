package com.liquidmusicglass.engine.automix

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.liquidmusicglass.engine.AudioReactor
import com.liquidmusicglass.engine.PlaybackBackend
import com.liquidmusicglass.engine.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Haptic Music — «музыка в руке», наш ответ Music Haptics из iOS.
 *
 * Системные пути (HapticGenerator, вибро-примитивы) вендоры почти никому не
 * открывают — даже на флагманских моторах isAvailable()=false. Поэтому свой
 * детектор: нативный аудио-колбэк уже считает бас-огибающую (LP ~110 Гц) и
 * транзиенты (см. automix::noteBassLevel) — здесь они превращаются в
 * тактильные ТАПЫ с амплитудой от силы удара через createOneShot/createWaveform.
 * Работает на любом устройстве с амплитудным контролем; без него — деградирует
 * до коротких он/офф импульсов.
 *
 * Источники:
 *  - JUCE-локал: нативные hapticBeatCount/hapticBeatStrength (точнее всего);
 *  - ExoPlayer-стриминг: [AudioReactor.low] + свой детектор ударов здесь.
 *
 * Цикл опроса (25мс) живёт на своём HandlerThread ТОЛЬКО пока
 * (тумблер включён && музыка играет) — иначе поток спит, батарея не тратится.
 */
object HapticMusicEngine {

    private const val POLL_MS = 25L
    private const val BEAT_COOLDOWN_MS = 120L

    @Volatile private var vibrator: Vibrator? = null
    @Volatile private var hasAmplitude = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var running = false

    // Состояние детектора (только поток опроса).
    private var lastNativeBeats = -1L
    private var streamEnv = 0f
    private var lastBeatAtMs = 0L

    fun init(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        hasAmplitude = vibrator?.hasAmplitudeControl() == true

        // Старт/стоп цикла по связке (тумблер && играет).
        scope.launch {
            combine(
                com.liquidmusicglass.engine.AppSettings.hapticMusicEnabled,
                PlayerController.isPlaying
            ) { enabled, playing -> enabled && playing }
                .collect { active -> if (active) start() else stop() }
        }
    }

    @Synchronized
    private fun start() {
        if (running || vibrator?.hasVibrator() != true) return
        running = true
        lastNativeBeats = -1L
        streamEnv = 0f
        val t = HandlerThread("lmg-haptic").apply { start() }
        thread = t
        handler = Handler(t.looper).also { it.post(tick) }
    }

    @Synchronized
    private fun stop() {
        running = false
        handler = null
        thread?.quitSafely()
        thread = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { pollOnce() }
            handler?.postDelayed(this, POLL_MS)
        }
    }

    private fun pollOnce() {
        val juceActive = PlayerController.playbackBackend.value == PlaybackBackend.JUCE_LOCAL
        if (juceActive) {
            // Нативный детектор: удар = рост счётчика, сила уже посчитана.
            val beats = AutoMixNativeEngine.hapticBeatCount()
            if (lastNativeBeats < 0) lastNativeBeats = beats
            if (beats > lastNativeBeats) {
                lastNativeBeats = beats
                tap(AutoMixNativeEngine.hapticBeatStrength())
            }
        } else {
            // Стриминг: бас из цепочки ExoPlayer, детектор ударов свой
            // (та же математика, что в нативе: атака/спад + порог над огибающей).
            val level = AudioReactor.low.coerceIn(0f, 1f)
            val k = if (level > streamEnv) 0.5f else 0.06f
            val prevEnv = streamEnv
            streamEnv += k * (level - streamEnv)
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBeatAtMs >= BEAT_COOLDOWN_MS &&
                level > prevEnv * 1.45f + 0.02f
            ) {
                lastBeatAtMs = now
                val strength = ((level - prevEnv) / maxOf(prevEnv, 0.05f)).coerceIn(0f, 1f)
                tap(strength)
            }
        }
    }

    /** Тактильный «тап»: длительность и амплитуда следуют за силой удара. */
    private fun tap(strength: Float) {
        val v = vibrator ?: return
        val s = strength.coerceIn(0f, 1f)
        runCatching {
            if (hasAmplitude) {
                val amp = (70 + 185 * s).toInt().coerceIn(1, 255)
                val durMs = (18 + 14 * s).toLong()
                v.vibrate(VibrationEffect.createOneShot(durMs, amp))
            } else {
                // Без амплитудного контроля — короткий фиксированный тик.
                v.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }
}
