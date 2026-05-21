package com.liquidmusicglass.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.liquidmusicglass.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AudioService — один ExoPlayer, один Listener, один MediaSession.
 * Нет deck swapping, нет crossfade, нет secondary player.
 */
@OptIn(UnstableApi::class)
class AudioService : MediaSessionService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    private val channelId = "liquid_music_playback"

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _isDucked = MutableStateFlow(false)
    val isDucked: StateFlow<Boolean> = _isDucked.asStateFlow()

    private var positionJob: Job? = null

    private var focusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                setDucked(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                player.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                setDucked(false)
                if (!player.isPlaying && player.playbackState == Player.STATE_READY) {
                    player.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                setDucked(false)
                player.pause()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _playbackState.value = playbackState
            _isBuffering.value = (playbackState == Player.STATE_BUFFERING)

            if (playbackState == Player.STATE_ENDED) {
                android.util.Log.d("AudioService", "STATE_ENDED → next track")
                PlayerController.onTrackEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlayerController.setPlaying(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let {
                PlayerController.onTrackChanged(it.mediaId)
            }
            // Trigger LRU cache cleanup after track transition
            // (new track may have been cached, old ones may need eviction)
            MediaCacheManager.onCacheUpdated()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.e("AudioService", "Player error: ${error.errorCodeName} | ${error.message}")

            val isExpiredUrl = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                && error.message?.contains("403") == true

            if (isExpiredUrl) {
                val currentTrackId = player.currentMediaItem?.mediaId
                if (currentTrackId != null) {
                    PlayerController.handleExpiredUrl(this@AudioService, currentTrackId)
                    return
                }
            }

            player.stop()
            player.prepare()
            PlayerController.setPlaying(false)
            PlayerController.onPlaybackError(error.errorCodeName)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Audio focus setup
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestAudioFocus()

        // Инициализация кэша (lazy, не блокирует старт)
        serviceScope.launch {
            MediaCacheManager.init(this@AudioService)
        }

        // ── Один плеер ──
        player = buildPlayer()
        player.addListener(playerListener)

        PlayerController.audioServiceRef = this

        // ── Одна сессия, созданная один раз ──
        session = MediaSession.Builder(this, player)
            .setId("liquid_music_session")
            .setCallback(SessionCallback())
            .build()

        // ── Нотификация ──
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(channelId)
            .setChannelName(R.string.notification_channel_name)
            .setNotificationId(1001)
            .build()
            .apply { setSmallIcon(R.drawable.ic_notification_play) }
        setMediaNotificationProvider(notificationProvider)

        // ── Полинг позиции ──
        startPositionPolling()
        ensureNotificationChannel()
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .setWillPauseWhenDucked(false) // Мы сами управляем ducking'ом
                .build()
            focusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusChangeListener)
        }
        focusRequest = null
    }

    private fun buildPlayer(): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(5_000)
            .setDefaultRequestProperties(mapOf(
                "User-Agent" to "LiquidMusicGlass/1.0"
            ))

        val dataSourceFactory = StreamingDataSource.create(
            context = this,
            httpDataSource = httpFactory
        )

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 60_000, 2_500, 5_000)
            .build()

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build()

                setAudioAttributes(audioAttributes, true)
                setHandleAudioBecomingNoisy(true)
                playWhenReady = false
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return session
    }

    override fun onDestroy() {
        positionJob?.cancel()
        abandonAudioFocus()

        player.removeListener(playerListener)
        player.release()

        session?.release()
        session = null

        PlayerController.audioServiceRef = null

        MediaCacheManager.release()
        super.onDestroy()
    }

    private fun startPositionPolling() {
        positionJob?.cancel()
        positionJob = serviceScope.launch {
            while (true) {
                val position = player.currentPosition
                val duration = player.duration

                val safeDuration = if (duration > 0 && duration != C.TIME_UNSET) duration else 0L
                PlayerController.updatePosition(position, safeDuration)

                delay(200L)
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setCustomLayout(
                    ImmutableList.of(
                        androidx.media3.session.CommandButton.Builder()
                            .setDisplayName("Favorite")
                            .setIconResId(R.drawable.ic_notification_favorite)
                            .setSessionCommand(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
                            .build()
                    )
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                COMMAND_TOGGLE_FAVORITE -> {
                    val trackId = PlayerController.currentTrack.value?.id
                    if (trackId != null) {
                        PlayerController.toggleFavorite(trackId)
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        player.setPlaybackParameters(PlaybackParameters(clamped))
        _playbackSpeed.value = clamped
    }

    fun setDucked(ducked: Boolean) {
        _isDucked.value = ducked
        player.volume = if (ducked) 0.2f else 1f
    }

    companion object {
        private const val COMMAND_TOGGLE_FAVORITE = "com.liquidmusicglass.TOGGLE_FAVORITE"
    }
}
