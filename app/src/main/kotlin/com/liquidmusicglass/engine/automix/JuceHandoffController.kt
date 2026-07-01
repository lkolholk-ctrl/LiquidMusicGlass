package com.liquidmusicglass.engine.automix

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stage 7a — ядро hand-off Media3 ↔ JUCE на ЛОКАЛЬНЫХ файлах.
 *
 * Обычное воспроизведение трека A ведёт Media3 (ExoPlayer). Модель (Стадия 6)
 * уже решила, где переход (transitionStartMs) и какими параметрами сводить.
 * В точке свода управление плавно передаётся JUCE: он берёт ХВОСТ A (с позиции
 * cue, через setEntryOffsetA) и НАЧАЛО B (entry_offset) и исполняет свод
 * (equal-power + бит-матч + bass-swap, стадии 3-5). Media3 на это время
 * заглушён и на паузе — нет двойного звука. После свода управление
 * возвращается Media3, который продолжает трек B с позиции, где JUCE закончил
 * (entry_offset + crossfade). JUCE освобождается до следующего свода.
 *
 * Ленивость: JUCE поднимается не на старте, а только у точки свода
 * (предзагрузка за PRELOAD_LEAD_MS до cue, чтобы шов был без разрыва).
 *
 * Здесь Media3-сторона — отдельный ExoPlayer на локальной паре (DEV-отладка
 * без сети). В 7b/7c это место займут MediaStore-URI и стриминговый кеш/Range.
 *
 * @OptIn на классе: Media3 ExoPlayer помечен @UnstableApi — используем его
 * только ВНУТРИ, публичная сигнатура (handoff/release) стабильна, поэтому
 * вызывающим opt-in не нужен.
 */
@OptIn(UnstableApi::class)
class JuceHandoffController(private val appContext: Context) {

    @Volatile private var exo: ExoPlayer? = null

    /**
     * Прогон одного hand-off на локальной паре A→B. Возвращается, когда свод
     * завершён и Media3 продолжил B. Бросает CancellationException при отмене
     * (освобождая ExoPlayer). Все вызовы ExoPlayer — на главном потоке,
     * тяжёлый JUCE-декод — на IO; UI не блокируется.
     */
    suspend fun handoff(
        engine: AutoMixNativeEngine,
        pathA: String,
        pathB: String,
        transitionStartMs: Long,
        crossfadeMs: Long,
        entryOffsetMs: Long,
        transitionType: Int = 0,
        bpmA: Float?,
        bpmB: Float?,
        status: (String) -> Unit
    ) {
        // 1) Media3 играет A. Очередь из двух item'ов [A, B] — индекс 1 = B.
        val player = withContext(Dispatchers.Main) {
            val p = ExoPlayer.Builder(appContext).build()
            p.setMediaItems(
                listOf(
                    MediaItem.fromUri(Uri.fromFile(File(pathA))),
                    MediaItem.fromUri(Uri.fromFile(File(pathB)))
                )
            )
            p.prepare()
            p.playWhenReady = true
            p
        }
        exo = player
        status("Media3 playing A…")

        var preArmed = false
        try {
            while (true) {
                delay(POLL_MS)
                val pos = withContext(Dispatchers.Main) { player.currentPosition }

                // 2) Предзагрузка JUCE ЗАРАНЕЕ (тихо, пока Media3 ещё играет A),
                //    чтобы у самого шва не было паузы на декод/инициализацию.
                if (!preArmed && pos >= (transitionStartMs - PRELOAD_LEAD_MS).coerceAtLeast(0L)) {
                    preArmed = true
                    status("Pre-arming JUCE (silent)…")
                    preArm(engine, pathA, pathB, entryOffsetMs, bpmA, bpmB)
                }

                if (pos >= transitionStartMs) {
                    // 3) Точка свода. Заглушаем и ставим Media3 на паузу — JUCE
                    //    берёт хвост A с ЭТОЙ позиции (бесшовно) и сводит в B.
                    val cuePos = withContext(Dispatchers.Main) {
                        val cp = player.currentPosition
                        player.volume = 0f
                        player.pause()
                        cp
                    }
                    if (!preArmed) preArm(engine, pathA, pathB, entryOffsetMs, bpmA, bpmB)

                    status("JUCE blend ${crossfadeMs}ms (Media3 muted)…")
                    withContext(Dispatchers.IO) {
                        engine.setEntryOffsetA(cuePos.toDouble()) // дек A продолжает с cue
                        engine.startCrossfade(crossfadeMs.toDouble(), transitionType)
                    }

                    // 4) Ждём завершения свода, затем возвращаем управление Media3:
                    //    B продолжается с позиции, где JUCE закончил.
                    delay(crossfadeMs)
                    val resumeB = entryOffsetMs + crossfadeMs
                    withContext(Dispatchers.Main) {
                        player.seekTo(1, resumeB) // index 1 = трек B
                        player.volume = 1f
                        player.play()
                    }
                    // 5) Освобождаем деки JUCE до следующего свода.
                    withContext(Dispatchers.IO) { engine.stop() }
                    status("Media3 continuing B @ ${resumeB / 1000}s — hand-off done")
                    return
                }

                status("Media3 A · ${pos / 1000}s / cue ${transitionStartMs / 1000}s")
            }
        } catch (c: CancellationException) {
            withContext(NonCancellable) {
                runCatching { engine.stop() }
                releasePlayer()
            }
            throw c
        }
    }

    /** Тихая предзагрузка JUCE: устройство + декод A/B + точка входа B + бит-матч. */
    private suspend fun preArm(
        engine: AutoMixNativeEngine,
        pathA: String,
        pathB: String,
        entryOffsetMs: Long,
        bpmA: Float?,
        bpmB: Float?
    ) = withContext(Dispatchers.IO) {
        engine.init(appContext)
        engine.loadTrackA(pathA)
        engine.loadTrackB(pathB)
        engine.setEntryOffsetB(entryOffsetMs.toDouble())
        if (bpmA != null && bpmB != null && bpmA > 0f && bpmB > 0f) {
            engine.prepareStretchB(bpmA.toDouble(), bpmB.toDouble())
        }
    }

    /** Free the Media3 ExoPlayer (must be released on the main thread). */
    fun release() {
        val p = exo ?: return
        exo = null
        Handler(Looper.getMainLooper()).post { runCatching { p.release() } }
    }

    private fun releasePlayer() {
        val p = exo ?: return
        exo = null
        Handler(Looper.getMainLooper()).post { runCatching { p.release() } }
    }

    companion object {
        private const val POLL_MS = 200L
        /** Подними JUCE за столько до cue, чтобы декод не попал на шов. */
        private const val PRELOAD_LEAD_MS = 4000L
    }
}
