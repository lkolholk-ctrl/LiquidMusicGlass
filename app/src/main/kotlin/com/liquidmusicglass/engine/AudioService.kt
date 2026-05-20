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
import androidx.media3.session.DefaultMediaNotificationProvider
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
 */
@OptIn(UnstableApi::class)
class AudioService : MediaSessionService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var _player: ExoPlayer? = null
    private var _session: MediaSession? = null
    private var _notificationProvider: DefaultMediaNotificationProvider? = null

    private val channelId = "liquid_music_playback"

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private var positionJob: Job? = null
    private var autoMixEngine: ServiceBackedAutoMixEngine? = null

    override fun onCreate() {
        super.onCreate()

        serviceScope.launch {
            MediaCacheManager.init(this@AudioService)
            rebuildPlayerWithCache()
        }

        val player = buildPlayer()
        _player = player
        currentPlayer = player

        _session = MediaSession.Builder(this, player)
            .setId("liquid_music_session")
            .setCallback(SessionCallback())
            .build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(channelId)
            .setChannelName(R.string.notification_channel_name)
            .setNotificationId(1001)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_notification_play)
            }
        _notificationProvider = notificationProvider
        setMediaNotificationProvider(notificationProvider)

        player.addListener(PlayerEventForwarder())

        autoMixEngine = ServiceBackedAutoMixEngine(
            context = this,
            scope = serviceScope,
            isEnabled = { PlayerController.autoMixEnabled.value }
        )
        PlayerController.setAutoMixEngine(autoMixEngine)

        startPositionPolling(player)
        ensureNotificationChannel()
    }

    private fun rebuildPlayerWithCache() {
        val oldPlayer = _player ?: return
        val cacheFactory = MediaCacheManager.getCacheDataSourceFactory()
        if (cacheFactory == null) {
            android.util.Log.d("AudioService", "Cache not available, using HTTP-only player")
            return
        }

        val wasPlaying = oldPlayer.isPlaying
        val currentPosition = oldPlayer.currentPosition
        val currentMediaItem = oldPlayer.currentMediaItem
        val mediaItems = (0 until oldPlayer.mediaItemCount).map { oldPlayer.getMediaItemAt(it) }
        val currentIndex = oldPlayer.currentMediaItemIndex

        val newPlayer = buildPlayer(cacheFactory)
        _player = newPlayer
        currentPlayer = newPlayer

        if (mediaItems.isNotEmpty()) {
            newPlayer.setMediaItems(mediaItems, currentIndex, currentPosition)
            newPlayer.prepare()
            if (wasPlaying) newPlayer.play()
        } else if (currentMediaItem != null) {
            newPlayer.setMediaItem(currentMediaItem)
            newPlayer.prepare()
            if (wasPlaying) newPlayer.play()
        }

        oldPlayer.removeListener(PlayerEventForwarder())
        newPlayer.addListener(PlayerEventForwarder())

        _session?.let { session ->
            session.release()
            _session = null
        }

        _session = MediaSession.Builder(this, newPlayer)
            .setId("liquid_music_session")
            .setCallback(SessionCallback())
            .build()

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

    /**
     * Deck Swapping: динамически меняет активный плеер в MediaSession.
     * Используется AutoMix для бесшовного перехода между треками.
     */
    fun switchActivePlayer(newPrimary: ExoPlayer) {
        _session?.let { session ->
            if (session.player != newPrimary) {
                // Переносим листенер со старого плеера
                val oldPlayer = _player
                oldPlayer?.removeListener(PlayerEventForwarder())
                newPrimary.addListener(PlayerEventForwarder())

                // Меняем плеер в сессии — система увидит новый активный плеер
                session.player = newPrimary
                _player = newPrimary
                currentPlayer = newPrimary

                // Перезапускаем полинг на новом плеере
                startPositionPolling(newPrimary)

                android.util.Log.d("AudioService", "MediaSession successfully swapped to new primary deck.")
            }
        }
    }

    private inner class PlayerEventForwarder : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            _playbackState.value = playbackState
            _isBuffering.value = (playbackState == Player.STATE_BUFFERING)

            when (playbackState) {
                Player.STATE_ENDED -> {
                    android.util.Log.d("AudioService", "STATE_ENDED → next track")
                    PlayerController.onTrackEnded()
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

            val isExpiredUrl = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                && error.message?.contains("403") == true

            if (isExpiredUrl) {
                val currentTrackId = _player?.currentMediaItem?.mediaId
                if (currentTrackId != null) {
                    PlayerController.handleExpiredUrl(this@AudioService, currentTrackId)
                    return
                }
            }

            _player?.stop()
            _player?.prepare()
            PlayerController.setPlaying(false)
            PlayerController.onPlaybackError(error.errorCodeName)
        }
    }

    // Фикс: Полинг непрерывно шлет позицию и безопасный duration в PlayerController
    private fun startPositionPolling(player: ExoPlayer) {
        positionJob?.cancel()
        positionJob = serviceScope.launch {
            while (true) {
                val position = player.currentPosition
                val duration = player.duration
                
                val safeDuration = if (duration > 0 && duration != C.TIME_UNSET) duration else 0L

                PlayerController.updatePosition(position, safeDuration)

                if (player.isPlaying) {
                    val currentIndex = PlayerController.getCurrentIndex()
                    val isPlaying = player.isPlaying
                    val queueSize = PlayerController.getCurrentQueue().size

                    autoMixEngine?.maybeStartAutoMix(
                        currentPositionMs = position,
                        durationMs = safeDuration,
                        currentIndex = currentIndex,
                        isPlaying = isPlaying,
                        queueSize = queueSize
                    )
                }
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

    companion object {
        private const val COMMAND_TOGGLE_FAVORITE = "com.liquidmusicglass.TOGGLE_FAVORITE"

        @Volatile
        var currentPlayer: ExoPlayer? = null
            private set
    }
}
