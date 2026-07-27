package com.liquidmusicglass.engine

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

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

    /** Сколько раз собирался аудио-синк за жизнь процесса (см. лог в buildAudioSink). */
    private val sinkCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun renderersFactory(context: Context): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParameters: Boolean
            ): AudioSink {
                // Диагностика: кроссфейд форка держит два аудио-рендерера. Если
                // на один плеер синк собирается дважды, то и цепочка процессоров
                // существует в двух экземплярах — а значит, два BassAudioProcessor
                // конкурентно пишут уровни в AudioReactor, и определение
                // «уходящего» трека по порядковому номеру в DjStreamFx считает
                // новейшим не тот рендерер. Считаем и пишем в лог, чтобы знать
                // наверняка, прежде чем что-то менять.
                // Log.w, а не i/d: в релизе R8 вырезает verbose/debug/info, и
                // диагностика молча исчезла бы именно там, где нужна.
                val n = sinkCount.incrementAndGet()
                android.util.Log.w("PlayerAudioChain", "buildAudioSink #$n")
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

            // Видео-рендерер НУЖЕН: видеоклипы Apple Music играют этим же
            // сервисным ExoPlayer (mp4 → SurfaceView в FullPlayer). Раньше метод
            // был намеренно пустым («audio-only — создание видео-рендереров на
            // холодном старте цепляло startup ANR») — из-за этого клип играл
            // только звуком, а Surface оставался чёрным. Конструктор
            // MediaCodecVideoRenderer лёгкий: сам кодек инициализируется только
            // когда в потоке реально есть видео-трек, на чистом аудио оверхеда
            // нет. Если startup ANR вдруг вернётся — профилировать причину, а
            // не снова выпиливать рендерер (это молча ломает клипы).
        }
}
