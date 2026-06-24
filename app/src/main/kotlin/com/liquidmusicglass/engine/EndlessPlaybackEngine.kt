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
        val genre: String? = null,
        val mood: String? = null
    ) {
        enum class Type { WAVE, ARTIST, ALBUM, SEARCH, GENRE, PLAYLIST, LIBRARY, YOUTUBE_RADIO }
    }

    fun setRefillContext(context: RefillContext?) {
        _refillContext.value = context
        android.util.Log.d("EndlessEngine", "Context changed to: ${context?.type?.name ?: "null"}")
    }

    fun reset() {
        playedIds.clear()
        isRefilling.set(false)
        lastRefillTime.set(0L)
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
        // ── BOUNDARY LOCK: refill is strictly allowed in Global (Wave) context ──
        if (PlayerController.playbackContext !is PlaybackContext.Global) {
            android.util.Log.d("EndlessEngine", "Refill blocked: active context is ${PlayerController.playbackContext}")
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
                    when (refillCtx?.type) {
                        RefillContext.Type.YOUTUBE_RADIO -> {
                            // YouTube radio refill
                            val seedId = refillCtx.seedTrackId ?: return@withContext emptyList()
                            val result = com.liquidmusicglass.api.youtube.YouTubeMusicRepository.getInstance()
                                .getRadioQueue(seedId)
                            if (result.isSuccess) {
                                val ytTracks = result.getOrThrow()
                                ytTracks.map { ytTrack ->
                                    ytTrack.toEngineTrack()
                                }
                            } else emptyList()
                        }
                        else -> {
                            // ICM wave refill. seedTrackId != null → продолжаем мудовую станцию
                            // тем же жанром; null → персональная волна.
                            val waveRepo = com.liquidmusicglass.data.local.WaveRepository.getInstance(ctx)
                            waveRepo.buildWaveQueue(count = REFILL_BATCH_SIZE, seedTrackId = refillCtx?.seedTrackId)
                        }
                    }
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
