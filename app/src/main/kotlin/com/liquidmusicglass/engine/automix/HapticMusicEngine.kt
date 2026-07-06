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

    // ── Бит-грид v2 (#77): PLL по онсетам ДОЗАПОЛНЯЕТ пропущенные биты ──
    // Друг просил «докрутить, чтоб под каждый бит»: онсет-детектор точный, но на
    // слабых/тихих битах тап пропадает. Решение — по онсетам оценить темп и фазу,
    // и при устойчивом бите («лок») ДОБАВЛЯТЬ тап на предсказанный бит, ЕСЛИ его
    // пропустил детектор. Живые онсет-тапы при этом НЕ глушатся (иначе теряется
    // плотность — регрессия «половина вибро пропала»). Всё на Kotlin, натив не
    // трогаем.
    private const val GRID_MIN_PERIOD = 300f   // мс = 200 BPM
    private const val GRID_MAX_PERIOD = 1000f  // мс = 60 BPM
    private const val GRID_LOCK_COUNT = 4      // столько онсетов «в сетку» → лок

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

    // Состояние бит-грида (только поток опроса).
    private var gridPeriod = 0f            // мс между битами; 0 = ещё не оценён
    private var gridNextBeat = 0L          // время следующего предсказанного бита
    private var gridConfidence = 0         // 0..8; >=GRID_LOCK_COUNT → locked
    private var gridLocked = false
    private var lastOnsetMs = 0L
    private var gridBeatStrength = 0.4f    // сглаженная сила для грид-тапа
    private val recentIoi = ArrayDeque<Float>()  // последние интер-онсет-интервалы
    private var gridBeatsFired = 0L

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
        gridPeriod = 0f
        gridNextBeat = 0L
        gridConfidence = 0
        gridLocked = false
        lastOnsetMs = 0L
        gridBeatStrength = 0.4f
        recentIoi.clear()
        gridBeatsFired = 0L
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
                val bpm = if (gridPeriod > 0f) (60000f / gridPeriod).toInt() else 0
                DebugLog.add(
                    "HAPTIC: beats=$beatsSeen taps=$tapsSent grid=%s bpm=%d fired=%d env=%.3f".format(
                        if (gridLocked) "LOCK" else "free", bpm, gridBeatsFired,
                        AutoMixNativeEngine.hapticEnv()
                    )
                )
            }
            handler?.postDelayed(this, POLL_MS)
        }
    }

    private fun pollOnce() {
        val now = android.os.SystemClock.elapsedRealtime()
        val juceActive = PlayerController.playbackBackend.value == PlaybackBackend.JUCE_LOCAL
        if (juceActive) {
            // Нативный детектор: удар = рост счётчика, сила уже посчитана.
            val beats = AutoMixNativeEngine.hapticBeatCount()
            if (lastNativeBeats < 0) lastNativeBeats = beats
            if (beats > lastNativeBeats) {
                beatsSeen += beats - lastNativeBeats
                lastNativeBeats = beats
                val strength = AutoMixNativeEngine.hapticBeatStrength()
                onOnset(now, strength)
                // Живой онсет ВСЕГДА даёт тап — это основная плотность вибрации.
                // Сетка (maybeFireGridBeat) не заменяет его, а лишь дозаполняет
                // пропущенные слабые биты (см. регрессию «половина вибро пропала»).
                tap(strength)
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
            if (now - lastBeatAtMs >= BEAT_COOLDOWN_MS &&
                level > prevEnv * trigMul + 0.02f
            ) {
                lastBeatAtMs = now
                beatsSeen++
                // Та же шкала силы, что у нативного детектора: отношение к
                // среднему качу -> ровный бит лёгкий, акценты в полную силу.
                val ratio = level / maxOf(prevEnv, 0.05f)
                val strength = ((ratio - trigMul) / 3f).coerceIn(0f, 1f)
                onOnset(now, strength)
                tap(strength)   // онсет всегда → тап; сетка лишь дозаполняет пробелы
            }
        }
        // Дозаполнить пропущенные слабые биты по сетке (поверх онсет-тапов).
        maybeFireGridBeat(now)
    }

    // ── Бит-грид v2: оценка темпа/фазы по онсетам + отдача тапов по сетке ──

    /** Онсет (удар от детектора) кормит PLL: копит IOI, оценивает период,
     *  подтягивает фазу; растит/роняет уверенность → лок. */
    private fun onOnset(now: Long, strength: Float) {
        gridBeatStrength += 0.3f * (strength.coerceIn(0f, 1f) - gridBeatStrength)

        if (lastOnsetMs > 0L) {
            var ioi = (now - lastOnsetMs).toFloat()
            // Складка октав в темп-диапазон: восьмые/половинки → к доле.
            if (ioi in 120f..1400f) {
                while (ioi < GRID_MIN_PERIOD) ioi *= 2f
                while (ioi > GRID_MAX_PERIOD) ioi /= 2f
                recentIoi.addLast(ioi)
                while (recentIoi.size > 8) recentIoi.removeFirst()
            }
        }
        lastOnsetMs = now

        if (recentIoi.size >= 4) {
            val est = medianPeriod()
            if (gridPeriod <= 0f) {
                gridPeriod = est
                gridNextBeat = now + est.toLong()
            } else {
                gridPeriod += 0.1f * (est - gridPeriod)   // мягкая адаптация темпа
            }
        }

        if (gridPeriod > 0f && gridNextBeat > 0L) {
            val err = phaseError(now)                     // now − ближайший бит, [−P/2, P/2]
            if (kotlin.math.abs(err) < gridPeriod * 0.22f) {
                gridNextBeat += (0.25f * err).toLong()     // подтягиваем фазу к онсету
                gridConfidence = (gridConfidence + 1).coerceAtMost(8)
            } else {
                gridConfidence = (gridConfidence - 1).coerceAtLeast(0)
            }
            gridLocked = gridConfidence >= GRID_LOCK_COUNT
        }
    }

    /** Если залочены и подошло время предсказанного бита — выдать тап. */
    private fun maybeFireGridBeat(now: Long) {
        // Долго нет онсетов (пауза / трек без бита) → сбросить лок, вернуться
        // в онсет-режим, чтобы не молотить по тишине.
        if (gridLocked && gridPeriod > 0f && now - lastOnsetMs > gridPeriod * 4f) {
            gridLocked = false
            gridConfidence = 0
        }
        if (!gridLocked || gridPeriod <= 0f) return
        if (now >= gridNextBeat) {
            // Сетка — ЗАПОЛНИТЕЛЬ ПРОБЕЛОВ, а не замена онсет-тапов. Если реальный
            // удар уже дал тап недавно (онсет попал в бит) — не дублируем: тап от
            // сетки идёт ТОЛЬКО когда бит был пропущен детектором (слабый/тихий).
            // Так восстанавливается плотность v1 + сохраняется «под каждый бит».
            if (now - lastBassTapAtMs > gridPeriod * 0.45f) {
                tap(gridBeatStrength.coerceIn(0.28f, 1f))
                gridBeatsFired++
            }
            gridNextBeat += gridPeriod.toLong()
            // Если отстали (лаг опроса) — досинхронизировать, без залпа догоняющих.
            if (now - gridNextBeat > gridPeriod) gridNextBeat = now + gridPeriod.toLong()
        }
    }

    private fun medianPeriod(): Float {
        val s = recentIoi.sorted()
        return s[s.size / 2]
    }

    /** Ошибка фазы: now минус ближайший предсказанный бит, в диапазоне [−P/2, P/2]. */
    private fun phaseError(now: Long): Float {
        val p = gridPeriod
        val k = Math.round((now - gridNextBeat) / p.toDouble())
        val nearest = gridNextBeat + (k * p).toLong()
        return (now - nearest).toFloat()
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
