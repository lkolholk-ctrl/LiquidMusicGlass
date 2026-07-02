package com.liquidmusicglass.engine.automix

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/**
 * AudioTrack-выход (режим 6): третий вход в аудио-систему Android.
 *
 * Для устройств, где кривые ОБА нативных пути (vivo Y35: AAudio Float — шум,
 * deep-путь — тишина, OpenSL не поднялся): Oboe-девайс закрыт, движок жив, и
 * готовые блоки тянет ЭТОТ поток и пишет в обычный Java [AudioTrack] — ровно
 * тем путём, каким играет ExoPlayer/стриминг, заведомо рабочим на этих девайсах.
 *
 * 16-бит стерео 48кГц, блок 960 кадров (20мс), буфер трека ~4 блока. Пейсинг —
 * блокирующий AudioTrack.write. Задержка ~60-100мс — для музыки неощутимо;
 * AutoMix/кроссфейды не затронуты (сводятся ДО выхода, внутри движка).
 */
object AudioTrackSink {

    private const val TAG = "AudioTrackSink"
    private const val SAMPLE_RATE = 48_000
    private const val BLOCK_FRAMES = 960              // 20 мс @ 48к

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Запустить sink (идемпотентно). Зовётся с compatExecutor движка. */
    @Synchronized
    fun start() {
        if (thread != null) return
        running = true
        thread = Thread({ loop() }, "audiotrack-sink").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Остановить sink и дождаться потока (идемпотентно). */
    @Synchronized
    fun stop() {
        running = false
        thread?.let { runCatching { it.join(1000) } }
        thread = null
    }

    private fun loop() {
        // THREAD_PRIORITY_URGENT_AUDIO — как у системных аудио-потоков: пейсинг
        // стабильнее, меньше шанс underrun'а при нагрузке на UI.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf, BLOCK_FRAMES * 2 /*ch*/ * 2 /*bytes*/ * 4)

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            Log.e(TAG, "AudioTrack create failed", it)
            running = false
            return
        }

        runCatching {
            track.play()
            val buf = ShortArray(BLOCK_FRAMES * 2)
            while (running) {
                AutoMixNativeEngine.sinkRender(buf, BLOCK_FRAMES)
                // Блокирующий write пейсит цикл под темп воспроизведения.
                track.write(buf, 0, buf.size)
            }
        }.onFailure { Log.e(TAG, "sink loop failed", it) }

        runCatching { track.stop() }
        runCatching { track.release() }
    }
}
