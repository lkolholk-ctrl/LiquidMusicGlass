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

    val available: Boolean

    init {
        available = try {
            System.loadLibrary("automix_juce")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "automix_juce native library not available", t)
            false
        }
    }

    private var initialised = false

    /** Open the JUCE/Oboe output device. Returns true on success. Idempotent. */
    @Synchronized
    fun init(context: Context): Boolean {
        if (!available) return false
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
        if (!available || !initialised) return false
        return runCatching { nativeLoadTrack(path) }.getOrElse {
            Log.w(TAG, "nativeLoadTrack failed", it); false
        }
    }

    /** Load a file into deck A (crossfade source). */
    @Synchronized
    fun loadTrackA(path: String): Boolean {
        if (!available || !initialised) return false
        return runCatching { nativeLoadTrackA(path) }.getOrElse {
            Log.w(TAG, "nativeLoadTrackA failed", it); false
        }
    }

    /** Load a file into deck B (crossfade target). */
    @Synchronized
    fun loadTrackB(path: String): Boolean {
        if (!available || !initialised) return false
        return runCatching { nativeLoadTrackB(path) }.getOrElse {
            Log.w(TAG, "nativeLoadTrackB failed", it); false
        }
    }

    /** Start an equal-power crossfade A -> B over durationMs. */
    @Synchronized
    fun startCrossfade(durationMs: Double) {
        if (!available || !initialised) return
        runCatching { nativeStartCrossfade(durationMs) }
            .onFailure { Log.w(TAG, "nativeStartCrossfade failed", it) }
    }

    /** Start/resume playback of the loaded track. */
    @Synchronized
    fun play() {
        if (!available || !initialised) return
        runCatching { nativePlay() }.onFailure { Log.w(TAG, "nativePlay failed", it) }
    }

    /** Pause playback (keeps position). */
    @Synchronized
    fun pause() {
        if (!available || !initialised) return
        runCatching { nativePause() }.onFailure { Log.w(TAG, "nativePause failed", it) }
    }

    /** Stop playback and rewind to the start. */
    @Synchronized
    fun stop() {
        if (!available || !initialised) return
        runCatching { nativeStop() }.onFailure { Log.w(TAG, "nativeStop failed", it) }
    }

    /** Start the 440 Hz test tone (only audible when no track is loaded). */
    @Synchronized
    fun startTone() {
        if (!available || !initialised) return
        runCatching { nativeStartTone() }.onFailure { Log.w(TAG, "nativeStartTone failed", it) }
    }

    /** Stop the test tone (device stays open). */
    @Synchronized
    fun stopTone() {
        if (!available || !initialised) return
        runCatching { nativeStopTone() }.onFailure { Log.w(TAG, "nativeStopTone failed", it) }
    }

    /** Close the device and free the engine. */
    @Synchronized
    fun release() {
        if (!available || !initialised) return
        runCatching { nativeRelease() }.onFailure { Log.w(TAG, "nativeRelease failed", it) }
        initialised = false
    }

    private external fun nativeInit(context: Context): Boolean
    private external fun nativeLoadTrack(path: String): Boolean
    private external fun nativeLoadTrackA(path: String): Boolean
    private external fun nativeLoadTrackB(path: String): Boolean
    private external fun nativeStartCrossfade(durationMs: Double)
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeStop()
    private external fun nativeStartTone()
    private external fun nativeStopTone()
    private external fun nativeRelease()
}
