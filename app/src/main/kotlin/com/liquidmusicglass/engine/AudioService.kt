package com.liquidmusicglass.engine

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AudioService : MediaSessionService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var mediaSession: MediaSession? = null
    private var servicePlayer: ExoPlayer? = null
    private var autoMixEngine: ServiceBackedAutoMixEngine? = null
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        // Init settings first
        AppSettings.init(applicationContext)

        val player = buildPrimaryPlayer()
        servicePlayer = player

        // Init audio effects with player's session
        AudioEffectsEngine.init(player.audioSessionId)

        mediaSession = MediaSession.Builder(this, player)
            .setId("liquid_music_session")
            .build()

        autoMixEngine = ServiceBackedAutoMixEngine(
            context = applicationContext,
            scope = serviceScope,
            primaryPlayerProvider = { servicePlayer },
            mediaSessionProvider = { mediaSession },
            isEnabled = { AppSettings.autoMixEnabled.value },
            onPlayerSwapped = { newPlayer ->
                newPlayer.addListener(primaryListener)
                servicePlayer = newPlayer
                companionPlayer = newPlayer
                AudioEffectsEngine.init(newPlayer.audioSessionId)
            },
            onTransitionFinished = {
                mixingState = false
            }
        )

        companionPlayer = player
        companionSession = mediaSession
        companionService = this

        // Init playlists
        PlaylistManager.init(applicationContext)

        // Restore last playback state
        restorePlayerState()

        startMonitorLoop()
    }

    private fun buildPrimaryPlayer(): ExoPlayer {
        return ExoPlayer.Builder(this).build().apply {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(primaryListener)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        monitorJob?.cancel()

        // Save final state
        servicePlayer?.let { player ->
            AppSettings.savePlayerState(
                player.currentMediaItemIndex,
                player.currentPosition
            )
        }

        autoMixEngine?.release()
        autoMixEngine = null

        AudioEffectsEngine.release()

        servicePlayer?.removeListener(primaryListener)
        servicePlayer?.release()
        servicePlayer = null

        mediaSession?.release()
        mediaSession = null

        companionPlayer = null
        companionSession = null
        companionService = null
        mixingState = false

        super.onDestroy()
    }

    private var lastTrackPosition = 0L
    private var lastTrackStartTime = 0L

    private val primaryListener = object : Player.Listener {
        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int
        ) {
            lastTrackStartTime = System.currentTimeMillis()
            lastTrackPosition = 0L

            // Save current track index
            servicePlayer?.let { player ->
                AppSettings.savePlayerState(player.currentMediaItemIndex, 0L)
            }

            autoMixEngine?.onTrackChanged()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                mixingState = false
                // Save position on pause
                servicePlayer?.let { player ->
                    AppSettings.savePlayerState(
                        player.currentMediaItemIndex,
                        player.currentPosition
                    )
                }
            } else {
                lastTrackStartTime = System.currentTimeMillis()
            }
        }
    }

    private fun restorePlayerState() {
        val lastIndex = AppSettings.lastTrackIndex.value
        val lastPos = AppSettings.lastPositionMs.value
        if (lastIndex >= 0) {
            servicePlayer?.let { player ->
                if (player.mediaItemCount > lastIndex) {
                    player.seekTo(lastIndex, lastPos)
                    player.prepare()
                    // Don't auto-play, just restore position
                }
            }
        }
    }

    private fun startMonitorLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (true) {
                autoMixEngine?.maybeStartAutoMix()
                mixingState = autoMixEngine?.isMixing == true
                delay(200L)
            }
        }
    }

    fun notifyManualNavigation() {
        autoMixEngine?.onManualNavigation()
    }

    companion object {
        @Volatile
        private var mixingState: Boolean = false

        @Volatile
        var companionPlayer: ExoPlayer? = null
            private set

        @Volatile
        var companionSession: MediaSession? = null
            private set

        @Volatile
        var companionService: AudioService? = null
            private set

        fun setAutoMixEnabled(enabled: Boolean) {
            AppSettings.setAutoMix(enabled)
        }

        fun getMixingState(): Boolean = mixingState

        fun getAudioSessionId(): Int {
            return try { companionPlayer?.audioSessionId ?: 0 } catch (_: Throwable) { 0 }
        }
    }
}
