package com.liquidmusicglass.engine.automix

import android.content.Context
import android.net.Uri
import com.liquidmusicglass.automix.AutoMixController
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.PlayerSettings
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
 * Media3 играет трек A как обычно. Когда включён глобальный тумблер AutoMix (PlayerSettings.autoMix),
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

    private const val PREARM_LEAD_MS = 8_000L   // за сколько до cue готовить сегмент+JUCE
    private const val MARGIN_MS = 1_500L        // запас в извлекаемом окне
    private const val LOCKSTEP_MS = 500L        // B играет в JUCE и Media3 параллельно перед сменой источника
    private const val SAFETY_MS = 2_000L        // запас хвоста A, чтобы он не доиграл до нашего seek
    private const val POLL_MS = 50L
    private const val MIN_XFADE_MS = 5_000L
    private const val MAX_XFADE_MS = 30_000L

    private val engine = AutoMixNativeEngine

    @Volatile private var appContext: Context? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var controller: AutoMixController? = null
    @Volatile private var trackJob: Job? = null
    @Volatile private var armedForTrackId: String? = null
    // Идёт JUCE-свод: переходы Media3 (вызванные нашим же seek на B) не должны
    // отменять job свода и перезапускать анализ.
    @Volatile private var blending = false

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
        if (blending) return            // во время свода переход на B не рвёт job
        trackJob?.cancel()
        val s = scope ?: return
        if (track == null) return
        if (!PlayerSettings.autoMix.value) return
        if (PlayerController.isLocalJucePlaybackActive) return
        if (track.id == armedForTrackId) return
        armedForTrackId = track.id
        trackJob = s.launch { runCatching { runForTrack(track) } }
    }

    private suspend fun runForTrack(current: Track) {
        val ctx = appContext ?: return
        if (!PlayerSettings.autoMix.value) return
        if (PlayerController.isLocalJucePlaybackActive) return

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
            // Длительность B берём из каталога: иначе анализ полез бы за ней в
            // сеть по временной ссылке (лишнее соединение, и оно может отказать).
            ctrl.analyzeTrackPair(curUri, nextUri, durA, next.durationMs)
        } catch (_: Throwable) { return }
        if (!feat.readyForTransition) return

        val crossfade = feat.crossfadeDurationMs.coerceIn(MIN_XFADE_MS, MAX_XFADE_MS)
        val transitionType = feat.transitionType
        // Зажимаем cue так, чтобы у A остался хвост на ВЕСЬ свод + лок-степ + запас:
        // иначе A доиграет и Media3 сам авто-перейдёт на B до нашего seek (тогда
        // handoffPrepareNext уедет на C вместо B).
        val latestCue = (durA - crossfade - LOCKSTEP_MS - SAFETY_MS).coerceAtLeast(0L)
        val cueMs = feat.transitionStartMs.coerceIn(0L, latestCue)
        val entry = feat.entryOffsetMs

        android.util.Log.i(
            "AutoMixCoordinator",
            "armed ${current.id}->${next.id} cue=${cueMs}ms xfade=${crossfade}ms entry=${entry}ms type=$transitionType"
        )

        var armed = false
        var wavB: File? = null
        try {
            while (true) {
                if (!PlayerSettings.autoMix.value) return
                if (PlayerController.isLocalJucePlaybackActive) return
                // Трек сменился под нами (skip/ошибка) — выходим.
                if (PlayerController.currentTrack.value?.id != current.id) return
                val pos = PlayerController.audioServiceRef?.activePlaybackPositionMs()
                    ?: PlayerController.currentPositionMs.value

                if (!armed && pos >= (cueMs - PREARM_LEAD_MS).coerceAtLeast(0L)) {
                    armed = true
                    // Наложенный кроссфейд: A ОСТАЁТСЯ в Media3 и гаснет там. В JUCE
                    // грузим ТОЛЬКО начало B — оно нарастает поверх. Хвост A не
                    // извлекаем и в JUCE не кладём (нет шва на стороне A).
                    val bUri = resolveUri(next) ?: return
                    // Нужно проиграть B в JUCE на весь кроссфейд + лок-степ передачи.
                    wavB = extractHead(ctx, bUri, entry, crossfade + LOCKSTEP_MS)
                    if (wavB == null) return // fallback: обычный переход Media3

                    withContext(Dispatchers.IO) {
                        engine.init(ctx)
                        engine.clearDeckA()           // дека A пуста — A звучит из Media3
                        engine.setBassSwap(false)     // bass-swap не нужен (A не в JUCE)
                        engine.loadTrackB(wavB!!.absolutePath)
                        engine.setEntryOffsetB(0.0)   // wavB начинается с entry
                        // Без бит-матча в реальном потоке: растянутый B не совпал бы
                        // по позиции с Media3 при передаче. Нативный темп = ровный шов.
                    }
                }

                if (armed && pos >= cueMs) {
                    doBlend(crossfade, entry, transitionType)
                    return
                }
                delay(POLL_MS)
            }
        } finally {
            cleanup(wavB)
        }
    }

    private suspend fun doBlend(crossfadeMs: Long, entryMs: Long, transitionType: Int) {
        blending = true
        val resumeB = entryMs + crossfadeMs
        try {
            val svc = PlayerController.audioServiceRef
            // НАЛОЖЕННЫЙ кроссфейд: A гаснет в Media3 (cos), B нарастает в JUCE (sin)
            // ОДНОВРЕМЕННО. A НЕ паузим и не трогаем — никакого шва и паузы.
            svc?.crossfadeFadeOutA(crossfadeMs)
            withContext(Dispatchers.IO) { engine.startCrossfade(crossfadeMs.toDouble(), transitionType) }
            android.util.Log.i("AutoMixCoordinator", "overlap crossfade ${crossfadeMs}ms type=$transitionType")
            delay(crossfadeMs)

            // A уже тихий (Media3 vol≈0), B на полной в JUCE. Передаём B в Media3 БЕЗ
            // шва: Media3 встаёт на ту же позицию B и играет МОЛЧА (vol=0) в лок-степе
            // с JUCE; затем просто меняем источник — оба тот же трек на той же позиции.
            svc?.handoffPrepareNext(resumeB)   // seek на B (index+1), Media3 играет@0
            delay(LOCKSTEP_MS)
            withContext(Dispatchers.IO) { runCatching { engine.stop() } }
            svc?.handoffPlay()                 // Media3 vol=1 (B на той же позиции)
            android.util.Log.i(
                "AutoMixCoordinator",
                "hand-off done → Media3 B @ ${(resumeB + LOCKSTEP_MS) / 1000}s"
            )
        } finally {
            blending = false
        }
        // Media3 теперь на B — поставить анализ следующей пары (B→C); переход был
        // проигнорирован из-за blending, поэтому армим вручную (дедуп по id внутри).
        armNext()
    }

    /** Поставить анализ для текущего трека Media3 (B после свода). Не отменяет
     *  завершающийся job свода — просто заводит новый. Дедуп по armedForTrackId. */
    private fun armNext() {
        val s = scope ?: return
        if (!PlayerSettings.autoMix.value) return
        if (PlayerController.isLocalJucePlaybackActive) return
        val track = PlayerController.currentTrack.value ?: return
        if (track.id == armedForTrackId) return
        armedForTrackId = track.id
        trackJob = s.launch { runCatching { runForTrack(track) } }
    }

    /** Локальный трек → content://; стриминговый → свежий https:// (или null). */
    private fun resolveUri(track: Track): Uri? = try {
        if (track.isOnlineTrack) PlayerController.resolveStreamUrlSync(track.id) else track.uri
    } catch (_: Throwable) { null }

    /** Извлечь начало B: [entryMs, entryMs + playMs + запас) во временный WAV. */
    private suspend fun extractHead(ctx: Context, uri: Uri, entryMs: Long, playMs: Long): File? {
        val out = File(ctx.cacheDir, "automix_headB.wav")
        val endMs = entryMs + playMs + MARGIN_MS
        return if (SegmentExtractor.extractToWav(ctx, uri, entryMs, endMs, out)) out else null
    }

    private fun cleanup(vararg files: File?) {
        files.forEach { f -> f?.let { runCatching { it.delete() } } }
    }
}
