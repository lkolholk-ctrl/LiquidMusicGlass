package com.liquidmusicglass.automix

data class Transition(
    val shouldStart: Boolean,
    val compatibility: Float,
    val crossfadeDurationMs: Long,
    val entryOffsetMs: Long,
    val transitionType: Int,
    val transitionStartMs: Long,
    val bpmDrift: Float = 0f,
    val lowPassCurve: FloatArray? = null,
    val debugInfo: String
) {
    companion object {
        val NONE = Transition(
            shouldStart = false,
            compatibility = 0f,
            crossfadeDurationMs = 8000L,
            entryOffsetMs = 0L,
            transitionType = 0,
            transitionStartMs = 0L,
            bpmDrift = 0f,
            lowPassCurve = null,
            debugInfo = ""
        )
    }
}
