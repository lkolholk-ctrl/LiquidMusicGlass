package com.liquidmusicglass.data.yandex

import android.util.Log
import com.liquidmusicglass.engine.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * «Моя волна» ЯМ — обвязка над ротором (`rotor/station/user:onyourwave`).
 *
 * Держит состояние станции (batchId для фидбека, id последнего трека для
 * продолжения контекста) и отдаёт пачки уже в модели плеера. Дозаправку
 * дергает EndlessPlaybackEngine (RefillContext.Type.YWAVE), обучение —
 * PlayerController при завершении/скипе ym_-трека.
 *
 * Все сетевые методы блокирующие — звать с IO.
 */
object YandexWaveEngine {

    private const val TAG = "YandexWave"
    const val PERSONAL_STATION = "user:onyourwave"

    /** id станции «радио по треку» для Apple/Y numeric id. */
    fun trackStation(bareTrackId: String) = "track:$bareTrackId"

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    @Volatile private var station: String = PERSONAL_STATION
    @Volatile private var batchId: String? = null
    @Volatile private var lastQueueId: String? = null

    /** Активна ли ПЕРСОНАЛЬНАЯ волна (для подсветки кнопки «Моя волна»). */
    val isPersonalActive: Boolean
        get() = _isActive.value && station == PERSONAL_STATION

    /**
     * Старт станции: первая пачка ротора + сигнал `radioStarted`.
     * [station] — `user:onyourwave` (личная волна) или `track:<id>` (радио по треку).
     * Возвращает треки в модели плеера (пустой список = не удалось).
     */
    fun start(station: String = PERSONAL_STATION): List<Track> {
        val client = YandexAuthRepository.clientOrNull() ?: return emptyList()
        val batch = client.rotorStationTracks(station)
        if (batch.tracks.isEmpty()) return emptyList()

        this.station = station
        batchId = batch.batchId
        lastQueueId = batch.tracks.last().bareTrackId
        _isActive.value = true

        runCatching {
            client.sendRotorFeedback(station, "radioStarted", batchId)
        }.onFailure { Log.w(TAG, "radioStarted feedback failed (${it.javaClass.simpleName})") }

        Log.i(TAG, "station '$station' started: ${batch.tracks.size} tracks, batch=${batchId != null}")
        return batch.tracks.map { it.toEngineTrack() }
    }

    /**
     * Следующая пачка для дозаправки очереди. [excludeIds] — то, что уже в
     * очереди/играло (engine-id вида `ym_…`), дубли отфильтровываются.
     */
    fun nextBatch(excludeIds: Set<String>): List<Track> {
        if (!_isActive.value) return emptyList()
        val client = YandexAuthRepository.clientOrNull() ?: return emptyList()
        val batch = try {
            client.rotorStationTracks(station, queue = lastQueueId)
        } catch (e: Exception) {
            Log.w(TAG, "nextBatch failed (${e.javaClass.simpleName})")
            return emptyList()
        }
        if (batch.tracks.isEmpty()) return emptyList()

        batchId = batch.batchId
        lastQueueId = batch.tracks.last().bareTrackId

        val fresh = batch.tracks
            .map { it.toEngineTrack() }
            .filter { it.id !in excludeIds }
        Log.d(TAG, "wave refill: +${fresh.size}/${batch.tracks.size}")
        return fresh
    }

    fun stop() {
        if (!_isActive.value) return
        _isActive.value = false
        station = PERSONAL_STATION
        batchId = null
        lastQueueId = null
        Log.i(TAG, "wave stopped")
    }

    /**
     * Обучение волны: дослушал → `trackFinished`, рано переключил → `skip`.
     * Зовётся из PlayerController для ym_-треков, пока волна активна.
     */
    fun onPlaybackLogged(
        trackId: String,
        playedSeconds: Double,
        skipped: Boolean,
    ) {
        if (!_isActive.value) return
        val client = YandexAuthRepository.clientOrNull() ?: return
        val bare = trackId.removePrefix(YandexDownloadManager.ID_PREFIX)
        runCatching {
            client.sendRotorFeedback(
                station = station,
                type = if (skipped) "skip" else "trackFinished",
                batchId = batchId,
                trackId = bare,
                totalPlayedSeconds = playedSeconds,
            )
        }.onFailure { Log.w(TAG, "playback feedback failed (${it.javaClass.simpleName})") }
    }
}
