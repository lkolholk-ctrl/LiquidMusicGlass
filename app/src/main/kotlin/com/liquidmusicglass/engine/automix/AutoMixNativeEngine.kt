package com.liquidmusicglass.engine.automix

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Kotlin bridge to the native JUCE -> Oboe audio engine.
 *
 * Stage 1 ONLY: this exists to prove JUCE builds in our CI/release pipeline and
 * that JUCE can drive Oboe output (a 440 Hz test tone). It does no decoding,
 * mixing, time-stretching or model work — those are later stages.
 *
 * All native calls are wrapped in runCatching so a missing/failed library can
 * never crash the app: if libautomix_juce.so didn't load, every call is a no-op.
 */
object AutoMixNativeEngine {

    private const val TAG = "AutoMixNativeEngine"

    // 0 = not loaded yet, 1 = loaded, -1 = load failed.
    @Volatile private var libState = 0
    @Volatile private var initialised = false

    /** Режимы совместимости Oboe (значения совпадают с OboeRuntime.h). */
    const val OBOE_MODE_NORMAL = 0      // Shared + LowLatency + Float — ДЕФОЛТ
    const val OBOE_MODE_SAFE = 1        // Shared + None + Float (на части девайсов сам даёт тишину)
    const val OBOE_MODE_EXCLUSIVE = 2   // Exclusive + LowLatency + Float (сток JUCE; vivo молча даёт Shared)
    const val OBOE_MODE_NORMAL_I16 = 3  // Shared + LowLatency + int16 (HAL с битым float-микшером: vivo)
    const val OBOE_MODE_SAFE_I16 = 4    // Shared + None + int16
    const val OBOE_MODE_OPENSLES_I16 = 5 // OpenSL ES + None + int16 (запасной бэкенд, минуя AAudio)
    const val OBOE_MODE_AUDIOTRACK = 6   // Java AudioTrack-sink: третий вход, путь ExoPlayer (см. AudioTrackSink)

    // Смена режима на живом движке переоткрывает Oboe-поток (close/reopen) —
    // нельзя на main. Выделенный поток, как у AudioRouteMonitor.
    private val compatExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "oboe-compat").apply { isDaemon = true }
    }

    /**
     * Lazily load libautomix_juce.so on FIRST real use — never at app/UI start.
     * The native lib (JUCE + Signalsmith) and its AAudio device are large and
     * compete for CMA/contiguous memory at cold start; loading them on demand
     * (when the player / AutoMix is actually used) keeps the cold start light.
     */
    @Synchronized
    private fun ensureLibrary(): Boolean {
        if (libState == 1) return true
        if (libState == -1) return false
        return try {
            System.loadLibrary("automix_juce")
            libState = 1
            true
        } catch (t: Throwable) {
            Log.w(TAG, "automix_juce native library not available", t)
            libState = -1
            false
        }
    }

    /** True only if the lib is already loaded. Reading this does NOT trigger a load. */
    val isLoaded: Boolean get() = libState == 1

    /**
     * Open the JUCE/Oboe output device. Returns true on success. Idempotent.
     *
     * КРИТИЧНО: JUCE-инициализация (Thread::initialiseJUCE -> JuceActivityWatcher
     * -> getAppContext -> IsInstanceOf) ОБЯЗАНА выполняться на ГЛАВНОМ потоке —
     * только там JNIEnv корректно резолвит app-контекст/classloader и кэш
     * jclass'ов JUCE. Вызов nativeInit с фонового потока (например, из
     * AutoMixCoordinator на Dispatchers.IO) роняет процесс с
     * "JNI DETECTED ERROR: java_class == null in IsInstanceOf".
     * Поэтому nativeInit маршалится на главный поток независимо от вызывающего;
     * тяжёлый декод/свод (loadTrackA/B, startCrossfade) остаются на фоне.
     *
     * Не @Synchronized: nativeInit всегда исполняется на одном (главном) потоке,
     * значит сериализован сам по себе; держать монитор объекта во время
     * блокирующего ожидания main создало бы дедлок с другими @Synchronized
     * вызовами движка с главного потока.
     */
    fun init(context: Context): Boolean {
        if (!ensureLibrary()) return false
        if (initialised) return true
        // Сохранённый режим совместимости — ДО открытия устройства, чтобы первый
        // Oboe-поток открылся сразу с правильными sharing/performance mode.
        // Движка ещё нет → нативка просто запоминает атомик (реопена не будет).
        // Режим 6 (AudioTrack) — Kotlin-уровневый: нативке даём NORMAL, а sink
        // поднимаем после успешного init (ниже).
        val savedMode = com.liquidmusicglass.engine.AppSettings.audioCompatMode.value
        runCatching {
            nativeSetOboeCompatMode(if (savedMode == OBOE_MODE_AUDIOTRACK) OBOE_MODE_NORMAL else savedMode)
        }
        // JUCE-инициализации нужен ACTIVITY-контекст (getAppContext/JuceActivityWatcher
        // делают IsInstanceOf по android.app.Activity). applicationContext роняет это
        // с "java_class == null". Берём живую Activity из холдера (например, когда
        // init зовёт AudioService/координатор без Activity), иначе — переданный
        // контекст КАК ЕСТЬ (из Compose это уже Activity).
        val initCtx: Context = JuceContextHolder.get() ?: context
        val ok = runOnMainBlocking {
            runCatching { nativeInit(initCtx) }.getOrElse {
                Log.w(TAG, "nativeInit failed", it); false
            }
        }
        if (ok) generation.incrementAndGet()
        initialised = ok
        // Движок поднялся (лениво, на первом проигрывании) — переотправляем
        // сохранённые настройки аудио-обработки и текущий маршрут вывода.
        if (ok) {
            runCatching { com.liquidmusicglass.engine.AudioFxController.applyToEngine() }
            runCatching { com.liquidmusicglass.engine.AudioRouteMonitor.reapplyToEngine() }
            // Сохранённый AudioTrack-режим: поднять Java-sink после старта движка.
            if (savedMode == OBOE_MODE_AUDIOTRACK) setOboeCompatMode(OBOE_MODE_AUDIOTRACK)
            // Телеметрия: через 90с зафиксировать устоявшийся режим (раз в сессию).
            runCatching { AudioTelemetry.onEngineStarted() }
            // Фактические параметры открытого Oboe-потока (API/sharing/perf/format/
            // rate/burst) — в on-screen лог: видно, каким путём пошёл звук на девайсе.
            runCatching { com.liquidmusicglass.debug.DebugLog.add("QUIRKS ${AudioQuirks.describe()}") }
            runCatching { com.liquidmusicglass.debug.DebugLog.add("OBOE ${audioDiagnostics()}") }
        }
        return ok
    }

    /**
     * Режим совместимости Oboe: [OBOE_MODE_NORMAL] / [OBOE_MODE_SAFE] /
     * [OBOE_MODE_EXCLUSIVE]. До init() применять не нужно (init читает сохранённый
     * режим сам); на живом движке переоткрывает Oboe-поток на фоновом потоке —
     * позиция воспроизведения сохраняется.
     */
    fun setOboeCompatMode(mode: Int) {
        if (!isLoaded) return  // применится в init() из AppSettings
        compatExecutor.execute {
            AudioOutputWatchdog.noteDeviceReopen()
            if (mode == OBOE_MODE_AUDIOTRACK) {
                // Режим 6: закрыть Oboe-девайс, поднять Java-sink (путь ExoPlayer).
                runCatching { nativeSinkStart(48_000, 960) }
                    .onFailure { Log.w(TAG, "nativeSinkStart failed", it) }
                runCatching { AudioTrackSink.start() }
                    .onFailure { Log.w(TAG, "AudioTrackSink start failed", it) }
            } else {
                // Любой режим ≤5: сначала остановить sink (no-op, если не работал),
                // затем применить режим — реопен Oboe вернёт нативный выход.
                // КРИТИЧНО: реопенить Oboe можно только если sink-поток ТОЧНО
                // мёртв — иначе два рендерера на одних буферах (порча кучи).
                val sinkDead = runCatching { AudioTrackSink.stop() }.getOrDefault(false)
                if (sinkDead) {
                    runCatching { nativeSinkStop() }
                    runCatching { nativeSetOboeCompatMode(mode) }
                        .onFailure { Log.w(TAG, "nativeSetOboeCompatMode failed", it) }
                } else {
                    com.liquidmusicglass.debug.DebugLog.add(
                        "OBOE mode=$mode отложен: sink не остановился, остаёмся в режиме 6"
                    )
                }
            }
            runCatching { com.liquidmusicglass.debug.DebugLog.add("OBOE ${audioDiagnostics()}") }
        }
    }

    /** Рендер блока для AudioTrackSink (зовёт ТОЛЬКО его поток). */
    fun sinkRender(out: ShortArray, frames: Int) {
        if (!isLoaded) { out.fill(0); return }
        runCatching { nativeSinkRender(out, frames) }.onFailure { out.fill(0) }
    }

    /** Число вызовов аудио-колбэка с запуска (для watchdog'а). */
    fun callbackCount(): Long {
        if (!isLoaded || !initialised) return 0L
        return runCatching { nativeCallbackCount() }.getOrDefault(0L)
    }

    // ── Haptic Music: бас-огибающая и удары из нативного колбэка ──

    /** Огибающая баса 0..~1 (LP ~110 Гц, атака быстрая/спад медленный). */
    fun hapticEnv(): Float {
        if (!isLoaded || !initialised) return 0f
        return runCatching { nativeHapticEnv() }.getOrDefault(0f)
    }

    /** Счётчик ударов (кик/бас-транзиент) с запуска — растёт на каждом ударе. */
    fun hapticBeatCount(): Long {
        if (!isLoaded || !initialised) return 0L
        return runCatching { nativeHapticBeatCount() }.getOrDefault(0L)
    }

    /** Сила последнего удара 0..1 (насколько блок выше огибающей). */
    fun hapticBeatStrength(): Float {
        if (!isLoaded || !initialised) return 0f
        return runCatching { nativeHapticBeatStrength() }.getOrDefault(0f)
    }

    /** Счётчик ударов средней полосы (~200..1800 Гц: снейр/клэп). */
    fun hapticMidBeatCount(): Long {
        if (!isLoaded || !initialised) return 0L
        return runCatching { nativeHapticMidBeatCount() }.getOrDefault(0L)
    }

    /** Сила последнего среднечастотного удара 0..1. */
    fun hapticMidBeatStrength(): Float {
        if (!isLoaded || !initialised) return 0f
        return runCatching { nativeHapticMidBeatStrength() }.getOrDefault(0f)
    }

    /** Мастер-громкость 0..1 (дак при уведомлениях, фейды). Сглаживается в движке. */
    fun setPlaybackVolume(v01: Float) {
        if (!isLoaded || !initialised) return
        runCatching { nativeSetPlaybackVolume(v01) }
    }

    /** Текущий режим + последние открытые Oboe-потоки (для DebugOverlay/логов). */
    fun audioDiagnostics(): String {
        if (!isLoaded) return "native lib not loaded"
        return runCatching { nativeGetAudioDiagnostics() }.getOrDefault("n/a")
    }

    /** Выполнить [block] на главном потоке и дождаться результата (или сразу, если уже на main). */
    private fun runOnMainBlocking(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        // happens-before обеспечивает сам latch (countDown -> await), @Volatile не нужен.
        var result = false
        Handler(Looper.getMainLooper()).post {
            try { result = block() } finally { latch.countDown() }
        }
        return try {
            // С таймаутом: если main надолго занят (тяжёлый кадр, чужой ANR),
            // load-поток не должен ждать вечно. По таймауту вернём false —
            // инициализация просто повторится при следующей попытке проигрывания.
            if (latch.await(10, TimeUnit.SECONDS)) result else {
                Log.w(TAG, "runOnMainBlocking timed out waiting for main thread")
                false
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Decode-open a local audio file into deck A. Returns true on success. */
    @Synchronized
    fun loadTrack(path: String): Boolean {
        if (!isLoaded || !initialised) return false
        return runCatching { nativeLoadTrack(path) }.getOrElse {
            Log.w(TAG, "nativeLoadTrack failed", it); false
        }
    }

    /** Load a file into deck A (crossfade source). */
    @Synchronized
    fun loadTrackA(path: String): Boolean {
        if (!isLoaded || !initialised) return false
        return runCatching { nativeLoadTrackA(path) }.getOrElse {
            Log.w(TAG, "nativeLoadTrackA failed", it); false
        }
    }

    /**
     * Load deck A from a file descriptor (content:// via openFileDescriptor) — без
     * копирования в кэш. fd дублируется в нативке, поэтому вызывающий может закрыть
     * свой ParcelFileDescriptor сразу после возврата. length<=0 → определяется fstat'ом.
     */
    @Synchronized
    fun loadTrackAFd(fd: Int, offset: Long, length: Long): Boolean {
        if (!isLoaded || !initialised) return false
        return runCatching { nativeLoadTrackAFd(fd, offset, length) }.getOrElse {
            Log.w(TAG, "nativeLoadTrackAFd failed", it); false
        }
    }

    /** Load a file into deck B (crossfade target). */
    @Synchronized
    fun loadTrackB(path: String): Boolean {
        if (!isLoaded || !initialised) return false
        return runCatching { nativeLoadTrackB(path) }.getOrElse {
            Log.w(TAG, "nativeLoadTrackB failed", it); false
        }
    }

    /** Start a styled crossfade A -> B over durationMs. */
    @Synchronized
    fun startCrossfade(durationMs: Double, transitionType: Int = 0) {
        if (!isLoaded || !initialised) return
        runCatching { nativeStartCrossfade(durationMs, transitionType) }
            .onFailure { Log.w(TAG, "nativeStartCrossfade failed", it) }
    }

    /** Set deck B's entry point (model's entryOffsetMs), applied at next crossfade. */
    @Synchronized
    fun setEntryOffsetB(ms: Double) {
        if (!isLoaded || !initialised) return
        runCatching { nativeSetEntryOffsetB(ms) }
            .onFailure { Log.w(TAG, "nativeSetEntryOffsetB failed", it) }
    }

    /** Stage 7 hand-off: deck A's blend-start (the Media3 cue), applied at next crossfade. */
    @Synchronized
    fun setEntryOffsetA(ms: Double) {
        if (!isLoaded || !initialised) return
        runCatching { nativeSetEntryOffsetA(ms) }
            .onFailure { Log.w(TAG, "nativeSetEntryOffsetA failed", it) }
    }

    /** Stage 7 overlap: empty deck A so the next crossfade only fades deck B IN. */
    @Synchronized
    fun clearDeckA() {
        if (!isLoaded || !initialised) return
        runCatching { nativeClearDeckA() }.onFailure { Log.w(TAG, "nativeClearDeckA failed", it) }
    }

    /** Enable/disable Stage 5 bass-swap (off for the overlap crossfade). */
    @Synchronized
    fun setBassSwap(enabled: Boolean) {
        if (!isLoaded || !initialised) return
        runCatching { nativeSetBassSwap(enabled) }.onFailure { Log.w(TAG, "nativeSetBassSwap failed", it) }
    }

    // ── Graphic equalizer (10 bands, applied to all local JUCE audio) ───────

    /** Enable/disable the 10-band graphic EQ. */
    @Synchronized
    fun setEqEnabled(enabled: Boolean) {
        if (!isLoaded || !initialised) return
        runCatching { nativeSetEqEnabled(enabled) }.onFailure { Log.w(TAG, "nativeSetEqEnabled failed", it) }
    }

    /** Set one band's gain in dB (band 0..9, clamped to ±12 dB natively). */
    @Synchronized
    fun setEqBandGain(band: Int, gainDb: Float) {
        if (!isLoaded || !initialised) return
        runCatching { nativeSetEqBandGain(band, gainDb) }
            .onFailure { Log.w(TAG, "nativeSetEqBandGain failed", it) }
    }

    /** Set all 10 band gains at once (for presets / bulk apply). */
    @Synchronized
    fun setEqBands(gainsDb: FloatArray) {
        if (!isLoaded || !initialised) return
        runCatching { nativeSetEqBands(gainsDb) }.onFailure { Log.w(TAG, "nativeSetEqBands failed", it) }
    }

    // ── Профессиональная FX-цепочка (Preamp→EQ→Bass→Loudness→Width→Comp→Limiter) ──

    @Synchronized fun fxSetMasterEnabled(on: Boolean) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetMasterEnabled(on) }.onFailure { Log.w(TAG, "fxMaster failed", it) }
    }

    @Synchronized fun fxSetPreampGainDb(db: Float) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetPreampGainDb(db) }.onFailure { Log.w(TAG, "fxPreamp failed", it) }
    }

    @Synchronized fun fxSetBassBoost(on: Boolean, freqHz: Float, gainDb: Float) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetBassBoost(on, freqHz, gainDb) }.onFailure { Log.w(TAG, "fxBass failed", it) }
    }

    @Synchronized fun fxSetLoudnessEnabled(on: Boolean) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetLoudnessEnabled(on) }.onFailure { Log.w(TAG, "fxLoud failed", it) }
    }

    /** Текущая системная громкость (0..1) — для loudness-компенсации. Зовётся часто, не @Synchronized. */
    fun fxSetCurrentVolume(v01: Float) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetCurrentVolume(v01) }
    }

    @Synchronized fun fxSetStereoWidth(width: Float) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetStereoWidth(width) }.onFailure { Log.w(TAG, "fxWidth failed", it) }
    }

    @Synchronized fun fxSetBalance(pan: Float) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetBalance(pan) }.onFailure { Log.w(TAG, "fxBalance failed", it) }
    }

    @Synchronized fun fxSetMono(on: Boolean) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetMono(on) }.onFailure { Log.w(TAG, "fxMono failed", it) }
    }

    @Synchronized fun fxSetParamEqEnabled(on: Boolean) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetParamEqEnabled(on) }.onFailure { Log.w(TAG, "fxParamEq failed", it) }
    }

    @Synchronized fun fxSetParamBand(band: Int, freqHz: Float, q: Float, gainDb: Float) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetParamBand(band, freqHz, q, gainDb) }.onFailure { Log.w(TAG, "fxParamBand failed", it) }
    }

    @Synchronized fun fxSetReverb(on: Boolean, roomSize: Float, damping: Float, wet: Float) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetReverb(on, roomSize, damping, wet) }.onFailure { Log.w(TAG, "fxReverb failed", it) }
    }

    @Synchronized fun fxSetSaturation(on: Boolean, drive: Float) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetSaturation(on, drive) }.onFailure { Log.w(TAG, "fxSaturation failed", it) }
    }

    @Synchronized fun fxSetCompressor(on: Boolean, threshDb: Float, ratio: Float, attackMs: Float, releaseMs: Float) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetCompressor(on, threshDb, ratio, attackMs, releaseMs) }
            .onFailure { Log.w(TAG, "fxComp failed", it) }
    }

    @Synchronized fun fxSetLimiter(on: Boolean, threshDb: Float, releaseMs: Float) {
        if (!isLoaded || !initialised) return
        runCatching { nativeFxSetLimiter(on, threshDb, releaseMs) }.onFailure { Log.w(TAG, "fxLimiter failed", it) }
    }

    /**
     * Сменился маршрут вывода (BT подключён/отключён): переоткрыть Oboe-поток на
     * текущем устройстве с правильным буфером. Зовётся из AudioRouteMonitor на
     * ФОНОВОМ потоке (внутри идёт close/reopen устройства — нельзя на audio/main).
     */
    @Synchronized fun setOutputRouteBluetooth(isBluetooth: Boolean) {
        if (!isLoaded || !initialised) return
        // Реопен девайса может занять секунды (BT-стек) — watchdog не должен
        // принимать это за отказ выхода и эскалировать.
        AudioOutputWatchdog.noteDeviceReopen()
        runCatching { nativeSetOutputRouteBluetooth(isBluetooth) }
            .onFailure { Log.w(TAG, "nativeSetOutputRouteBluetooth failed", it) }
    }

    // ── Stage 8: full LOCAL player (ping-pong decks) ────────────────────────

    /** Load a track into the NON-current (incoming) deck. */
    @Synchronized
    fun loadIncoming(path: String): Boolean {
        if (!isLoaded || !initialised) return false
        return runCatching { nativeLoadIncoming(path) }.getOrElse {
            Log.w(TAG, "nativeLoadIncoming failed", it); false
        }
    }

    /** Load into the NON-current (incoming) deck по дескриптору (content:// без копии). */
    @Synchronized
    fun loadIncomingFd(fd: Int, offset: Long, length: Long): Boolean {
        if (!isLoaded || !initialised) return false
        return runCatching { nativeLoadIncomingFd(fd, offset, length) }.getOrElse {
            Log.w(TAG, "nativeLoadIncomingFd failed", it); false
        }
    }

    /** Styled crossfade current -> incoming over durationMs; incoming starts at entryMs. */
    @Synchronized
    fun startTransition(durationMs: Double, entryMs: Double, transitionType: Int = 0) {
        if (!isLoaded || !initialised) return
        runCatching { nativeStartTransition(durationMs, entryMs, transitionType) }
            .onFailure { Log.w(TAG, "nativeStartTransition failed", it) }
    }

    /** Start / resume the current deck. */
    @Synchronized
    fun playCurrent() {
        if (!isLoaded || !initialised) return
        runCatching { nativePlayCurrent() }.onFailure { Log.w(TAG, "nativePlayCurrent failed", it) }
    }

    /** Seek the current deck (ms). */
    @Synchronized
    fun seekCurrent(ms: Double) {
        if (!isLoaded || !initialised) return
        runCatching { nativeSeekCurrent(ms) }.onFailure { Log.w(TAG, "nativeSeekCurrent failed", it) }
    }

    /** Current deck position (ms). */
    fun positionMsCurrent(): Double {
        if (!isLoaded || !initialised) return 0.0
        return runCatching { nativePositionMsCurrent() }.getOrDefault(0.0)
    }

    /** Current deck length (ms). */
    fun lengthMsCurrent(): Double {
        if (!isLoaded || !initialised) return 0.0
        return runCatching { nativeLengthMsCurrent() }.getOrDefault(0.0)
    }

    /** True while a transition (crossfade) is running. */
    fun isCrossfadeActive(): Boolean {
        if (!isLoaded || !initialised) return false
        return runCatching { nativeIsCrossfadeActive() }.getOrDefault(false)
    }

    /** Which deck is current (0 = A, 1 = B). */
    fun currentDeckIndex(): Int {
        if (!isLoaded || !initialised) return 0
        return runCatching { nativeCurrentDeckIndex() }.getOrDefault(0)
    }

    /** Clear a deck by index (0 = A, 1 = B). */
    @Synchronized
    fun clearDeck(index: Int) {
        if (!isLoaded || !initialised) return
        runCatching { nativeClearDeck(index) }.onFailure { Log.w(TAG, "nativeClearDeck failed", it) }
    }

    /** Deck A current position (ms). 0 if not playing/loaded. */
    fun positionMsA(): Double {
        if (!isLoaded || !initialised) return 0.0
        return runCatching { nativePositionMsA() }.getOrDefault(0.0)
    }

    /** Deck A total length (ms). 0 if not loaded. */
    fun lengthMsA(): Double {
        if (!isLoaded || !initialised) return 0.0
        return runCatching { nativeLengthMsA() }.getOrDefault(0.0)
    }

    /**
     * Счётчик underrun'ов (xrun) Oboe-потока с момента его открытия; -1 если
     * неизвестен. Не блокирует (try_lock в нативке) — можно звать из тикера.
     * Сбрасывается при переоткрытии потока (смена маршрута BT/динамик).
     */
    fun xRunCount(): Int {
        if (!isLoaded || !initialised) return -1
        return runCatching { nativeXRunCount() }.getOrDefault(-1)
    }

    /**
     * Underrun-адаптация: телеметрия поймала рост xrun → увеличить буфер
     * (burst-множитель 6→8) и переоткрыть Oboe-поток. Внутри — close/reopen
     * устройства, поэтому ТОЛЬКО с фонового потока (@Synchronized как route).
     */
    @Synchronized
    fun escalateBufferForUnderruns() {
        if (!isLoaded || !initialised) return
        runCatching { nativeEscalateBufferForUnderruns() }
            .onFailure { Log.w(TAG, "nativeEscalateBufferForUnderruns failed", it) }
    }

    /**
     * Battery Saver вкл/выкл: движок держит максимальный буфер на время режима
     * (система тротлит CPU жёстче). Внутри реопен потока — ТОЛЬКО с фонового
     * потока (@Synchronized как route/escalate).
     */
    @Synchronized
    fun setPowerSaveMode(on: Boolean) {
        if (!isLoaded || !initialised) return
        runCatching { nativeSetPowerSaveMode(on) }
            .onFailure { Log.w(TAG, "nativeSetPowerSaveMode failed", it) }
    }

    /**
     * Pre-stretch deck B to match deck A's tempo (beat-match), pitch preserved.
     * Heavy/offline — call from a background thread BEFORE startCrossfade.
     */
    @Synchronized
    fun prepareStretchB(bpmA: Double, bpmB: Double): Boolean {
        if (!isLoaded || !initialised) return false
        return runCatching { nativePrepareStretchB(bpmA, bpmB) }.getOrElse {
            Log.w(TAG, "nativePrepareStretchB failed", it); false
        }
    }

    /** Start/resume playback of the loaded track. */
    @Synchronized
    fun play() {
        if (!isLoaded || !initialised) return
        runCatching { nativePlay() }.onFailure { Log.w(TAG, "nativePlay failed", it) }
    }

    /** Pause playback (keeps position). */
    @Synchronized
    fun pause() {
        if (!isLoaded || !initialised) return
        runCatching { nativePause() }.onFailure { Log.w(TAG, "nativePause failed", it) }
    }

    /** Immediately mute the audio callback without waiting for deck locks or load queue. */
    fun silenceOutput() {
        if (!isLoaded || !initialised) return
        runCatching { nativeSilenceOutput() }.onFailure { Log.w(TAG, "nativeSilenceOutput failed", it) }
    }

    /** Stop playback and rewind to the start. */
    @Synchronized
    fun stop() {
        if (!isLoaded || !initialised) return
        runCatching { nativeStop() }.onFailure { Log.w(TAG, "nativeStop failed", it) }
    }

    /** Start the 440 Hz test tone (only audible when no track is loaded). */
    @Synchronized
    fun startTone() {
        if (!isLoaded || !initialised) return
        runCatching { nativeStartTone() }.onFailure { Log.w(TAG, "nativeStartTone failed", it) }
    }

    /** Stop the test tone (device stays open). */
    @Synchronized
    fun stopTone() {
        if (!isLoaded || !initialised) return
        runCatching { nativeStopTone() }.onFailure { Log.w(TAG, "nativeStopTone failed", it) }
    }

    // Поколение движка: каждый успешный init() его инкрементит. Отложенный
    // release() от УМЕРШЕГО плеера (сервис пересоздан системой, старый
    // loadThread дорабатывает очередь) прилетал ПОСЛЕ init() нового плеера
    // и разрушал свежесозданный движок — тишина посреди игры до следующего
    // loadCurrent. Теперь release с чужим поколением игнорируется.
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)

    /** Текущее поколение движка — захватить после init() и передать в release(). */
    fun currentGeneration(): Long = generation.get()

    /** Close the device and free the engine. */
    @Synchronized
    fun release(expectedGeneration: Long = -1L) {
        if (!isLoaded || !initialised) return
        if (expectedGeneration >= 0L && expectedGeneration != generation.get()) {
            com.liquidmusicglass.debug.DebugLog.add(
                "ENGINE release пропущен: поколение $expectedGeneration != ${generation.get()} (движок уже пересоздан)"
            )
            return
        }
        // Sink-поток (режим 6) не должен пережить движок: реалтайм-поток
        // с живым AudioTrack продолжал бы писать тишину 50 раз/с вечно.
        runCatching { AudioTrackSink.stop() }
        runCatching { nativeRelease() }.onFailure { Log.w(TAG, "nativeRelease failed", it) }
        initialised = false
    }

    private external fun nativeInit(context: Context): Boolean
    private external fun nativeLoadTrack(path: String): Boolean
    private external fun nativeLoadTrackA(path: String): Boolean
    private external fun nativeLoadTrackAFd(fd: Int, offset: Long, length: Long): Boolean
    private external fun nativeLoadTrackB(path: String): Boolean
    private external fun nativeStartCrossfade(durationMs: Double, transitionType: Int)
    private external fun nativePrepareStretchB(bpmA: Double, bpmB: Double): Boolean
    private external fun nativeSetEntryOffsetB(ms: Double)
    private external fun nativeSetEntryOffsetA(ms: Double)
    private external fun nativeClearDeckA()
    private external fun nativeSetBassSwap(enabled: Boolean)
    private external fun nativeSetEqEnabled(enabled: Boolean)
    private external fun nativeSetEqBandGain(band: Int, gainDb: Float)
    private external fun nativeSetEqBands(gainsDb: FloatArray)
    private external fun nativeFxSetMasterEnabled(on: Boolean)
    private external fun nativeFxSetPreampGainDb(db: Float)
    private external fun nativeFxSetBassBoost(on: Boolean, freqHz: Float, gainDb: Float)
    private external fun nativeFxSetLoudnessEnabled(on: Boolean)
    private external fun nativeFxSetCurrentVolume(v01: Float)
    private external fun nativeFxSetStereoWidth(width: Float)
    private external fun nativeFxSetBalance(pan: Float)
    private external fun nativeFxSetMono(on: Boolean)
    private external fun nativeFxSetParamEqEnabled(on: Boolean)
    private external fun nativeFxSetParamBand(band: Int, freqHz: Float, q: Float, gainDb: Float)
    private external fun nativeFxSetReverb(on: Boolean, roomSize: Float, damping: Float, wet: Float)
    private external fun nativeFxSetSaturation(on: Boolean, drive: Float)
    private external fun nativeFxSetCompressor(on: Boolean, threshDb: Float, ratio: Float, attackMs: Float, releaseMs: Float)
    private external fun nativeFxSetLimiter(on: Boolean, threshDb: Float, releaseMs: Float)
    private external fun nativeSetOutputRouteBluetooth(isBluetooth: Boolean)
    private external fun nativeSetOboeCompatMode(mode: Int)
    private external fun nativeGetAudioDiagnostics(): String
    private external fun nativeSinkStart(sampleRate: Int, blockFrames: Int)
    private external fun nativeSinkRender(out: ShortArray, frames: Int)
    private external fun nativeSinkStop()
    private external fun nativeCallbackCount(): Long
    private external fun nativeHapticEnv(): Float
    private external fun nativeHapticBeatCount(): Long
    private external fun nativeHapticBeatStrength(): Float
    private external fun nativeHapticMidBeatCount(): Long
    private external fun nativeHapticMidBeatStrength(): Float
    private external fun nativeSetPlaybackVolume(v01: Float)
    private external fun nativeLoadIncoming(path: String): Boolean
    private external fun nativeLoadIncomingFd(fd: Int, offset: Long, length: Long): Boolean
    private external fun nativeStartTransition(durationMs: Double, entryMs: Double, transitionType: Int)
    private external fun nativePlayCurrent()
    private external fun nativeSeekCurrent(ms: Double)
    private external fun nativePositionMsCurrent(): Double
    private external fun nativeLengthMsCurrent(): Double
    private external fun nativeIsCrossfadeActive(): Boolean
    private external fun nativeCurrentDeckIndex(): Int
    private external fun nativeClearDeck(index: Int)
    private external fun nativePositionMsA(): Double
    private external fun nativeLengthMsA(): Double
    private external fun nativeXRunCount(): Int
    private external fun nativeEscalateBufferForUnderruns()
    private external fun nativeSetPowerSaveMode(on: Boolean)
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeSilenceOutput()
    private external fun nativeStop()
    private external fun nativeStartTone()
    private external fun nativeStopTone()
    private external fun nativeRelease()
}
