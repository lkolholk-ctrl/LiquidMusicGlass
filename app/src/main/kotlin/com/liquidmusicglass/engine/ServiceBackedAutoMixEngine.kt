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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * ServiceBackedAutoMixEngine — ML-powered DJ transitions.
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
        getPrimaryPlayer()?.volume = 1f
        releaseSecondaryPlayer()
        transitionStarted = false
        mixing = false
        nextTrackFeatures = null
        analyzedNextIndex = -1
        scheduleNextTrackAnalysis()
    }

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

        // Защита от фальстарта: автомикс физически не может начаться,
        // если трек не проиграл хотя бы 65% от своей общей длительности.
        val safePlaybackThreshold = (durationMs * 0.65f).toLong()
        if (currentPositionMs < safePlaybackThreshold) return

        // Дополнительная защита: игнорируем триггер в первые 60 секунд трека в любом случае
        if (currentPositionMs < 60_000L) return

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

        val entryOffsetMs = transition.entryOffsetMs.coerceAtLeast(0L)
        val crossfadeDuration = transition.crossfadeDurationMs

        fadeJob?.cancel()
        fadeJob = scope.launch {
            try {
                // ИСПРАВЛЕНО: Извлекаем горячий рабочий URL из кэша для дочернего плеера кроссфейда
                val realNextUri = PlayerController.getValidStreamUri(nextTrack.id) ?: nextTrack.uri

                val secondary = withContext(Dispatchers.Main) {
                    buildSecondaryPlayer().apply {
                        val mediaItem = MediaItem.Builder()
                            .setMediaId(nextTrack.id)
                            .setUri(realNextUri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(nextTrack.title)
                                    .setArtist(nextTrack.artist)
                                    .build()
                            )
                            .build()
                        setMediaItem(mediaItem)
                        seekTo(entryOffsetMs)
                        prepare()
                        volume = 0f
                    }
                }
                secondaryPlayer = secondary

                mixing = true
                PlayerController.setMixing(true)
                crossfadeActive = true

                awaitPlayerReady(secondary, timeoutMs = 4000L)
                withContext(Dispatchers.Main) { secondary.play() }

                val oldPrimary = getPrimaryPlayer()

                // ═══════════════════════════════════════════════════════════
                //  DECK SWAPPING: Бесшовная смена активного плеера в НАЧАЛЕ кроссфейда
                // ═══════════════════════════════════════════════════════════
                withContext(Dispatchers.Main) {
                    val newPrimary = secondaryPlayer ?: return@withContext

                    // 1. Переключаем MediaSession на новый плеер сразу — без seekTo!
                    val service = appContext as? AudioService
                    service?.switchActivePlayer(newPrimary)

                    // 2. Синхронизируем очередь в PlayerController
                    PlayerController.setQueue(queue, nextIndex)
                }

                if (oldPrimary != null) {
                    DJEffectsEngine.crossfadeWithEffects(
                        fromPlayer = oldPrimary,
                        toPlayer = secondary,
                        durationMs = crossfadeDuration,
                        transitionType = transition.transitionType,
                        masterVolume = 1f,
                        outgoingBias = 1.5f,
                        bpmDrift = transition.bpmDrift,
                        stepMs = STEP_MS
                    )
                } else {
                    simpleCrossfade(secondary, crossfadeDuration)
                }

                // ═══════════════════════════════════════════════════════════
                //  Окончательно освобождаем и останавливаем старую первичную деку
                // ═══════════════════════════════════════════════════════════
                withContext(Dispatchers.Main) {
                    oldPrimary?.stop()
                    oldPrimary?.clearMediaItems()
                    oldPrimary?.volume = 0f
                    secondaryPlayer = oldPrimary
                }

                crossfadeActive = false
                mixing = false
                PlayerController.setMixing(false)
                transitionStarted = false

                // Принудительно очищаем кэш ИИ и запускаем предзагрузку/анализ для СЛЕДУЮЩЕЙ пары
                onTrackChanged()
                onTransitionFinished()
            } catch (e: Exception) {
                android.util.Log.e("AutoMixEngine", "Crossfade execution crash: ${e.message}")
                crossfadeActive = false
                withContext(Dispatchers.Main) { releaseSecondaryPlayer() }
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
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val steps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
            for (step in 0..steps) {
                val progress = step.toFloat() / steps
                withContext(Dispatchers.Main) {
                    secondary.volume = progress.coerceIn(0f, 1f)
                }
                delay(STEP_MS)
            }
            withContext(Dispatchers.Main) { secondary.volume = 1f }
        }
    }

    companion object {
        private const val STEP_MS = 50L
    }
}
