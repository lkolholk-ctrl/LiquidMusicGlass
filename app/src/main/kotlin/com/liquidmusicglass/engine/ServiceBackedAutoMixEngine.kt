package com.liquidmusicglass.engine

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.liquidmusicglass.automix.AutoMixController
import com.liquidmusicglass.automix.DJEffectsEngine
import com.liquidmusicglass.automix.TrackFeatures
import com.liquidmusicglass.automix.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@OptIn(UnstableApi::class)
class ServiceBackedAutoMixEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val primaryPlayerProvider: () -> ExoPlayer?,
    private val mediaSessionProvider: () -> MediaSession?,
    private val isEnabled: () -> Boolean,
    private val onPlayerSwapped: (ExoPlayer) -> Unit,
    private val onTransitionFinished: () -> Unit
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
        restorePrimaryVolume()
        transitionStarted = false
        mixing = false
        nextTrackFeatures = null
        analyzedNextIndex = -1
        scheduleNextTrackAnalysis()
    }

    fun maybeStartAutoMix() {
        if (!isEnabled()) return
        if (mixing || transitionStarted) return

        val primary = primaryPlayerProvider() ?: return
        if (!primary.isPlaying) return
        if (!primary.hasNextMediaItem()) return

        val duration = primary.duration
        val position = primary.currentPosition
        if (duration <= 0L) return

        val remaining = duration - position
        val nextIndex = primary.currentMediaItemIndex + 1
        if (analyzedNextIndex != nextIndex) return

        val features = nextTrackFeatures ?: return
        val transition = autoMixController.shouldStartTransition(
            currentPositionMs = position,
            remainingMs = remaining,
            features = features
        )

        if (!transition.shouldStart) return

        transitionStarted = true
        startDualPlayerTransition(transition)
    }

    /**
     * Парный анализ: модель видит конец текущего + начало следующего.
     */
    private fun scheduleNextTrackAnalysis() {
        if (!isEnabled()) return

        val primary = primaryPlayerProvider() ?: return
        if (!primary.hasNextMediaItem()) return

        val currentIndex = primary.currentMediaItemIndex
        val nextIndex = currentIndex + 1

        val currentItem = primary.getMediaItemAt(currentIndex)
        val nextItem = primary.getMediaItemAt(nextIndex)

        val currentUri = currentItem.localConfiguration?.uri ?: return
        val nextUri = nextItem.localConfiguration?.uri ?: return
        val currentDurationMs = primary.duration.coerceAtLeast(0L)

        analysisJob?.cancel()
        analysisJob = scope.launch {
            val features = try {
                autoMixController.analyzeTrackPair(
                    currentTrackUri = currentUri,
                    nextTrackUri = nextUri,
                    currentTrackDurationMs = currentDurationMs
                )
            } catch (_: Throwable) {
                null
            }

            nextTrackFeatures = features
            analyzedNextIndex = nextIndex
        }
    }

    /**
     * Dual-player кроссфейд с параметрами от ML модели:
     *
     * - crossfadeDurationMs: модель решает сколько длится фейд (3-15 сек)
     * - entryOffsetMs: модель решает откуда стартовать трек B
     *   (например, пропустить тихое интро)
     * - transitionType: тип кривой кроссфейда (пока smooth_fade)
     */
    private fun startDualPlayerTransition(transition: Transition) {
        val primary = primaryPlayerProvider() ?: return
        if (!primary.hasNextMediaItem()) return

        val targetIndex = primary.currentMediaItemIndex + 1

        val allItems = mutableListOf<MediaItem>()
        for (i in 0 until primary.mediaItemCount) {
            allItems.add(primary.getMediaItemAt(i))
        }

        // Entry offset: модель может сказать "начни трек B с 1500мс"
        val entryOffsetMs = transition.entryOffsetMs.coerceAtLeast(0L)

        val secondary = try {
            buildSecondaryPlayer().apply {
                setMediaItems(allItems, targetIndex, entryOffsetMs)
                repeatMode = primary.repeatMode
                shuffleModeEnabled = primary.shuffleModeEnabled
                prepare()
                volume = 0f
            }
        } catch (_: Throwable) {
            transitionStarted = false
            return
        }
        secondaryPlayer = secondary

        val crossfadeDuration = transition.crossfadeDurationMs

        fadeJob?.cancel()
        fadeJob = scope.launch {
            try {
                mixing = true
                crossfadeActive = true

                awaitPlayerReady(secondary, timeoutMs = 3000L)

                DJEffectsEngine.crossfadeWithEffects(
                    fromPlayer = primary,
                    toPlayer = secondary,
                    durationMs = crossfadeDuration,
                    transitionType = transition.transitionType,
                    masterVolume = 1f,
                    stepMs = STEP_MS
                )

                // Мгновенный свап через MediaSession
                secondary.volume = 1f
                mediaSessionProvider()?.player = secondary

                onPlayerSwapped(secondary)

                try {
                    primary.stop()
                    primary.release()
                } catch (_: Throwable) {}

                secondaryPlayer = null

                crossfadeActive = false
                mixing = false
                transitionStarted = false
                nextTrackFeatures = null
                analyzedNextIndex = -1
                onTransitionFinished()
                scheduleNextTrackAnalysis()
            } catch (_: Throwable) {
                crossfadeActive = false
                releaseSecondaryPlayer()
                restorePrimaryVolume()
                mixing = false
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

    private fun restorePrimaryVolume() {
        try {
            primaryPlayerProvider()?.volume = 1f
        } catch (_: Throwable) {}
    }

    companion object {
        private const val STEP_MS = 50L
    }
}
