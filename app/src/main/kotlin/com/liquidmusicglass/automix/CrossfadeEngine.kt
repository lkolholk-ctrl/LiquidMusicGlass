package com.liquidmusicglass.automix

import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos

object CrossfadeEngine {

    suspend fun crossfadePlayers(
        fromPlayer: Player,
        toPlayer: Player,
        durationMs: Long,
        masterVolume: Float,
        stepMs: Long = 50L
    ) {
        val safeDuration = durationMs.coerceAtLeast(stepMs)
        val steps = (safeDuration / stepMs).toInt().coerceAtLeast(1)

        toPlayer.volume = 0f
        toPlayer.play()

        for (step in 0..steps) {
            val progress = (step.toFloat() / steps.toFloat()).coerceIn(0f, 1f)

            val fadeOut = cos(progress * PI.toFloat() / 2f)
            val fadeIn = cos((1f - progress) * PI.toFloat() / 2f)

            fromPlayer.volume = fadeOut * masterVolume
            toPlayer.volume = fadeIn * masterVolume

            delay(stepMs)
        }

        fromPlayer.volume = 0f
        toPlayer.volume = masterVolume
    }
}