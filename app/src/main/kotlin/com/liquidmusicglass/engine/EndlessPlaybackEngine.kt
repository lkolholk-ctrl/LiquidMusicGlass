package com.liquidmusicglass.engine

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Ядро бесконечного стриминга "Моей Волны".
 *
 * Отвечает за:
 * - Управление контекстом фоновой дозагрузки.
 * - Фоновый мониторинг очереди воспроизведения.
 * - Throttling запросов к API и предотвращение параллельных вызовов (isRefilling).
 */
@OptIn(UnstableApi::class)
class EndlessPlaybackEngine(
    private val scope: CoroutineScope,
    private val getController: () -> androidx.media3.session.MediaController?,
    private val getCompanionPlayer: () -> androidx.media3.exoplayer.ExoPlayer?
) {

    companion object {
        // Порог 8 (было 3): очередь держится «сытой» — в Up Next всегда
        // ~8-18 треков, а не «3 и обрыв» (полевой фидбек: «чтоб не было
        // пусто»). Частота запросов та же: батч 10 на каждые ~10 сыгранных.
        const val REFILL_THRESHOLD = 8
        const val REFILL_BATCH_SIZE = 10
        const val MIN_REFILL_INTERVAL_MS = 8000L
    }

    private val _refillContext = MutableStateFlow<RefillContext?>(null)
    val refillContext: StateFlow<RefillContext?> = _refillContext

    private val playedIds = mutableSetOf<String>()
    private val isRefilling = AtomicBoolean(false)
    private val lastRefillTime = AtomicLong(0L)
    private var refillCounter = 0

    data class RefillContext(
        val type: Type,
        val id: String? = null,
        val name: String? = null,
        val seedTrackId: String? = null,
        val genre: String? = null,
        val mood: String? = null,
        // Пул якорных сидов (артист-волна: топ-треки артиста). Ротация seed
        // идёт по нему, а не по хвосту очереди — станция «притягивается»
        // обратно к артисту вместо транзитивного дрейфа в соседей-соседей.
        val seedPool: List<String> = emptyList()
    ) {
        enum class Type { WAVE, ARTIST, ALBUM, SEARCH, GENRE, PLAYLIST, LIBRARY }
    }

    fun setRefillContext(context: RefillContext?) {
        _refillContext.value = context
        android.util.Log.d("EndlessEngine", "Context changed to: ${context?.type?.name ?: "null"}")
    }

    fun reset() {
        playedIds.clear()
        isRefilling.set(false)
        lastRefillTime.set(0L)
        refillCounter = 0
        _refillContext.value = null
        android.util.Log.d("EndlessEngine", "Reset complete")
    }

    fun registerTracks(trackIds: List<String>) {
        playedIds.addAll(trackIds)
        if (playedIds.size > 500) {
            playedIds.take(400).toMutableSet().also { playedIds.clear(); playedIds.addAll(it) }
        }
    }

    fun registerTrack(trackId: String) {
        playedIds.add(trackId)
    }

    /**
     * Проверяет заполненность очереди и дозагружает треки "Моей волны" при необходимости.
     *
     * @param remainingCount Количество треков, оставшихся в очереди перед активным треком.
     * @return true, если дозагрузка была успешно выполнена.
     */
    suspend fun checkAndRefillIfNeeded(remainingCount: Int = -1): Boolean {
        // Бесконечная дозаправка — ТОЛЬКО для discovery-станций (Global): «Моя
        // волна», муд-плитка, трек/артист-станция. Коллекции (плейлист/альбом/
        // артист-список/загрузки, включая ИМПОРТИРОВАННОЕ и ЛЮБИМОЕ) играются как
        // есть и НЕ подмешивают внешние рекомендации — «треки только оттуда»
        // (полевой фидбек: волна лезла в середину импортированного плейлиста и
        // подставляла по смыслу последнего трека). Инфинити-открытие живёт
        // отдельно в «Моей волне».
        if (PlayerController.playbackContext !is PlaybackContext.Global) {
            android.util.Log.d("EndlessEngine", "Bounded context (${PlayerController.playbackContext}) — no external refill")
            return false
        }

        if (isRefilling.get()) {
            android.util.Log.d("EndlessEngine", "Already refilling in background, skip")
            return false
        }

        // Calculate remaining items if not passed explicitly
        val remaining = if (remainingCount == -1) getRemainingTracks() else remainingCount
        android.util.Log.d("EndlessEngine", "Checking refill: remaining=$remaining, threshold=$REFILL_THRESHOLD")

        if (remaining >= REFILL_THRESHOLD) {
            android.util.Log.d("EndlessEngine", "Queue has sufficient tracks ($remaining >= $REFILL_THRESHOLD), skipping")
            return false
        }

        val now = System.currentTimeMillis()
        val last = lastRefillTime.get()
        if (now - last < MIN_REFILL_INTERVAL_MS) {
            android.util.Log.d("EndlessEngine", "Throttled: only ${now - last}ms elapsed since last refill (min interval=$MIN_REFILL_INTERVAL_MS)")
            return false
        }

        if (!isRefilling.compareAndSet(false, true)) {
            return false
        }

        return try {
            lastRefillTime.set(now)
            android.util.Log.d("EndlessEngine", "Starting background queue refill...")

            val newTracks = withContext(Dispatchers.IO) {
                val ctx = PlayerController.context
                if (ctx != null) {
                    val refillCtx = _refillContext.value
                    val isGlobal = PlayerController.playbackContext is PlaybackContext.Global
                    val queueIds = PlayerController.getCurrentQueue().map { it.id }
                    // Волна: seed из контекста (мудовая/трековая станция) или null
                    // (личная волна). Не-волна: станция по ПОСЛЕДНЕМУ треку
                    // очереди — альбом кончился, продолжаем «по мотивам».
                    val baseSeed = if (isGlobal) refillCtx?.seedTrackId else queueIds.lastOrNull()
                    // Ротация seed — станция как у Яндекса (пул 500+): похожих
                    // ИМЕННО на исходный трек у сервера обычно немного, зато
                    // соседей-соседей — сотни. Чередуем: нечётный рефилл строит
                    // от исходного seed (ДНК станции держится), чётный — от
                    // случайного из последних треков станции (пул расширяется
                    // транзитивно, волна блуждает по окрестностям, не по кругу).
                    // Контекстный seed при этом НЕ трогаем — исходный трек
                    // остаётся якорем станции.
                    // Артист-волна (seedPool не пуст): чётный рефилл берёт seed
                    // из пула топ-треков артиста, а не из хвоста очереди —
                    // станция постоянно «притягивается» обратно к артисту
                    // вместо транзитивного уползания в соседей-соседей.
                    val seedPool = refillCtx?.seedPool.orEmpty()
                    val seed = if (isGlobal && baseSeed != null) {
                        refillCounter++
                        when {
                            // Артист-волна: чередуем исходный сид и топ-трек
                            // артиста из пула — ОБА остаются на артисте, жанр
                            // держится.
                            seedPool.isNotEmpty() ->
                                if (refillCounter % 2 == 1) baseSeed
                                else seedPool.randomOrNull() ?: baseSeed
                            // Трек/муд-станция: держим ЯКОРЬ. Сервер строит
                            // станцию ВОКРУГ seed_track_id (по доке — это
                            // единственный рычаг жанра, параметра genre у
                            // /wave/next нет). Прежний «чётный рефилл от хвоста
                            // очереди» пересеивал станцию с соседа-соседа →
                            // за один батч уползала из жанра (хаус→рок). Разно-
                            // образие даёт растущий exclude, а не дрейф сида.
                            else -> baseSeed
                        }
                    } else baseSeed
                    // Anti-repeat: playedIds (вся история этой сессии волны) +
                    // текущая очередь (в не-Global контекстах playedIds пуст).
                    val exclude = (playedIds + queueIds).toList()
                    val waveRepo = com.liquidmusicglass.data.local.WaveRepository.getInstance(ctx)
                    var tracks = waveRepo.buildWaveQueue(
                        count = REFILL_BATCH_SIZE,
                        seedTrackId = seed,
                        exclude = exclude
                    )
                    // Станция пересохла (похожие на seed кончились) → ДРЕЙФ,
                    // как у Яндекса: строим станцию вокруг последнего трека
                    // очереди (похожие-на-похожие — пул расширяется транзитивно,
                    // «подобных очень много»). Seed в контексте обновляем, чтобы
                    // следующие рефиллы не молотили сухую станцию.
                    if (tracks.isEmpty() && seed != null) {
                        val driftSeed = queueIds.lastOrNull()?.takeIf { it != seed }
                        if (driftSeed != null) {
                            android.util.Log.w("EndlessEngine", "Station dried up (seed=$seed) — drifting to seed=$driftSeed")
                            tracks = waveRepo.buildWaveQueue(
                                count = REFILL_BATCH_SIZE,
                                seedTrackId = driftSeed,
                                exclude = exclude
                            )
                            if (tracks.isNotEmpty() && isGlobal && refillCtx != null) {
                                _refillContext.value = refillCtx.copy(seedTrackId = driftSeed)
                                com.liquidmusicglass.debug.DebugLog.add("WAVE drift seed -> $driftSeed")
                            }
                        }
                    }
                    // Дрейфовать некуда/нечем → личная волна: музыка не должна
                    // останавливаться и идти по кругу.
                    if (tracks.isEmpty() && seed != null) {
                        android.util.Log.w("EndlessEngine", "Drift dried up too — falling back to personal wave")
                        tracks = waveRepo.buildWaveQueue(
                            count = REFILL_BATCH_SIZE,
                            seedTrackId = null,
                            exclude = exclude
                        )
                        if (tracks.isNotEmpty() && isGlobal && refillCtx?.seedTrackId != null) {
                            _refillContext.value = refillCtx.copy(seedTrackId = null)
                            com.liquidmusicglass.debug.DebugLog.add("WAVE station -> personal (dried up)")
                        }
                    }
                    tracks
                } else {
                    android.util.Log.e("EndlessEngine", "Context is null, unable to fetch wave repository")
                    emptyList()
                }
            }

            if (newTracks.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    PlayerController.addTracksToQueue(newTracks)
                }
                android.util.Log.d("EndlessEngine", "Refill completed successfully: added ${newTracks.size} tracks")
                newTracks.forEach { playedIds.add(it.id) }
                true
            } else {
                android.util.Log.w("EndlessEngine", "WaveRepository returned an empty queue, refill failed")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("EndlessEngine", "Error refilling wave tracks: ${e.message}", e)
            false
        } finally {
            isRefilling.set(false)
        }
    }

    private suspend fun getRemainingTracks(): Int {
        return withContext(Dispatchers.Main) {
            val player = getController() ?: getCompanionPlayer() ?: return@withContext 0
            val total = player.mediaItemCount
            val current = player.currentMediaItemIndex
            if (total > 0 && current >= 0) (total - current) else 0
        }
    }
}
