package com.liquidmusicglass.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.liquidmusicglass.R
import com.liquidmusicglass.data.local.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioService — один ExoPlayer, один Listener, один MediaSession.
 * Нет deck swapping, нет crossfade, нет secondary player.
 *
 * Refactored with:
 * 1. WakeLock for lock-screen playback retention
 * 2. Strict queue population + error recovery with auto-skip
 * 3. Custom notification buttons: Force Stop & Download
 */
@OptIn(UnstableApi::class)
class AudioService : MediaSessionService() {

    /** Service scope: IO for network/DB work; ExoPlayer calls always switch to Main */
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** Dedicated main-thread scope for ALL ExoPlayer operations */
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

    /** WakeLock to prevent CPU sleep during active playback */
    private var wakeLock: PowerManager.WakeLock? = null

    private var errorRetryCount = 0
    private var lastErrorTrackId: String? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                android.util.Log.d("VOIDPIXEL_MEDIA", "[AUDIO_FOCUS] LOSS_TRANSIENT_CAN_DUCK — ducking volume to 20%")
                setDucked(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                android.util.Log.d("VOIDPIXEL_MEDIA", "[AUDIO_FOCUS] LOSS_TRANSIENT — maintaining playback, NOT pausing")
                // Do NOT pause on transient loss — playback continues
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                android.util.Log.d("VOIDPIXEL_MEDIA", "[AUDIO_FOCUS] GAIN — restoring volume")
                setDucked(false)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                android.util.Log.d("VOIDPIXEL_MEDIA", "[AUDIO_FOCUS] LOSS (permanent) — pausing playback")
                setDucked(false)
                player.pause()
            }
        }
    }

    /** Solid service-scoped queue reference — never garbage collected in background */
    private var currentQueueItems: List<MediaItem> = emptyList()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _playbackState.value = playbackState
            _isBuffering.value = (playbackState == Player.STATE_BUFFERING)

            // ── GUARD: never touch playWhenReady during BUFFERING or READY ──
            // Only log state; do NOT mutate playWhenReady here.
            if (playbackState == Player.STATE_BUFFERING) {
                android.util.Log.d("VOIDPIXEL_MEDIA", "[STATE] BUFFERING — playWhenReady left untouched (${player.playWhenReady})")
            }
            if (playbackState == Player.STATE_READY) {
                android.util.Log.d("VOIDPIXEL_MEDIA", "[STATE] READY — playWhenReady left untouched (${player.playWhenReady})")
                errorRetryCount = 0
                lastErrorTrackId = null
            }

            // WakeLock management: acquire during active playback, release when idle
            manageWakeLock()

            if (playbackState == Player.STATE_ENDED) {
                android.util.Log.d("AudioService", "STATE_ENDED → notifying controller")
                PlayerController.onTrackEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlayerController.setPlaying(isPlaying)
            manageWakeLock()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            android.util.Log.d("VOIDPIXEL_MEDIA", "[PAUSE_TRIGGER] playWhenReady changed to $playWhenReady, Reason ID: $reason")
            when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ->
                    android.util.Log.d("VOIDPIXEL_MEDIA", "[PAUSE_TRIGGER] Reason: AUDIO_FOCUS_LOSS")
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ->
                    android.util.Log.d("VOIDPIXEL_MEDIA", "[PAUSE_TRIGGER] Reason: AUDIO_BECOMING_NOISY")
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE ->
                    android.util.Log.d("VOIDPIXEL_MEDIA", "[PAUSE_TRIGGER] Reason: REMOTE (notification/bluetooth)")
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM ->
                    android.util.Log.d("VOIDPIXEL_MEDIA", "[PAUSE_TRIGGER] Reason: END_OF_MEDIA_ITEM")
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ->
                    android.util.Log.d("VOIDPIXEL_MEDIA", "[PAUSE_TRIGGER] Reason: USER_REQUEST")
                else ->
                    android.util.Log.d("VOIDPIXEL_MEDIA", "[PAUSE_TRIGGER] Reason: UNKNOWN/other ($reason)")
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let {
                android.util.Log.d("AudioService", "onMediaItemTransition: id=${it.mediaId}, reason=$reason")
                android.util.Log.d("VOIDPIXEL_MEDIA", "[SERVICE_TRANSITION] id=${it.mediaId}, reason=$reason")
                // ── ISOLATE UI FROM NATIVE AUTOMATION ──
                // Only sync UI state; NEVER issue seek/play commands here.
                PlayerController.onTrackChanged(it.mediaId)
            }
            // Trigger LRU cache cleanup after track transition
            MediaCacheManager.onCacheUpdated()

            // ── QUEUE AUTO-REFILL (single source of truth) ──
            // Раньше здесь был отдельный префетч со своими фильтрами — он расходился с
            // EndlessPlaybackEngine и подсыпал не-в-тему. Теперь оба триггера (этот
            // service-listener и UI-bridge в PlayerController) идут в ОДИН движок;
            // его lock + throttle дедуплицируют двойной вызов.
            PlayerController.ensureWaveRefill()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // ── Duration recovery: timeline changes often carry resolved duration ──
            val duration = player.duration
            if (duration > 0 && duration != C.TIME_UNSET) {
                PlayerController.updatePosition(player.currentPosition, duration)
                android.util.Log.d("AudioService", "[TIMELINE] Duration resolved: $duration ms")
            }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // ── Duration recovery: tracks info may resolve after playback starts ──
            val duration = player.duration
            if (duration > 0 && duration != C.TIME_UNSET) {
                PlayerController.updatePosition(player.currentPosition, duration)
                android.util.Log.d("AudioService", "[TRACKS] Duration resolved: $duration ms")
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.e("AudioService", "Player error: ${error.errorCodeName} | ${error.message}")

            val currentTrackId = player.currentMediaItem?.mediaId

            val isExpiredUrl = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                && error.message?.contains("403") == true

            if (isExpiredUrl && currentTrackId != null) {
                // Не каждый 403 — протухшая подпись: source_not_allowed/региональный
                // 403 вернётся снова с тем же источником. Раньше re-resolve шёл в обход
                // счётчика и зацикливался. Ограничиваем число пере-резолвов на трек.
                if (currentTrackId != lastErrorTrackId) {
                    lastErrorTrackId = currentTrackId
                    errorRetryCount = 0
                }
                if (errorRetryCount < 2) {
                    errorRetryCount++
                    PlayerController.handleExpiredUrl(this@AudioService, currentTrackId)
                    return
                }
                // Пере-резолвы исчерпаны → проваливаемся в обычное восстановление/skip ниже.
            }

            // ── SOFT ERROR RECOVERY: retry up to 3 times on the SAME track, do NOT auto-skip ──
            if (currentTrackId != null) {
                if (currentTrackId != lastErrorTrackId) {
                    lastErrorTrackId = currentTrackId
                    errorRetryCount = 0
                }

                if (errorRetryCount < 3) {
                    errorRetryCount++
                    android.util.Log.w("AudioService", "[ERROR_RECOVERY] Retrying playback of trackId=$currentTrackId (attempt $errorRetryCount/3) in 2 seconds...")
                    mainScope.launch {
                        delay(2000)
                        if (player.currentMediaItem?.mediaId == currentTrackId) {
                            player.prepare()
                            player.play()
                        }
                    }
                    return
                }
            }

            val failedIndex = player.currentMediaItemIndex
            val failedMediaId = currentTrackId ?: "unknown"
            android.util.Log.e("AudioService", "[ERROR_RECOVERY] Permanent failure for track at index=$failedIndex, mediaId=$failedMediaId, error=${error.errorCodeName}")

            val nextIndex = failedIndex + 1
            if (nextIndex < player.mediaItemCount) {
                android.util.Log.w("AudioService", "[ERROR_RECOVERY] Auto-skipping to next track (index=$nextIndex) since current track failed permanently.")
                player.seekTo(nextIndex, 0L)
                player.prepare()
                player.play()
            } else {
                // HALT: user must explicitly choose next action after retries exhausted and no more items in queue
                player.playWhenReady = false
                android.util.Log.w("AudioService", "[ERROR_RECOVERY] Retries exhausted and no more items. Playback halted.")

                PlayerController.setPlaying(false)
                PlayerController.onPlaybackError(error.errorCodeName)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // ── Global service-scope exception handler ──
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("AudioService", "Uncaught exception on thread ${thread.name}", throwable)
            // Do NOT kill the process — log and swallow to keep service alive
        }

        // Audio focus setup
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestAudioFocus()

        // WakeLock initialization
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LiquidMusicGlass::AudioPlaybackWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        // Инициализация кэша (lazy, не блокирует старт)
        serviceScope.launch {
            MediaCacheManager.init(this@AudioService)
        }

        // ── Один плеер ──
        player = buildPlayer()
        player.addListener(playerListener)

        PlayerController.audioServiceRef = this

        // ── Одна сессия, созданная один раз ──
        val sessionActivityIntent = android.content.Intent(this, com.liquidmusicglass.MainActivity::class.java).apply {
            putExtra("NAVIGATE_TO", "LARGE_PLAYER")
            flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setId("liquid_music_session")
            .setSessionActivity(sessionActivityPendingIntent)
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

        // Observe current track and favorites to update notification button dynamically
        serviceScope.launch {
            PlayerController.favoriteIds.collect {
                updateNotificationLayout()
            }
        }
        serviceScope.launch {
            PlayerController.currentTrack.collect {
                updateNotificationLayout()
            }
        }
    }

    /** Acquire or release WakeLock based on playback state */
    private fun manageWakeLock() {
        val isActive = player.isPlaying || player.playbackState == Player.STATE_BUFFERING
        if (isActive) {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L) // 10 min timeout, refreshed by playback
            }
        } else {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
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
                .setWillPauseWhenDucked(false)
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
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
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

        // Renderers factory with a transparent bass-analysis audio processor.
        // It does not alter the audio, only measures low-frequency energy for
        // the reactive glow on the "Моя волна" screen.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParameters: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(BassAudioProcessor()))
                    .build()
            }
        }

        return ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build()

                setAudioAttributes(audioAttributes, false)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL) // Media3 native wake mode
                // ── Audio Becoming Noisy Guard ──
                // Do NOT aggressively pause on minor BT/routing changes.
                // ExoPlayer's built-in handler is sufficient; we soften by
                // NOT setting any custom broadcast receiver.
                playWhenReady = false
            }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        // START_STICKY ensures Android recreates the service if killed by LMK
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return session
    }

    override fun onDestroy() {
        PlayerController.logFinalPlayback()
        positionJob?.cancel()
        abandonAudioFocus()

        // Release WakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null

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
        positionJob = mainScope.launch {
            while (isActive) {
                try {
                    // ── ExoPlayer reads on Main thread ──
                    val position = player.currentPosition
                    val duration = player.duration
                    val safeDuration = if (duration > 0 && duration != C.TIME_UNSET) duration else 0L

                    val effectiveDuration = if (safeDuration > 0L) {
                        safeDuration
                    } else {
                        PlayerController.durationMs.value.coerceAtLeast(0L)
                    }
                    PlayerController.updatePosition(position, effectiveDuration)
                } catch (e: Exception) {
                    android.util.Log.e("AudioService", "Position polling error", e)
                }
                delay(500L)
            }
        }
    }

    /** Safe main-thread player access wrapper for UI-driven commands */
    private inline fun withPlayerOnMain(crossinline block: (ExoPlayer) -> Unit) {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main) {
            block(player)
        }
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            // Accept and append items to our solid queue reference
            currentQueueItems = currentQueueItems + mediaItems
            player.addMediaItems(mediaItems)
            return Futures.immediateFuture(mediaItems)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Hard timeline population: replace entire queue
            currentQueueItems = mediaItems.toList()
            player.setMediaItems(currentQueueItems, startIndex, startPositionMs)
            player.prepare()
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(currentQueueItems, startIndex, startPositionMs)
            )
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_FORCE_STOP, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_DOWNLOAD_CURRENT, Bundle.EMPTY))
                .build()

            val currentTrack = PlayerController.currentTrack.value
            val isFav = currentTrack?.let { PlayerController.isFavorite(it.id) } == true
            val favIconResId = if (isFav) R.drawable.ic_notification_favorite else R.drawable.ic_notification_favorite_border

            val favoriteButton = CommandButton.Builder()
                .setDisplayName("Favorite")
                .setIconResId(favIconResId)
                .setSessionCommand(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
                .build()

            val forceStopButton = CommandButton.Builder()
                .setDisplayName("Force Stop")
                .setIconResId(R.drawable.ic_notification_stop)
                .setSessionCommand(SessionCommand(COMMAND_FORCE_STOP, Bundle.EMPTY))
                .build()

            val downloadButton = CommandButton.Builder()
                .setDisplayName("Download")
                .setIconResId(R.drawable.ic_notification_download)
                .setSessionCommand(SessionCommand(COMMAND_DOWNLOAD_CURRENT, Bundle.EMPTY))
                .build()

            // ── Explicitly advertise seek-to-next / seek-to-previous availability ──
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(ImmutableList.of(favoriteButton, forceStopButton, downloadButton))
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

                COMMAND_FORCE_STOP -> {
                    android.util.Log.d("AudioService", "[FORCE_STOP] Executing hard termination sequence")
                    // 1. Stop ExoPlayer
                    player.stop()
                    player.clearMediaItems()
                    // 2. Release MediaSession
                    session.release()
                    this@AudioService.session = null
                    // 3. Stop foreground and service
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    // 4. Hard kill process for clean termination
                    android.os.Process.killProcess(android.os.Process.myPid())
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_DOWNLOAD_CURRENT -> {
                    val currentTrack = PlayerController.currentTrack.value
                    if (currentTrack != null) {
                        android.util.Log.d("AudioService", "[DOWNLOAD] Triggering download for trackId=${currentTrack.id}")
                        // Trigger single-track download via AudioDownloadManager
                        AudioDownloadManager.downloadTrack(
                            context = this@AudioService,
                            track = currentTrack
                        ) { success ->
                            android.util.Log.d("AudioService", "[DOWNLOAD] Result for ${currentTrack.id}: success=$success")
                        }
                    } else {
                        android.util.Log.w("AudioService", "[DOWNLOAD] No current track to download")
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
                    ).apply {
                        // Max priority + ongoing so the system treats this as unkillable
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                )
            }
        }
    }

    /**
     * Hard timeline population: inject the entire MediaItem list at once.
     * This ensures ExoPlayer's internal timeline structurally knows the exact total count.
     * The service retains a solid reference to prevent GC in background.
     *
     * ALL ExoPlayer calls run on Dispatchers.Main for thread safety.
     */
    fun setQueue(mediaItems: List<MediaItem>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        currentQueueItems = mediaItems.toList()
        mainScope.launch {
            player.stop()
            player.clearMediaItems()
            player.setMediaItems(currentQueueItems, startIndex, startPositionMs)
            player.prepare()
            android.util.Log.d("AudioService", "[QUEUE_SET] ${currentQueueItems.size} items injected, startIndex=$startIndex")
            // ── AGGRESSIVE DEBUG: verify injection ──
            android.util.Log.d("VOIDPIXEL_MEDIA", "[SERVICE_QUEUE_SET] ${currentQueueItems.size} items, startIndex=$startIndex")
            for (i in 0 until player.mediaItemCount) {
                val mid = player.getMediaItemAt(i).mediaId
                android.util.Log.d("VOIDPIXEL_MEDIA", "[SERVICE_QUEUE] Index [$i] MediaID: $mid")
            }
        }
    }

    fun addToQueue(mediaItems: List<MediaItem>) {
        currentQueueItems = currentQueueItems + mediaItems
        mainScope.launch {
            player.addMediaItems(mediaItems)
            android.util.Log.d("AudioService", "[QUEUE_ADD] ${mediaItems.size} items appended. Total=${currentQueueItems.size}")
        }
    }

    fun clearQueue() {
        currentQueueItems = emptyList()
        mainScope.launch {
            player.clearMediaItems()
            android.util.Log.d("AudioService", "[QUEUE_CLEAR] Queue wiped")
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

    private fun updateNotificationLayout() {
        val session = session ?: return
        val currentTrack = PlayerController.currentTrack.value
        val isFav = currentTrack?.let { PlayerController.isFavorite(it.id) } == true
        val favIconResId = if (isFav) R.drawable.ic_notification_favorite else R.drawable.ic_notification_favorite_border

        val favoriteButton = CommandButton.Builder()
            .setDisplayName("Favorite")
            .setIconResId(favIconResId)
            .setSessionCommand(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
            .build()

        val forceStopButton = CommandButton.Builder()
            .setDisplayName("Force Stop")
            .setIconResId(R.drawable.ic_notification_stop)
            .setSessionCommand(SessionCommand(COMMAND_FORCE_STOP, Bundle.EMPTY))
            .build()

        val downloadButton = CommandButton.Builder()
            .setDisplayName("Download")
            .setIconResId(R.drawable.ic_notification_download)
            .setSessionCommand(SessionCommand(COMMAND_DOWNLOAD_CURRENT, Bundle.EMPTY))
            .build()

        session.setCustomLayout(ImmutableList.of(favoriteButton, forceStopButton, downloadButton))
    }

    companion object {
        private const val COMMAND_TOGGLE_FAVORITE = "com.liquidmusicglass.TOGGLE_FAVORITE"
        private const val COMMAND_FORCE_STOP = "com.liquidmusicglass.FORCE_STOP"
        private const val COMMAND_DOWNLOAD_CURRENT = "com.liquidmusicglass.DOWNLOAD_CURRENT"
    }
}
