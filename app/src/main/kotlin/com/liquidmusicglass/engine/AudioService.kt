package com.liquidmusicglass.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import coil.ImageLoader
import coil.request.ImageRequest
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class AudioService : MediaSessionService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var mediaSession: MediaSession? = null
    private var servicePlayer: ExoPlayer? = null
    private var autoMixEngine: ServiceBackedAutoMixEngine? = null
    private var monitorJob: Job? = null

    // ── Notification ──
    private val notificationId = 1001
    private val channelId = "liquid_music_playback"
    private var isForeground = false

    // ── Metadata sync ──
    private var metadataSyncJob: Job? = null
    private val imageLoader by lazy { ImageLoader.Builder(applicationContext).build() }

    override fun onCreate() {
        super.onCreate()

        // Init settings first
        AppSettings.init(applicationContext)

        val player = buildPrimaryPlayer()
        servicePlayer = player

        // Init audio effects with player's session
        AudioEffectsEngine.init(player.audioSessionId)

        // Build MediaSession with full callback support
        mediaSession = buildMediaSession(player)

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
        // Explicit HTTP data source for signed stream URLs
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpDataSourceFactory)

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        15_000, // minBufferMs
                        50_000, // maxBufferMs
                        1_000,  // bufferForPlaybackMs — faster start
                        2_000   // bufferForPlaybackAfterRebufferMs
                    )
                    .build()
            )
            .build()
            .apply {
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

    /**
     * Build MediaSession with full callback support for lock screen / notification controls.
     */
    private fun buildMediaSession(player: ExoPlayer): MediaSession {
        return MediaSession.Builder(this, player)
            .setId("liquid_music_session")
            .setCallback(MediaSessionCallback())
            .build()
    }

    /**
     * MediaSession.Callback handling all system playback controls.
     */
    private inner class MediaSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Define custom layout for notification / lock screen buttons
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
                .build()

            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(
                    ImmutableList.of(
                        CommandButton.Builder()
                            .setDisplayName("Favorite")
                            .setIconResId(R.drawable.ic_notification_favorite)
                            .setSessionCommand(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
                            .build()
                    )
                )
                .build()
        }

        override fun onPostConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            // Sync metadata after connection
            syncCurrentMetadata()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Return last known queue for playback resumption
            val lastQueue = PlayerController.getCurrentQueue()
            val lastIndex = PlayerController.getCurrentIndex().coerceAtLeast(0)
            val mediaItems = lastQueue.map { t ->
                MediaItem.Builder()
                    .setMediaId(t.id)
                    .setUri(t.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(t.title)
                            .setArtist(t.artist)
                            .setAlbumArtist(t.artist)
                            .setArtworkUri(t.displayArtUri)
                            .build()
                    )
                    .build()
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, lastIndex, 0L)
            )
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    // ── MediaNotification.Provider for custom notification with artwork ──

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ) {
        ensureNotificationChannel()

        val player = session.player
        val currentItem = player.currentMediaItem
        val metadata = currentItem?.mediaMetadata

        // Build base notification
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification_play)
            .setContentTitle(metadata?.title ?: "LiquidMusicGlass")
            .setContentText(metadata?.artist ?: "Unknown Artist")
            .setSubText(metadata?.albumTitle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(player.isPlaying)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(createContentIntent())

        // Add playback controls
        addPlaybackActions(builder, player)

        // Try to load artwork into notification
        val artworkUri = metadata?.artworkUri
        if (artworkUri != null) {
            // Use Coil's cached bitmap if available, otherwise notification will show default
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(applicationContext)
                        .data(artworkUri)
                        .allowHardware(false)
                        .build()
                    val result = imageLoader.execute(request)
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        withContext(Dispatchers.Main) {
                            builder.setLargeIcon(bitmap)
                            val notification = builder.build()
                            updateOrShowNotification(player, notification)
                        }
                    }
                } catch (_: Exception) {
                    // Fall through to notification without artwork
                }
            }
        }

        val notification = builder.build()
        updateOrShowNotification(player, notification)
    }

    private fun updateOrShowNotification(player: Player, notification: android.app.Notification) {
        // Start foreground if playing
        if (player.isPlaying && !isForeground) {
            startForeground(notificationId, notification)
            isForeground = true
        } else if (!player.isPlaying && isForeground) {
            stopForeground(STOP_FOREGROUND_DETACH)
            isForeground = false
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notificationId, notification)
        }
    }

    private fun addPlaybackActions(builder: NotificationCompat.Builder, player: Player) {
        // Previous
        val prevIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent("com.liquidmusicglass.PREV").setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(R.drawable.ic_notification_prev, "Previous", prevIntent)

        // Play/Pause
        val playPauseIntent = PendingIntent.getBroadcast(
            this, 2,
            Intent("com.liquidmusicglass.PLAY_PAUSE").setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIcon = if (player.isPlaying) R.drawable.ic_notification_pause else R.drawable.ic_notification_play
        val playPauseTitle = if (player.isPlaying) "Pause" else "Play"
        builder.addAction(playPauseIcon, playPauseTitle, playPauseIntent)

        // Next
        val nextIntent = PendingIntent.getBroadcast(
            this, 3,
            Intent("com.liquidmusicglass.NEXT").setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(R.drawable.ic_notification_next, "Next", nextIntent)
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = Intent.ACTION_MAIN
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    "Music Playback",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows current track and playback controls"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    // ── Metadata Sync ──

    /**
     * Sync metadata from PlayerController to MediaSession.
     * Called when track changes. Loads artwork asynchronously.
     */
    fun syncCurrentMetadata() {
        metadataSyncJob?.cancel()
        metadataSyncJob = serviceScope.launch {
            val track = PlayerController.currentTrack.value ?: return@launch
            val session = mediaSession ?: return@launch

            // Build MediaMetadata with available fields
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.albumName.takeIf { it.isNotBlank() })
                .setArtworkUri(track.displayArtUri)

            // Set metadata immediately (without artwork bitmap)
            val currentIndex = session.player.currentMediaItemIndex
            if (currentIndex >= 0 && currentIndex < session.player.mediaItemCount) {
                val currentItem = session.player.getMediaItemAt(currentIndex)
                val updatedItem = currentItem.buildUpon()
                    .setMediaMetadata(metadataBuilder.build())
                    .build()
                session.player.removeMediaItem(currentIndex)
                session.player.addMediaItem(currentIndex, updatedItem)
                session.player.seekTo(currentIndex, session.player.currentPosition)
            }

            // Load artwork bitmap asynchronously for lock screen coloring
            loadArtworkBitmap(track.displayArtUri.toString()) { bitmap ->
                bitmap?.let {
                    // Set session extras for lock screen artwork coloring
                    session.setSessionExtras(Bundle().apply {
                        putParcelable("android.media.metadata.ALBUM_ART", bitmap)
                    })
                }
                // Update notification to reflect new metadata
                mediaSession?.let { onUpdateNotification(it, false) }
            }
        }
    }

    private fun loadArtworkBitmap(url: String?, callback: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank()) {
            callback(null)
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(applicationContext)
                    .data(url)
                    .allowHardware(false) // Software bitmap for notification/lockscreen
                    .build()
                val result = imageLoader.execute(request)
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                withContext(Dispatchers.Main) {
                    callback(bitmap)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    callback(null)
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        metadataSyncJob?.cancel()

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

            // Sync metadata for lock screen / notification
            syncCurrentMetadata()

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
                // Exit foreground when paused
                if (isForeground) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    isForeground = false
                }
            } else {
                lastTrackStartTime = System.currentTimeMillis()
                // Enter foreground when playing
                if (!isForeground) {
                    mediaSession?.let { onUpdateNotification(it, true) }
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Update notification on state changes
            mediaSession?.let { onUpdateNotification(it, false) }
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
        // Auto-mix is non-critical. If it throws (e.g. player timeline not
        // ready yet -> IndexOutOfBounds), swallow it instead of crashing.
        try {
            autoMixEngine?.onManualNavigation()
        } catch (e: Exception) {
            android.util.Log.e("AudioService", "onManualNavigation failed", e)
        }
    }

    companion object {
        private const val COMMAND_TOGGLE_FAVORITE = "com.liquidmusicglass.TOGGLE_FAVORITE"

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
