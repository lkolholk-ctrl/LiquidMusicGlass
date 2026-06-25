package com.liquidmusicglass.engine.vad

import android.content.Context
import org.json.JSONArray
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/**
 * VAD-лирики: детект «есть вокал / нет вокала» на устройстве, чтобы караоке-заливка
 * замирала на проигрышах. Фичи считаются 1:1 с обучением (librosa):
 *
 *   PCM(mono,44100) → кадр N_FFT=1024 (hop=512), окно Ханна → |rFFT|² (513)
 *   → mel = MEL_FB(64×513)·power → logmel = power_to_db(ref=max, top_db=80)
 *   → norm = (logmel - MEAN)/STD → окно из WIN=15 кадров → TFLite → p(вокал)
 *   → скользящее среднее SMOOTH_FRAMES=9 → гистерезис (вход <0.2 / выход >0.4)
 *   + минимальная длительность проигрыша 1.5с (короткие провалы p — шум).
 *
 * FFT берётся из того же PCM-тапа, что и бас-дым ([com.liquidmusicglass.engine.BassAudioProcessor]),
 * а не из Android Visualizer.getFft (8-бит/AGC/512 бинов — не 1:1).
 *
 * Тяжёлая часть (FFT + mel + TFLite) живёт на отдельном воркере; аудио-поток только
 * кладёт сэмплы в lock-free кольцо.
 *
 * ВНИМАНИЕ (parity): power_to_db здесь — ПО МАКСИМУМУ ТЕКУЩЕГО КАДРА (per-frame ref=max).
 * Если при обучении ref=max брался по всему клипу (global), формулу нужно согласовать.
 */
object VadLyricsEngine {

    // ─── Константы из vad_meta.json ───
    private const val SR = 44100
    private const val N_FFT = 1024
    private const val HOP = 512
    private const val N_MELS = 64
    private const val FREQ_BINS = N_FFT / 2 + 1     // 513
    private const val WIN = 15
    private const val MEAN = -24.79964516243778f
    private const val STD = 18.677518706818976f
    private const val SMOOTH_FRAMES = 9        // скользящее среднее p (8–10), не дёргается

    // Гистерезис: вход в «проигрыш» по низкому порогу, выход — по высокому.
    // Между 0.2 и 0.4 состояние не меняется → нет дребезга у порога.
    private const val ENTER_INSTRUMENTAL = 0.2f
    private const val EXIT_INSTRUMENTAL = 0.4f
    // Минимальная длительность проигрыша: короткие провалы p — шум, игнорим.
    private const val MIN_INTERLUDE_MS = 1500f
    private const val FRAME_MS = HOP * 1000f / SR   // ≈11.61мс на кадр (hop=512@44100)

    private const val TOP_DB = 80f
    private const val AMIN = 1e-10f                 // librosa power_to_db amin
    private const val LOG10 = 2.302585092994046f    // ln(10), для 10*log10 = 10/ln10 * ln

    @Volatile private var interpreter: Interpreter? = null
    private lateinit var melFb: Array<FloatArray>   // [64][513]
    @Volatile private var initialized = false

    // ─── DSP ───
    private val fft = RealFft(N_FFT)
    private val frame = FloatArray(N_FFT)
    private var frameFilled = 0
    private val power = FloatArray(FREQ_BINS)
    private val mel = FloatArray(N_MELS)
    private val window = ArrayDeque<FloatArray>()   // последние WIN нормированных кадров
    private val smooth = FloatArray(SMOOTH_FRAMES)
    private var smoothIdx = 0
    private var smoothCount = 0

    // Состояние машины «вокал/проигрыш» (гистерезис + минимальная длительность).
    private var instrumental = false
    private var belowFrames = 0

    // TFLite I/O (переиспользуемые)
    private val inputBuf: ByteBuffer =
        ByteBuffer.allocateDirect(WIN * N_MELS * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(1) }

    // ─── Lock-free SPSC кольцо для mono@44100 ───
    private const val RING = 1 shl 16               // 65536 сэмплов (~1.5с)
    private const val RING_MASK = RING - 1
    private val ring = FloatArray(RING)
    @Volatile private var ringHead = 0L             // читает воркер
    @Volatile private var ringTail = 0L             // пишет аудио-поток

    // ─── Ресемплер до 44100 (стрим, линейный) ───
    private var rsCursor = 0.0
    private var rsInCount = 0L
    private var rsPrev = 0f
    private var monoScratch = FloatArray(0)

    @Volatile private var worker: Thread? = null
    @Volatile private var running = false

    /** Ленивая инициализация модели и мел-фильтрбанка из assets (один раз, не на main). */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        try {
            val modelBytes = context.assets.open("vad_lyrics.tflite").use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            buf.put(modelBytes); buf.rewind()
            interpreter = Interpreter(buf, Interpreter.Options().setNumThreads(1))

            val json = context.assets.open("mel_fb.json").use { String(it.readBytes()) }
            melFb = parseMelFb(json)

            initialized = true
        } catch (e: Throwable) {
            android.util.Log.e("VadLyricsEngine", "init failed", e)
            interpreter = null
            initialized = false
        }
    }

    private fun parseMelFb(json: String): Array<FloatArray> {
        val arr = JSONArray(json)
        require(arr.length() == N_MELS) { "mel_fb rows ${arr.length()} != $N_MELS" }
        return Array(N_MELS) { r ->
            val row = arr.getJSONArray(r)
            require(row.length() == FREQ_BINS) { "mel_fb cols ${row.length()} != $FREQ_BINS" }
            FloatArray(FREQ_BINS) { c -> row.getDouble(c).toFloat() }
        }
    }

    /** Включить VAD: запускает воркер (загрузка модели идёт В ВОРКЕРЕ, не на main). */
    @Synchronized
    fun start(context: Context) {
        VocalState.enabled = true
        if (running) return
        resetDsp()
        running = true
        val appCtx = context.applicationContext
        worker = Thread({
            init(appCtx)
            if (interpreter == null) { running = false; return@Thread }
            loop()
        }, "vad-lyrics").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }

    /** Выключить VAD: останавливает воркер и снимает гейт. */
    @Synchronized
    fun stop() {
        VocalState.enabled = false
        VocalState.reset()
        running = false
        worker = null
        // Сбрасываем кольцо, чтобы при следующем старте не играли старые сэмплы.
        ringHead = ringTail
    }

    private fun resetDsp() {
        frameFilled = 0
        window.clear()
        smoothIdx = 0; smoothCount = 0
        instrumental = false; belowFrames = 0
        rsCursor = 0.0; rsInCount = 0L; rsPrev = 0f
        ringHead = ringTail
    }

    /**
     * Тап из аудио-потока ([BassAudioProcessor]). Даунмикс в моно, ресемпл до 44100,
     * кладём в кольцо. НИЧЕГО тяжёлого здесь — только копирование.
     */
    fun feed(shorts: ShortBuffer, channels: Int, sampleRate: Int) {
        if (!running) return
        val total = shorts.remaining()
        val ch = channels.coerceAtLeast(1)
        val frames = total / ch
        if (frames <= 0) return
        if (monoScratch.size < frames) monoScratch = FloatArray(frames)
        var w = 0
        var i = 0
        while (w < frames) {
            var acc = 0f
            var c = 0
            while (c < ch) { acc += shorts.get(i + c) / 32768f; c++ }
            monoScratch[w] = acc / ch
            w++; i += ch
        }
        if (sampleRate == SR) {
            for (k in 0 until frames) pushRing(monoScratch[k])
        } else {
            resamplePush(monoScratch, frames, sampleRate)
        }
    }

    /** Стрим-линейный ресемпл src→44100. */
    private fun resamplePush(src: FloatArray, len: Int, srcSr: Int) {
        val step = srcSr.toDouble() / SR
        // Доступны источниковые индексы [rsInCount, rsInCount+len-1]; для линейной
        // интерполяции точке cursor нужны i и i+1.
        while (true) {
            val i = floor(rsCursor).toLong()
            val nextStreamIdx = rsInCount + len            // первый недоступный индекс
            if (i + 1 > nextStreamIdx - 1) break           // не хватает правого соседа
            val frac = (rsCursor - i).toFloat()
            val leftIdx = i - rsInCount
            val left = if (leftIdx < 0) rsPrev else src[leftIdx.toInt()]
            val right = src[(leftIdx + 1).toInt()]
            pushRing(left * (1f - frac) + right * frac)
            rsCursor += step
        }
        rsPrev = src[len - 1]
        rsInCount += len
    }

    private fun pushRing(s: Float) {
        val tail = ringTail
        if (tail - ringHead < RING) {
            ring[(tail and RING_MASK.toLong()).toInt()] = s
            ringTail = tail + 1
        } // переполнение → дропаем (VAD-некритично)
    }

    private fun loop() {
        while (running) {
            // Забираем доступные сэмплы из кольца в кадр.
            var progressed = false
            while (running && ringHead < ringTail && frameFilled < N_FFT) {
                frame[frameFilled++] = ring[(ringHead and RING_MASK.toLong()).toInt()]
                ringHead += 1
                progressed = true
                if (frameFilled == N_FFT) {
                    processFrame()
                    // Сдвиг на HOP (50% перекрытие).
                    System.arraycopy(frame, HOP, frame, 0, N_FFT - HOP)
                    frameFilled = N_FFT - HOP
                }
            }
            if (!progressed) {
                try { Thread.sleep(3) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun processFrame() {
        fft.powerSpectrum(frame, power)

        // mel = MEL_FB · power
        var maxMel = AMIN
        for (m in 0 until N_MELS) {
            val fb = melFb[m]
            var acc = 0f
            for (k in 0 until FREQ_BINS) acc += fb[k] * power[k]
            mel[m] = acc
            if (acc > maxMel) maxMel = acc
        }

        // power_to_db(ref=max, top_db=80), per-frame, затем (x-MEAN)/STD
        val refDb = 10f * (ln(max(maxMel, AMIN)) / LOG10)
        val norm = FloatArray(N_MELS)
        for (m in 0 until N_MELS) {
            var db = 10f * (ln(max(mel[m], AMIN)) / LOG10) - refDb   // ≤ 0
            if (db < -TOP_DB) db = -TOP_DB
            norm[m] = (db - MEAN) / STD
        }

        window.addLast(norm)
        if (window.size > WIN) window.removeFirst()
        if (window.size == WIN) infer()
    }

    private fun infer() {
        val itp = interpreter ?: return
        inputBuf.clear()
        // layout [1, WIN, N_MELS, 1] — время-мажорно, старейший кадр первым.
        for (t in 0 until WIN) {
            val f = window.elementAt(t)
            for (m in 0 until N_MELS) inputBuf.putFloat(f[m])
        }
        inputBuf.rewind()
        try {
            itp.run(inputBuf, output)
        } catch (e: Throwable) {
            android.util.Log.e("VadLyricsEngine", "inference failed", e)
            return
        }
        val p = output[0][0].coerceIn(0f, 1f)

        // Скользящее среднее по SMOOTH_FRAMES — решение по сглаженному, не по сырому p.
        smooth[smoothIdx] = p
        smoothIdx = (smoothIdx + 1) % SMOOTH_FRAMES
        if (smoothCount < SMOOTH_FRAMES) smoothCount++
        var sum = 0f
        for (i in 0 until smoothCount) sum += smooth[i]
        val avg = sum / smoothCount
        VocalState.pVocal = avg

        // Машина состояний: гистерезис + минимальная длительность проигрыша.
        if (!instrumental) {
            // Вокал сейчас. Уходим в проигрыш только если p держится ниже ENTER
            // дольше MIN_INTERLUDE_MS (короткие провалы — шум, игнорим).
            if (avg < ENTER_INSTRUMENTAL) {
                belowFrames++
                if (belowFrames * FRAME_MS >= MIN_INTERLUDE_MS) instrumental = true
            } else {
                belowFrames = 0
            }
        } else {
            // Проигрыш сейчас. Возвращаемся к вокалу сразу, как p превысил EXIT.
            if (avg > EXIT_INSTRUMENTAL) {
                instrumental = false
                belowFrames = 0
            }
        }
        VocalState.isVocal = !instrumental
        VocalState.producing = true
    }
}
