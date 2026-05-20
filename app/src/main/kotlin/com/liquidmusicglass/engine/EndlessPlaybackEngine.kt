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
 * Упрощённое ядро бесконечного стриминга.
 *
 * ПРИНЦИП:
 * - Никакого фонового мониторинга.
 * - Refill вызывается ТОЛЬКО из playFromList() с задержкой 3 сек после старта.
 * - В onMediaItemTransition НЕТ вызовов refill — только UI-обновления.
 * - Все сетевые запросы строго в Dispatchers.IO.
 * - Дедупликация перед добавлением.
 */
@OptIn(UnstableApi::class)
internal class EndlessPlaybackEngine(
    private val scope: CoroutineScope,
    private val getController: () -> androidx.media3.session.MediaController?,
    private val getCompanionPlayer: () -> androidx.media3.exoplayer.ExoPlayer?
) {

    companion object {
        const val REFILL_THRESHOLD = 3
        const val REFILL_BATCH_SIZE = 10
        const val MIN_REFILL_INTERVAL_MS = 8000L
    }

    private val _refillContext = MutableStateFlow<RefillContext?>(null)
    val refillContext: StateFlow<RefillContext?> = _refillContext

    private val playedIds = mutableSetOf<String>()
    private val isRefilling = AtomicBoolean(false)
    private val lastRefillTime = AtomicLong(0L)

    data class RefillContext(
        val type: Type,
        val id: String? = null,
        val name: String? = null,
        val seedTrackId: String? = null,
        val genre: String? = null
    ) {
        enum class Type { WAVE, ARTIST, ALBUM, SEARCH, GENRE, PLAYLIST, LIBRARY }
    }

    fun setRefillContext(context: RefillContext?) {
        _refillContext.value = context
        android.util.Log.d("EndlessEngine", "Context: ${context?.type?.name ?: "null"}")
    }

    fun reset() {
        playedIds.clear()
        isRefilling.set(false)
        lastRefillTime.set(0L)
        _refillContext.value = null
        android.util.Log.d("EndlessEngine", "Reset")
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
     * ЕДИНСТВЕННЫЙ публичный метод для refill.
     * Вызывается из playFromList() с задержкой.
     */
    suspend fun checkAndRefillIfNeeded(): Boolean {
        if (isRefilling.get()) {
            android.util.Log.d("EndlessEngine", "Already refilling, skip")
            return false
        }

        val remaining = getRemainingTracks()
        android.util.Log.d("EndlessEngine", "Check refill: remaining=$remaining, threshold=$REFILL_THRESHOLD")

        if (remaining >= REFILL_THRESHOLD) {
            android.util.Log.d("EndlessEngine", "Queue sufficient, skip refill")
            return false
        }

        val now = System.currentTimeMillis()
        val last = lastRefillTime.get()
        if (now - last < MIN_REFILL_INTERVAL_MS) {
            android.util.Log.d("EndlessEngine", "Throttled: ${now - last}ms < $MIN_REFILL_INTERVAL_MS")
            return false
        }

        if (!isRefilling.compareAndSet(false, true)) {
            return false
        }

        return try {
            lastRefillTime.set(now)
            android.util.Log.d("EndlessEngine", "Starting refill...")

            val newTracks = withContext(Dispatchers.IO) {
                fetchWaveTracks()
            }

            if (newTracks.isNotEmpty()) {
                addTracksToQueue(newTracks)
                android.util.Log.d("EndlessEngine", "Refill done: +${newTracks.size} tracks")
                true
            } else {
                android.util.Log.w("EndlessEngine", "No tracks fetched")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("EndlessEngine", "Refill error: ${e.message}")
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

    /**
     * Запрос wave-треков из ICM API.
     * Выполняется в Dispatchers.IO.
     */
    private suspend fun fetchWaveTracks(): List<Track> {
        val tracks = mutableListOf<Track>()
        val excludeIds = buildExcludeSet()
        val seedTrackId = _refillContext.value?.seedTrackId

        repeat(REFILL_BATCH_SIZE) {
            try {
                val response = com.liquidmusicglass.api.icm.IcmRepository.getWaveNext(
                    seedTrackId = seedTrackId.takeIf { tracks.isEmpty() },
                    exclude = excludeIds.toList().takeIf { it.isNotEmpty() },
                    recentSkips = 0
                )

                if (response == null || response.status != "ok") {
                    return@repeat
                }

                val waveTrack = response.track ?: return@repeat

                if (waveTrack.id in excludeIds || waveTrack.id in playedIds) {
                    return@repeat
                }

                val track = Track(
                    id = waveTrack.id,
                    title = waveTrack.title,
                    artist = waveTrack.artist ?: "Unknown Artist",
                    albumName = "",
                    uri = Uri.parse("https://byicloud.online/track/${waveTrack.id}"),
                    durationMs = waveTrack.durationMs,
                    albumId = waveTrack.collectionId?.hashCode()?.toLong() ?: waveTrack.id.hashCode().toLong(),
                    coverUrl = waveTrack.cover?.replace("1000x1000", "600x600")
                )
                tracks.add(track)
                excludeIds.add(waveTrack.id)

            } catch (e: Exception) {
                android.util.Log.e("EndlessEngine", "Wave error: ${e.message}")
                return@repeat
            }
        }

        return tracks
    }

    private suspend fun buildExcludeSet(): MutableSet<String> {
        val exclude = mutableSetOf<String>()
        exclude.addAll(playedIds)

        withContext(Dispatchers.Main) {
            val player = getController() ?: getCompanionPlayer()
            player?.let { p ->
                for (i in 0 until p.mediaItemCount) {
                    p.getMediaItemAt(i).mediaId?.let { exclude.add(it) }
                }
            }
        }

        return exclude
    }

    /**
     * Добавление треков в очередь. Переключается на Main для ExoPlayer API.
     */
    private suspend fun addTracksToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return

        withContext(Dispatchers.Main) {
            PlayerController.addTracksToQueue(tracks)
            android.util.Log.d("EndlessEngine", "Added ${tracks.size} tracks to PlayerController queue")
        }

        tracks.forEach { playedIds.add(it.id) }
    }
}
