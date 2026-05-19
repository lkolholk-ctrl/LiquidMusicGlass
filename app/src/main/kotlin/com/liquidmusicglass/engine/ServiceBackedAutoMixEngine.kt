package com.liquidmusicglass.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.liquidmusicglass.automix.AutoMixController
import com.liquidmusicglass.automix.DJEffectsEngine
import com.liquidmusicglass.automix.TrackFeatures
import com.liquidmusicglass.automix.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * ServiceBackedAutoMixEngine — ML-powered DJ transitions.
 *
 * Адаптирован под новую архитектуру PlayerController + AudioService:
 *   - Не имеет прямого доступа к primary ExoPlayer / MediaSession
 *   - Получает состояние через PlayerController StateFlow
 *   - Создаёт secondary ExoPlayer только на время перехода
 *   - По окончании — обновляет queue в PlayerController, primary player подхватывает
 */
@OptIn(UnstableApi::class)
class ServiceBackedAutoMixEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val onTransitionFinished: () -> Unit = {}
) {

    private val appContext = context.applicationContext
    private val autoMixController = AutoMixController(appContext)

    private var analysisJob: Job? = null
    private var fadeJob: Job? = null

    private var secondaryPlayer: ExoPlayer? = null

    private var nextTrackFeatures: TrackFeatures? = null
    private var analyzedNextIndex: Int = -1

    private var mixing = false
    private var transitionStarted = false
    private var crossfadeActive = false

    val isMixing: Boolean
        get() = mixing

    fun release() {
        analysisJob?.cancel()
        fadeJob?.cancel()
        crossfadeActive = false
        releaseSecondaryPlayer()
        autoMixController.release()
    }

    fun onTrackChanged() {
        if (crossfadeActive) return

        transitionStarted = false
        mixing = false
        releaseSecondaryPlayer()
        nextTrackFeatures = null
        analyzedNextIndex = -1
        scheduleNextTrackAnalysis()
    }

    fun onManualNavigation() {
        fadeJob?.cancel()
        crossfadeActive = false
        releaseSecondaryPlayer()
        transitionStarted = false
        mixing = false
        nextTrackFeatures = null
        analyzedNextIndex = -1
        scheduleNextTrackAnalysis()
    }

    /**
     * Вызывается из AudioService position polling каждые ~200ms.
     * Проверяет, пора ли начинать переход.
     */
    fun maybeStartAutoMix(
        currentPositionMs: Long,
        durationMs: Long,
        currentIndex: Int,
        isPlaying: Boolean,
        queueSize: Int
    ) {
        if (!isEnabled()) return
        if (mixing || transitionStarted) return
        if (!isPlaying) return
        if (currentIndex + 1 >= queueSize) return
        if (durationMs <= 0L) return

        val remaining = durationMs - currentPositionMs
        val nextIndex = currentIndex + 1
        if (analyzedNextIndex != nextIndex) return

        val features = nextTrackFeatures ?: return
        val transition = autoMixController.shouldStartTransition(
            currentPositionMs = currentPositionMs,
            remainingMs = remaining,
            features = features
        )

        if (!transition.shouldStart) return

        transitionStarted = true
        startDualPlayerTransition(transition, currentIndex, nextIndex)
    }

    /**
     * Парный анализ: модель видит конец текущего + начало следующего.
     */
    private fun scheduleNextTrackAnalysis() {
        if (!isEnabled()) return

        val queue = PlayerController.getCurrentQueue()
        val currentIndex = PlayerController.getCurrentIndex()
        if (currentIndex + 1 >= queue.size) return

        val currentTrack = queue.getOrNull(currentIndex) ?: return
        val nextTrack = queue.getOrNull(currentIndex + 1) ?: return

        analysisJob?.cancel()
        analysisJob = scope.launch {
            val features = try {
                autoMixController.analyzeTrackPair(
                    currentTrackUri = currentTrack.uri,
                    nextTrackUri = nextTrack.uri,
                    currentTrackDurationMs = currentTrack.durationMs
                )
            } catch (_: Throwable) {
                null
            }

            nextTrackFeatures = features
            analyzedNextIndex = currentIndex + 1
        }
    }

    /**
     * Dual-player кроссфейд с параметрами от ML модели.
     *
     * В отличие от старой версии — НЕ меняет MediaSession.player.
     * Вместо этого: secondary играет переход, затем primary
     * переключается на следующий трек через PlayerController.
     */
    private fun startDualPlayerTransition(
        transition: Transition,
        currentIndex: Int,
        nextIndex: Int
    ) {
        val queue = PlayerController.getCurrentQueue()
        if (nextIndex >= queue.size) {
            transitionStarted = false
            return
        }

        val currentTrack = queue[currentIndex]
        val nextTrack = queue[nextIndex]

        // Entry offset: модель может сказать "начни трек B с 1500мс"
        val entryOffsetMs = transition.entryOffsetMs.coerceAtLeast(0L)
        val crossfadeDuration = transition.crossfadeDurationMs

        // Build secondary player with just the next track
        val secondary = try {
            buildSecondaryPlayer().apply {
                val mediaItem = buildMediaItem(nextTrack)
                setMediaItem(mediaItem)
                seekTo(entryOffsetMs)
                prepare()
                volume = 0f
            }
        } catch (_: Throwable) {
            transitionStarted = false
            return
        }
        secondaryPlayer = secondary

        fadeJob?.cancel()
        fadeJob = scope.launch {
            try {
                mixing = true
                PlayerController.setMixing(true)
                crossfadeActive = true

                awaitPlayerReady(secondary, timeoutMs = 3000L)
                secondary.play()

                // Get reference to primary player via AudioService for volume control
                // We need to temporarily access the service player for crossfade
                val primaryPlayer = getPrimaryPlayer()

                if (primaryPlayer != null) {
                    DJEffectsEngine.crossfadeWithEffects(
                        fromPlayer = primaryPlayer,
                        toPlayer = secondary,
                        durationMs = crossfadeDuration,
                        transitionType = transition.transitionType,
                        masterVolume = 1f,
                        stepMs = STEP_MS
                    )
                } else {
                    // Fallback: simple volume fade on secondary
                    simpleCrossfade(secondary, crossfadeDuration)
                }

                // Transition complete — primary player уже на следующем треке
                // (ExoPlayer auto-advanced через addMediaItem)
                secondary.stop()
                releaseSecondaryPlayer()

                // Не вызываем skipNext() — это сбросит очередь!
                // Просто сбрасываем флаги и планируем анализ следующего трека
                crossfadeActive = false
                mixing = false
                PlayerController.setMixing(false)
                transitionStarted = false
                nextTrackFeatures = null
                analyzedNextIndex = -1
                onTransitionFinished()
                scheduleNextTrackAnalysis()
            } catch (_: Throwable) {
                crossfadeActive = false
                releaseSecondaryPlayer()
                mixing = false
                PlayerController.setMixing(false)
                transitionStarted = false
            }
        }
    }

    private fun buildSecondaryPlayer(): ExoPlayer {
        return ExoPlayer.Builder(appContext).build().apply {
            val attrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            setAudioAttributes(attrs, false)
        }
    }

    private fun buildMediaItem(track: Track): MediaItem {
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .build()
            )
            .build()
    }

    private suspend fun awaitPlayerReady(player: ExoPlayer, timeoutMs: Long) {
        if (player.playbackState == Player.STATE_READY) return

        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            player.removeListener(this)
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }
                    }
                }
                player.addListener(listener)
                continuation.invokeOnCancellation {
                    player.removeListener(listener)
                }
                if (player.playbackState == Player.STATE_READY) {
                    player.removeListener(listener)
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
        }
    }

    private fun releaseSecondaryPlayer() {
        try {
            secondaryPlayer?.stop()
            secondaryPlayer?.release()
        } catch (_: Throwable) {}
        secondaryPlayer = null
    }

    private fun getPrimaryPlayer(): ExoPlayer? {
        return AudioService.currentPlayer
    }

    private fun simpleCrossfade(secondary: ExoPlayer, durationMs: Long) {
        // Fallback when primary player is not accessible
        // Just fade in the secondary player
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val steps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
            for (step in 0..steps) {
                val progress = step.toFloat() / steps
                secondary.volume = progress.coerceIn(0f, 1f)
                delay(STEP_MS)
            }
            secondary.volume = 1f
        }
    }

    companion object {
        private const val STEP_MS = 50L
    }
}
