package com.liquidmusicglass.engine.automix

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.liquidmusicglass.debug.DebugLog
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

    private const val POLL_MS = 15L   // быстрее опрос — тап ближе к биту
    private const val BEAT_COOLDOWN_MS = 120L

    @Volatile private var vibrator: Vibrator? = null
    @Volatile private var hasAmplitude = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var running = false

    // Состояние детектора (только поток опроса).
    private var lastNativeBeats = -1L
    private var lastNativeMidBeats = -1L
    private var streamEnv = 0f
    // Окно динамики низа (~3с): на «стене баса» размах мал → порог ниже.
    private var streamCeil = 0f
    private var streamFloor = 0f
    private var lastBeatAtMs = 0L
    private var lastBassTapAtMs = 0L

    // Диагностика (LOG/LCAT): сколько ударов увидели и сколько тапов реально
    // отдали системе — разводит «детектор молчит» и «система глотает вибро».
    private var beatsSeen = 0L
    private var tapsSent = 0L
    private var lastStatAtMs = 0L

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
        if (running) return
        if (vibrator?.hasVibrator() != true) {
            DebugLog.add("HAPTIC: vibrator недоступен (hasVibrator=false)")
            return
        }
        running = true
        lastNativeBeats = -1L
        lastNativeMidBeats = -1L
        streamEnv = 0f
        streamCeil = 0f
        streamFloor = 0f
        beatsSeen = 0L
        tapsSent = 0L
        lastStatAtMs = android.os.SystemClock.elapsedRealtime()
        DebugLog.add("HAPTIC start: amplitudeControl=$hasAmplitude sdk=${Build.VERSION.SDK_INT}")
        val t = HandlerThread("lmg-haptic").apply { start() }
        thread = t
        handler = Handler(t.looper).also { it.post(tick) }
    }

    @Synchronized
    private fun stop() {
        if (running)
            DebugLog.add("HAPTIC stop: beats=$beatsSeen taps=$tapsSent")
        running = false
        handler = null
        thread?.quitSafely()
        thread = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { pollOnce() }
            // Каждые 10с — счётчики в LOG: beats>0 && taps=0 -> система глотает
            // вибро; beats=0 -> молчит детектор (см. env в OBOE-дампе).
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastStatAtMs >= 10_000L) {
                lastStatAtMs = now
                DebugLog.add(
                    "HAPTIC: beats=$beatsSeen taps=$tapsSent env=%.3f".format(
                        AutoMixNativeEngine.hapticEnv()
                    )
                )
            }
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
                beatsSeen += beats - lastNativeBeats
                lastNativeBeats = beats
                tap(AutoMixNativeEngine.hapticBeatStrength())
            }
            // Средняя полоса (снейр/клэп) — лёгкие тики поверх басовых тапов.
            val midBeats = AutoMixNativeEngine.hapticMidBeatCount()
            if (lastNativeMidBeats < 0) lastNativeMidBeats = midBeats
            if (midBeats > lastNativeMidBeats) {
                beatsSeen += midBeats - lastNativeMidBeats
                lastNativeMidBeats = midBeats
                tapMid(AutoMixNativeEngine.hapticMidBeatStrength())
            }
        } else {
            // Стриминг: бас из цепочки ExoPlayer, детектор ударов свой
            // (та же математика, что в нативе: атака/спад + порог над огибающей).
            val level = AudioReactor.low.coerceIn(0f, 1f)
            val k = if (level > streamEnv) 0.5f else 0.06f
            val prevEnv = streamEnv
            streamEnv += k * (level - streamEnv)
            // Динамика окна ~3с: непрерывный компрессированный 808 («стена
            // баса», кейс Oliver Tree — Jerk) прижимает огибающую к уровню —
            // фикс-порог +45% слеп. Малый размах → порог опускается до +15%.
            streamCeil = if (level > streamCeil) level
                else streamCeil + 0.005f * (level - streamCeil)
            streamFloor = if (level < streamFloor) level
                else streamFloor + 0.005f * (level - streamFloor)
            val dyn = (streamCeil - streamFloor) / maxOf(streamCeil, 0.05f)
            val dynK = ((dyn - 0.15f) / 0.45f).coerceIn(0f, 1f)
            val trigMul = 1.15f + (1.45f - 1.15f) * dynK
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBeatAtMs >= BEAT_COOLDOWN_MS &&
                level > prevEnv * trigMul + 0.02f
            ) {
                lastBeatAtMs = now
                beatsSeen++
                // Та же шкала силы, что у нативного детектора: отношение к
                // среднему качу -> ровный бит лёгкий, акценты в полную силу.
                val ratio = level / maxOf(prevEnv, 0.05f)
                tap(((ratio - trigMul) / 3f).coerceIn(0f, 1f))
            }
        }
    }

    /**
     * Тактильный «тап»: длительность и амплитуда следуют за силой удара и
     * уровнем в настройках. Soft — только акценты (слабые удары ГЛОТАЮТСЯ,
     * не каждый кик!), лёгкие тики; Strong — каждый удар, в полную руку.
     * Полевой фидбек v1: тап на каждый кик жирным one-shot = «отбойный
     * молоток» — базовая сила снижена, добавлен гейт по силе удара.
     */
    private fun tap(strength: Float) {
        val v = vibrator ?: return
        val s = strength.coerceIn(0f, 1f)

        // Сила из детектора адаптивная — относительно СРЕДНЕГО удара трека:
        // ровный кач ~0.0-0.3, акценты/дропы 0.35+. Уровни: Medium — каждый
        // удар в 80% силы, Strong — в полную (Soft выброшен: его гейт при
        // адаптивной силе душил всё, полевой фидбек «не работает»).
        val level = com.liquidmusicglass.engine.AppSettings.hapticStrength.value
        val scale = if (level >= 2) 1.0f else 0.8f

        runCatching {
            val effect = if (hasAmplitude) {
                // Бас пожирнее (полевой фидбек), длительность короткая — стук,
                // не жужжание. База 110 (было 70): слабые тапы «ровного кача»
                // тонули в физической вибрации корпуса от динамика.
                val amp = ((110 + 145 * s) * scale).toInt().coerceIn(1, 255)
                val durMs = ((12 + 18 * s) * (0.7f + 0.3f * scale)).toLong().coerceAtLeast(9L)
                VibrationEffect.createOneShot(durMs, amp)
            } else {
                // Без амплитудного контроля — короткий фиксированный тик.
                VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            // КРИТИЧНО: без явного usage система классифицирует вибро как
            // «касания» (USAGE_UNKNOWN/TOUCH), и при выключенной в системе
            // вибрации касаний MagicOS/ColorOS молча глотают ВСЁ — «хаптика
            // не пашет вообще». USAGE_MEDIA — канал медиа, живёт отдельно.
            vibrateMedia(effect)
            tapsSent++
            lastBassTapAtMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    /**
     * Лёгкий тик средней полосы (снейр/клэп) — короче и тише басового тапа,
     * «как у Apple»: низ бьёт глубоко, середина щекочет. Бас в приоритете:
     * если только что был басовый тап — тик глотается (не месим руку кашей).
     * На Soft середина не подаётся вовсе (только акценты баса).
     */
    private fun tapMid(strength: Float) {
        val v = vibrator ?: return
        val level = com.liquidmusicglass.engine.AppSettings.hapticStrength.value
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBassTapAtMs < 70L) return
        val s = strength.coerceIn(0f, 1f)
        val scale = if (level >= 2) 1.0f else 0.8f
        runCatching {
            val effect = if (hasAmplitude) {
                val amp = ((30 + 90 * s) * scale).toInt().coerceIn(1, 255)
                VibrationEffect.createOneShot((7 + 7 * s).toLong(), amp)
            } else {
                VibrationEffect.createOneShot(8, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrateMedia(effect)
            tapsSent++
        }
    }

    private fun vibrateMedia(effect: VibrationEffect) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            v.vibrate(effect)
        }
    }
}
