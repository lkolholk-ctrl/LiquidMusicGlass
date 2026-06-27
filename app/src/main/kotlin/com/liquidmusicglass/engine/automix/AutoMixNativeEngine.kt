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

    /** Start the 440 Hz test tone. */
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
    private external fun nativeStartTone()
    private external fun nativeStopTone()
    private external fun nativeRelease()
}
