package com.liquidmusicglass.automix

import android.net.Uri

data class TrackFeatures(
    val trackUri: Uri,

    /** Совместимость пары 0..1 */
    val compatibility: Float,

    /** Оптимальная длительность кроссфейда в мс (2000–16000) */
    val crossfadeDurationMs: Long,

    /** Смещение точки входа в следующий трек в мс */
    val entryOffsetMs: Long,

    /** Тип перехода: 0=smooth, 1=energy, 2=beat_match, 3=hard_cut, 4=filter_sweep, 5=echo_out */
    val transitionType: Int,

    /** Когда начинать переход (мс от начала текущего трека) */
    val transitionStartMs: Long,

    /** BPM текущего трека */
    val bpmA: Float?,

    /** BPM следующего трека */
    val bpmB: Float?,

    /** Тональность текущего */
    val keyA: KeyDetector.KeyResult?,

    /** Тональность следующего */
    val keyB: KeyDetector.KeyResult?,

    val bpmDrift: Float = 0f,

    val lowPassCurve: FloatArray? = null,

    /** Debug info */
    val debugInfo: String,

    /** Готовность к переходу */
    val readyForTransition: Boolean
)
