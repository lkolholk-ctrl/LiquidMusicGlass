package com.liquidmusicglass.engine.automix

import android.content.Context
import android.net.Uri
import com.liquidmusicglass.automix.AutoMixController
import com.liquidmusicglass.engine.AppSettings
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stage 7b/7c — встраивание JUCE-свода в РЕАЛЬНЫЙ поток воспроизведения.
 *
 * Media3 играет трек A как обычно. Когда включён флаг juceAutoMixEnabled,
 * координатор для каждой пары (текущий → следующий в очереди):
 *  1. резолвит источники (локальный content:// или стриминговый https://);
 *  2. в фоне анализирует пару моделью (Стадия 6) → точка/параметры свода;
 *  3. ближе к точке свода извлекает хвост A и начало B во временные WAV
 *     (SegmentExtractor — один путь для локального и стриминга) и лениво
 *     поднимает JUCE;
 *  4. в точке свода глушит+ставит на паузу Media3 (нет двойного звука и
 *     авто-перехода), JUCE сводит A→B (стадии 3-5);
 *  5. после свода возвращает управление Media3: переход на B + seek на
 *     позицию, где JUCE закончил (entryOffset + crossfade); JUCE освобождается.
 *
 * Источник единый и для локального, и для стриминга — отличается только
 * резолвом URI. При ЛЮБОЙ ошибке (сеть/403/декод/таймаут) — graceful
 * fallback: ничего не делаем, Media3 переходит на B обычным образом. Не падаем.
 *
 * Ленивость: JUCE поднимается только у точки свода (init+декод), не на старте.
 * Когда флаг выключен — координатор полностью бездействует, обычное
 * воспроизведение не затрагивается.
 */
object AutoMixCoordinator {

    private const val PREARM_LEAD_MS = 8_000L   // за сколько до cue готовить сегменты+JUCE
    private const val MARGIN_MS = 1_500L        // запас в извлекаемом окне
    private const val POLL_MS = 200L

    private val engine = AutoMixNativeEngine

    @Volatile private var appContext: Context? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var controller: AutoMixController? = null
    @Volatile private var trackJob: Job? = null
    @Volatile private var armedForTrackId: String? = null

    /** Запустить координатор один раз (из AudioService.onCreate). Идемпотентно. */
    fun start(context: Context) {
        if (scope != null) return
        appContext = context.applicationContext
        val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = s
        s.launch {
            PlayerController.currentTrack.collect { track -> onTrackChanged(track) }
        }
    }

    private fun onTrackChanged(track: Track?) {
        trackJob?.cancel()
        val s = scope ?: return
        if (track == null) return
        if (!AppSettings.juceAutoMixEnabled.value) return
        if (track.id == armedForTrackId) return
        armedForTrackId = track.id
        trackJob = s.launch { runCatching { runForTrack(track) } }
    }

    private suspend fun runForTrack(current: Track) {
        val ctx = appContext ?: return
        if (!AppSettings.juceAutoMixEnabled.value) return

        // Следующий трек в очереди — если нет, обычный конец, ничего не делаем.
        val queue = PlayerController.getCurrentQueue()
        val idx = PlayerController.getCurrentIndex()
        val next = queue.getOrNull(idx + 1) ?: return

        val durA = PlayerController.durationMs.value.takeIf { it > 0L } ?: current.durationMs
        if (durA <= 0L) return

        val curUri = resolveUri(current) ?: return
        val nextUri = resolveUri(next) ?: return

        // Анализ пары (Стадия 6) — модель решает точку и параметры свода.
        val ctrl = controller ?: try {
            AutoMixController(ctx).also { controller = it }
        } catch (_: Throwable) { return }

        val feat = try {
            ctrl.analyzeTrackPair(curUri, nextUri, durA)
        } catch (_: Throwable) { return }
        if (!feat.readyForTransition) return

        val crossfade = feat.crossfadeDurationMs
        val cueMs = feat.transitionStartMs.coerceIn(0L, (durA - crossfade).coerceAtLeast(0L))
        val entry = feat.entryOffsetMs
        val ba = feat.bpmA; val bb = feat.bpmB

        android.util.Log.i(
            "AutoMixCoordinator",
            "armed ${current.id}->${next.id} cue=${cueMs}ms xfade=${crossfade}ms entry=${entry}ms"
        )

        var armed = false
        var wavA: File? = null
        var wavB: File? = null
        try {
            while (true) {
                if (!AppSettings.juceAutoMixEnabled.value) return
                // Трек сменился под нами (skip/ошибка) — выходим.
                if (PlayerController.currentTrack.value?.id != current.id) return
                val pos = PlayerController.currentPositionMs.value

                if (!armed && pos >= (cueMs - PREARM_LEAD_MS).coerceAtLeast(0L)) {
                    armed = true
                    // Извлекаем хвост A и начало B (резолвим URI заново — свежий
                    // стрим-URL на случай истечения подписи).
                    val aUri = resolveUri(current) ?: return
                    val bUri = resolveUri(next) ?: return
                    wavA = extractTail(ctx, aUri, cueMs, crossfade)
                    wavB = extractHead(ctx, bUri, entry, crossfade)
                    if (wavA == null || wavB == null) return // fallback: обычный переход

                    withContext(Dispatchers.IO) {
                        engine.init(ctx)
                        engine.loadTrackA(wavA!!.absolutePath)
                        engine.loadTrackB(wavB!!.absolutePath)
                        engine.setEntryOffsetA(0.0) // wavA уже начинается с cue
                        engine.setEntryOffsetB(0.0) // wavB уже начинается с entry
                        if (ba != null && bb != null && ba > 0f && bb > 0f) {
                            engine.prepareStretchB(ba.toDouble(), bb.toDouble())
                        }
                    }
                }

                if (armed && pos >= cueMs) {
                    doBlend(crossfade, entry)
                    return
                }
                delay(POLL_MS)
            }
        } finally {
            cleanup(wavA, wavB)
        }
    }

    private suspend fun doBlend(crossfadeMs: Long, entryMs: Long) {
        val svc = PlayerController.audioServiceRef
        // 1) Заглушить + пауза Media3 (нет двойного звука и авто-перехода на B).
        svc?.handoffPause()
        // 2) JUCE сводит хвост A → начало B.
        withContext(Dispatchers.IO) { engine.startCrossfade(crossfadeMs.toDouble()) }
        android.util.Log.i("AutoMixCoordinator", "blend started ${crossfadeMs}ms")
        // 3) Ждём завершения свода.
        delay(crossfadeMs)
        // 4) Возврат управления Media3: переход на B с позиции, где JUCE закончил.
        svc?.handoffToNext(entryMs + crossfadeMs)
        // 5) Освобождаем деки JUCE до следующего свода.
        withContext(Dispatchers.IO) { runCatching { engine.stop() } }
        android.util.Log.i("AutoMixCoordinator", "hand-off done → Media3 B @ ${(entryMs + crossfadeMs) / 1000}s")
    }

    /** Локальный трек → content://; стриминговый → свежий https:// (или null). */
    private fun resolveUri(track: Track): Uri? = try {
        if (track.isOnlineTrack) PlayerController.resolveStreamUrlSync(track.id) else track.uri
    } catch (_: Throwable) { null }

    private suspend fun extractTail(ctx: Context, uri: Uri, cueMs: Long, crossfadeMs: Long): File? {
        val out = File(ctx.cacheDir, "automix_tailA.wav")
        val endMs = cueMs + crossfadeMs + MARGIN_MS
        return if (SegmentExtractor.extractToWav(ctx, uri, cueMs, endMs, out)) out else null
    }

    private suspend fun extractHead(ctx: Context, uri: Uri, entryMs: Long, crossfadeMs: Long): File? {
        val out = File(ctx.cacheDir, "automix_headB.wav")
        val endMs = entryMs + crossfadeMs + MARGIN_MS
        return if (SegmentExtractor.extractToWav(ctx, uri, entryMs, endMs, out)) out else null
    }

    private fun cleanup(vararg files: File?) {
        files.forEach { f -> f?.let { runCatching { it.delete() } } }
    }
}
