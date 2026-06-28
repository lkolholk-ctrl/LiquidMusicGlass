package com.liquidmusicglass.engine.automix

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch

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
    private var initialised = false

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
        initialised = ok
        return ok
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
            latch.await()
            result
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

    /** Start an equal-power crossfade A -> B over durationMs. */
    @Synchronized
    fun startCrossfade(durationMs: Double) {
        if (!isLoaded || !initialised) return
        runCatching { nativeStartCrossfade(durationMs) }
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

    // ── Stage 8: full LOCAL player (ping-pong decks) ────────────────────────

    /** Load a track into the NON-current (incoming) deck. */
    @Synchronized
    fun loadIncoming(path: String): Boolean {
        if (!isLoaded || !initialised) return false
        return runCatching { nativeLoadIncoming(path) }.getOrElse {
            Log.w(TAG, "nativeLoadIncoming failed", it); false
        }
    }

    /** Crossfade current -> incoming over durationMs; incoming starts at entryMs. */
    @Synchronized
    fun startTransition(durationMs: Double, entryMs: Double) {
        if (!isLoaded || !initialised) return
        runCatching { nativeStartTransition(durationMs, entryMs) }
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

    /** Close the device and free the engine. */
    @Synchronized
    fun release() {
        if (!isLoaded || !initialised) return
        runCatching { nativeRelease() }.onFailure { Log.w(TAG, "nativeRelease failed", it) }
        initialised = false
    }

    private external fun nativeInit(context: Context): Boolean
    private external fun nativeLoadTrack(path: String): Boolean
    private external fun nativeLoadTrackA(path: String): Boolean
    private external fun nativeLoadTrackAFd(fd: Int, offset: Long, length: Long): Boolean
    private external fun nativeLoadTrackB(path: String): Boolean
    private external fun nativeStartCrossfade(durationMs: Double)
    private external fun nativePrepareStretchB(bpmA: Double, bpmB: Double): Boolean
    private external fun nativeSetEntryOffsetB(ms: Double)
    private external fun nativeSetEntryOffsetA(ms: Double)
    private external fun nativeClearDeckA()
    private external fun nativeSetBassSwap(enabled: Boolean)
    private external fun nativeLoadIncoming(path: String): Boolean
    private external fun nativeStartTransition(durationMs: Double, entryMs: Double)
    private external fun nativePlayCurrent()
    private external fun nativeSeekCurrent(ms: Double)
    private external fun nativePositionMsCurrent(): Double
    private external fun nativeLengthMsCurrent(): Double
    private external fun nativeIsCrossfadeActive(): Boolean
    private external fun nativeCurrentDeckIndex(): Int
    private external fun nativeClearDeck(index: Int)
    private external fun nativePositionMsA(): Double
    private external fun nativeLengthMsA(): Double
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeStop()
    private external fun nativeStartTone()
    private external fun nativeStopTone()
    private external fun nativeRelease()
}
