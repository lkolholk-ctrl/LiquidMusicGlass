package com.liquidmusicglass.engine

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Единая аудио-цепочка для ВСЕХ ExoPlayer'ов приложения (основной сервисный
 * и secondary в dual-player переходе):
 *
 *   BassAudioProcessor (анализ полос) → DjFxAudioProcessor (эффекты
 *   стримингового перехода) → VolumeNormalizationProcessor (Sound Check).
 *
 * Порядок важен: анализ видит чистый сигнал, эффекты — до нормализации.
 *
 * Раньше secondary-плеер собирался голым ExoPlayer.Builder: после первого же
 * стримингового AutoMix он становился основным БЕЗ цепочки — реактивное
 * свечение и нормализация умирали до перезапуска приложения.
 */
@UnstableApi
object PlayerAudioChain {

    fun renderersFactory(context: Context): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParameters: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(
                        arrayOf(
                            BassAudioProcessor(),
                            DjFxAudioProcessor(),
                            VolumeNormalizationProcessor()
                        )
                    )
                    .build()
            }

            // Audio-only: без видео-рендереров (см. комментарий в AudioService —
            // их создание на холодном старте цепляло startup ANR).
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                // intentionally empty
            }
        }
}
