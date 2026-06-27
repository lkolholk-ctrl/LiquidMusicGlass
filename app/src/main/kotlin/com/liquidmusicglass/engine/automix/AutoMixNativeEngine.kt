package com.liquidmusicglass.engine.automix

import android.content.Context
import android.util.Log

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

    /** Open the JUCE/Oboe output device. Returns true on success. Idempotent. */
    @Synchronized
    fun init(context: Context): Boolean {
        if (!ensureLibrary()) return false
        if (initialised) return true
        return runCatching {
            val ok = nativeInit(context.applicationContext)
            initialised = ok
            ok
        }.getOrElse {
            Log.w(TAG, "nativeInit failed", it)
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
    private external fun nativeLoadTrackB(path: String): Boolean
    private external fun nativeStartCrossfade(durationMs: Double)
    private external fun nativePrepareStretchB(bpmA: Double, bpmB: Double): Boolean
    private external fun nativeSetEntryOffsetB(ms: Double)
    private external fun nativePositionMsA(): Double
    private external fun nativeLengthMsA(): Double
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeStop()
    private external fun nativeStartTone()
    private external fun nativeStopTone()
    private external fun nativeRelease()
}
