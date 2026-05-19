package com.liquidmusicglass.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.liquidmusicglass.MainActivity
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
 * AudioService — строго по Media3 спецификации.
 *
 * Архитектура:
 *   1. Асинхронная инициализация кэша (не блокирует onCreate)
 *   2. CacheDataSource с fallback на чистый HTTP при ошибке
 *   3. Агрессивный LoadControl: 30s min / 60s max буфер
 *   4. MediaSessionService автоматически управляет foreground notification
 *   5. Gapless playback через очередь (player.addMediaItem)
 *
 * Жизненный цикл:
 *   onCreate()    → player + mediaSession (кэш инициализируется async)
 *   onGetSession()→ возвращаем mediaSession
 *   onDestroy()   → player.release() + mediaSession.release()
 */
@OptIn(UnstableApi::class)
class AudioService : MediaSessionService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var _player: ExoPlayer? = null
    private var _session: MediaSession? = null

    // ── Notification channel ──
    private val channelId = "liquid_music_playback"

    // ── Playback state exposed via StateFlow ──
    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    // ── Position polling job ──
    private var positionJob: Job? = null

    // ── AutoMix Engine ──
    private var autoMixEngine: ServiceBackedAutoMixEngine? = null

    override fun onCreate() {
        super.onCreate()

        // Initialize cache asynchronously — NEVER blocks onCreate
        serviceScope.launch {
            MediaCacheManager.init(this@AudioService)
            // Rebuild player with cache once it's ready
            rebuildPlayerWithCache()
        }

        // Build initial player without cache (will be rebuilt when cache is ready)
        val player = buildPlayer()
        _player = player
        currentPlayer = player

        _session = MediaSession.Builder(this, player)
            .setId("liquid_music_session")
            .setCallback(SessionCallback())
            .build()

        player.addListener(PlayerEventForwarder())

        // Initialize AutoMix engine
        autoMixEngine = ServiceBackedAutoMixEngine(
            context = this,
            scope = serviceScope,
            isEnabled = { PlayerController.autoMixEnabled.value }
        )
        PlayerController.setAutoMixEngine(autoMixEngine)

        // Start position polling
        startPositionPolling(player)
    }

    /**
     * Пересобирает плеер с кэширующим DataSource когда кэш готов.
     * Сохраняет текущее состояние воспроизведения.
     */
    private fun rebuildPlayerWithCache() {
        val oldPlayer = _player ?: return
        val cacheFactory = MediaCacheManager.getCacheDataSourceFactory()
        if (cacheFactory == null) {
            android.util.Log.d("AudioService", "Cache not available, using HTTP-only player")
            return
        }

        // Save state
        val wasPlaying = oldPlayer.isPlaying
        val currentPosition = oldPlayer.currentPosition
        val currentMediaItem = oldPlayer.currentMediaItem
        val mediaItems = (0 until oldPlayer.mediaItemCount).map { oldPlayer.getMediaItemAt(it) }
        val currentIndex = oldPlayer.currentMediaItemIndex

        // Build new player with cache
        val newPlayer = buildPlayer(cacheFactory)
        _player = newPlayer
        currentPlayer = newPlayer

        // Restore state
        if (mediaItems.isNotEmpty()) {
            newPlayer.setMediaItems(mediaItems, currentIndex, currentPosition)
            newPlayer.prepare()
            if (wasPlaying) newPlayer.play()
        } else if (currentMediaItem != null) {
            newPlayer.setMediaItem(currentMediaItem)
            newPlayer.prepare()
            if (wasPlaying) newPlayer.play()
        }

        // Update session
        oldPlayer.removeListener(PlayerEventForwarder())
        newPlayer.addListener(PlayerEventForwarder())

        _session?.let { session ->
            val newSession = MediaSession.Builder(this, newPlayer)
                .setId("liquid_music_session")
                .setCallback(SessionCallback())
                .build()
            session.release()
            _session = newSession
        }

        // Restart polling
        startPositionPolling(newPlayer)

        android.util.Log.d("AudioService", "Player rebuilt with cache support")
    }

    private fun buildPlayer(
        cacheFactory: androidx.media3.datasource.cache.CacheDataSource.Factory? = null
    ): ExoPlayer {
        val dataSourceFactory = cacheFactory ?: DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(5_000)
            .setDefaultRequestProperties(mapOf(
                "User-Agent" to "LiquidMusicGlass/1.0"
            ))

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // Агрессивный LoadControl для глубокой буферизации
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000, // minBufferMs — минимум 30 сек в буфере
                60_000, // maxBufferMs — максимум 60 сек
                2_500,  // bufferForPlaybackMs — начать играть когда 2.5сек буфер
                5_000   // bufferForPlaybackAfterRebufferMs — после ребуфера 5сек
            )
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

                // true = автоматический аудиофокус, ducking, пауза при звонках
                setAudioAttributes(audioAttributes, true)
                setHandleAudioBecomingNoisy(true)
                playWhenReady = false
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return _session
    }

    override fun onDestroy() {
        positionJob?.cancel()

        autoMixEngine?.release()
        autoMixEngine = null

        _player?.removeListener(PlayerEventForwarder())
        _player?.release()
        _player = null
        currentPlayer = null

        _session?.release()
        _session = null

        MediaCacheManager.release()

        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════
    //  Player Listener — единая точка обратной связи
    // ═══════════════════════════════════════════════════════════

    private inner class PlayerEventForwarder : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            _playbackState.value = playbackState
            _isBuffering.value = (playbackState == Player.STATE_BUFFERING)

            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    android.util.Log.d("AudioService", "STATE_BUFFERING")
                }
                Player.STATE_READY -> {
                    android.util.Log.d("AudioService", "STATE_READY")
                }
                Player.STATE_ENDED -> {
                    android.util.Log.d("AudioService", "STATE_ENDED → next track")
                    PlayerController.onTrackEnded()
                }
                Player.STATE_IDLE -> {
                    android.util.Log.d("AudioService", "STATE_IDLE")
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlayerController.setPlaying(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let {
                PlayerController.onTrackChanged(it.mediaId)
                autoMixEngine?.onTrackChanged()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.e("AudioService", "Player error: ${error.errorCodeName} | ${error.message}")

            // Check for expired stream URL (403 invalid_or_expired_signature per ICM API docs)
            val isExpiredUrl = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                && error.message?.contains("403") == true

            if (isExpiredUrl) {
                val currentTrackId = _player?.currentMediaItem?.mediaId
                if (currentTrackId != null) {
                    android.util.Log.d("AudioService", "URL expired for $currentTrackId, triggering re-resolve")
                    PlayerController.handleExpiredUrl(this@AudioService, currentTrackId)
                    return
                }
            }

            _player?.stop()
            _player?.prepare()
            PlayerController.setPlaying(false)
            PlayerController.onPlaybackError(error.errorCodeName)
        }

        override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) {
            error?.let {
                android.util.Log.e("AudioService", "Error changed: ${it.errorCodeName}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Position polling — строго асинхронный, только при isPlaying
    // ═══════════════════════════════════════════════════════════

    private fun startPositionPolling(player: ExoPlayer) {
        positionJob?.cancel()
        positionJob = serviceScope.launch {
            while (true) {
                if (player.isPlaying) {
                    val position = player.currentPosition
                    val duration = player.duration
                    val currentIndex = player.currentMediaItemIndex
                    val isPlaying = player.isPlaying
                    val queueSize = player.mediaItemCount

                    PlayerController.updatePosition(position)

                    // AutoMix check
                    autoMixEngine?.maybeStartAutoMix(
                        currentPositionMs = position,
                        durationMs = duration,
                        currentIndex = currentIndex,
                        isPlaying = isPlaying,
                        queueSize = queueSize
                    )
                }
                delay(200L)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  MediaSession Callback
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    //  Notification (MediaSessionService handles foreground)
    // ═══════════════════════════════════════════════════════════

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ) {
        ensureNotificationChannel()

        val player = session.player
        val metadata = player.currentMediaItem?.mediaMetadata

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification_play)
            .setContentTitle(metadata?.title ?: "LiquidMusicGlass")
            .setContentText(metadata?.artist ?: "Unknown Artist")
            .setSubText(metadata?.albumTitle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(player.isPlaying)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        action = Intent.ACTION_MAIN
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        if (startInForegroundRequired || player.isPlaying) {
            startForeground(1001, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Music Playback",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Shows current track and playback controls"
                        setShowBadge(false)
                        enableLights(false)
                        enableVibration(false)
                    }
                )
            }
        }
    }

    companion object {
        private const val COMMAND_TOGGLE_FAVORITE = "com.liquidmusicglass.TOGGLE_FAVORITE"

        /**
         * Exposed for AutoMix crossfade volume control only.
         * ServiceBackedAutoMixEngine needs temporary access to primary player
         * during transitions to fade volume. Never used for playback control.
         */
        @Volatile
        var currentPlayer: ExoPlayer? = null
            private set
    }
}
