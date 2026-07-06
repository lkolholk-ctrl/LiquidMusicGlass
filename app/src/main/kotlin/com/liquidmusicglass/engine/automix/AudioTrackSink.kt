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

    /** Идёт ли сейчас вывод через AudioTrack-sink (для диагностики в дампе). */
    val isRunning: Boolean get() = thread != null

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

    /**
     * Остановить sink и ДОЖДАТЬСЯ смерти потока. Возвращает true, только если
     * поток гарантированно мёртв. Раньше join(1000) по таймауту молча
     * проглатывался, thread=null — а живой sink продолжал рендерить, пока
     * Oboe реопенился и перевыделял буферы: два рендерера на одной памяти,
     * порча кучи/UAF. Теперь вызывающий обязан проверять результат и НЕ
     * реопенить Oboe, если sink не умер.
     */
    fun stop(): Boolean {
        // Ждать поток НЕЛЬЗЯ под локом объекта: onLoopExit() потока тоже
        // @Synchronized — join под локом дедлочил выход цикла (поток ждал лок,
        // stop() ждал поток), 5с таймаут и «остаёмся в режиме 6» на КАЖДОЙ
        // попытке уйти с AudioTrack. Лок — только на чтение/чистку состояния.
        val t: Thread = synchronized(this) {
            running = false
            thread ?: return true
        }
        var waitedMs = 0L
        while (t.isAlive && waitedMs < 5_000L) {
            runCatching { t.join(250) }
            waitedMs += 250
        }
        return if (t.isAlive) {
            com.liquidmusicglass.debug.DebugLog.add(
                "SINK stop: поток жив после ${waitedMs}мс — реопен Oboe отложен"
            )
            false
        } else {
            synchronized(this) { if (thread === t) thread = null }
            true
        }
    }

    /** Самотерминация цикла (см. loop): вычистить состояние под локом объекта. */
    @Synchronized
    private fun onLoopExit() {
        if (thread === Thread.currentThread()) {
            thread = null
            running = false
        }
    }

    private fun createTrack(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf, BLOCK_FRAMES * 2 /*ch*/ * 2 /*bytes*/ * 4)
        return runCatching {
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
            null
        }
    }

    private fun loop() {
        // THREAD_PRIORITY_URGENT_AUDIO — как у системных аудио-потоков: пейсинг
        // стабильнее, меньше шанс underrun'а при нагрузке на UI.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }

        var track = createTrack() ?: run { running = false; return }
        runCatching { track.play() }.onFailure {
            Log.e(TAG, "AudioTrack play failed", it); running = false
            runCatching { track.release() }; return
        }

        val buf = ShortArray(BLOCK_FRAMES * 2)
        var consecutiveErrors = 0
        var lastUnderruns = 0
        var lastUnderrunCheckAt = android.os.SystemClock.elapsedRealtime()

        while (running) {
            AutoMixNativeEngine.sinkRender(buf, BLOCK_FRAMES)
            // Блокирующий write пейсит цикл под темп воспроизведения.
            val written = runCatching { track.write(buf, 0, buf.size) }.getOrDefault(-1)

            if (written < 0) {
                // ERROR_DEAD_OBJECT и т.п.: система убила трек (смена маршрута/
                // рестарт audioserver). Без обработки цикл крутился бы вхолостую
                // (тишина + сожжённый CPU). Пересоздаём на месте — позиция
                // воспроизведения не теряется (движок продолжает с того же места).
                consecutiveErrors++
                com.liquidmusicglass.debug.DebugLog.add(
                    "SINK write=$written — пересоздаю AudioTrack (#$consecutiveErrors)"
                )
                runCatching { track.release() }
                if (consecutiveErrors >= 3 || !running) break
                track = createTrack() ?: break
                if (runCatching { track.play() }.isFailure) break
                continue
            }
            consecutiveErrors = 0

            // Телеметрия underrun'ов раз в ~10с: рост = sink-поток голодает
            // (тротлинг/нагрузка) — видно в логе без гаданий.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastUnderrunCheckAt > 10_000) {
                lastUnderrunCheckAt = now
                val u = runCatching { track.underrunCount }.getOrDefault(lastUnderruns)
                if (u > lastUnderruns) {
                    com.liquidmusicglass.debug.DebugLog.add("SINK underruns +${u - lastUnderruns} (всего $u)")
                    lastUnderruns = u
                }
            }
        }

        runCatching { track.stop() }
        runCatching { track.release() }
        // Вычищаем состояние: раньше после самотерминации (3 ошибки подряд)
        // thread оставался != null → следующий start() был no-op, режим 6
        // молчал до ручного передёргивания. Теперь start() сможет перезапустить.
        onLoopExit()
    }
}
