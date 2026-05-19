package com.liquidmusicglass.engine

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@OptIn(UnstableApi::class)
object PlayerController {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null

    private var controller: MediaController? = null
    private var isConnectingController = false
    // Once MediaController fails to build, stop retrying it — use direct player.
    private var mediaControllerUnavailable = false
    private var positionJob: Job? = null

    private var queue = listOf<Track>()
    private var currentIndex = -1

    private const val POSITION_UPDATE_MS = 200L

    // ── Endless Playback Engine ──
    private val endlessEngine by lazy {
        EndlessPlaybackEngine(
            scope = scope,
            getController = { controller },
            getCompanionPlayer = { AudioService.companionPlayer }
        )
    }

    // ── Wave playback tracking ──
    private var waveTrackStartTimeMs: Long = 0L
    private var waveTrackPlayedMs: Long = 0L
    private var waveTrackId: String? = null

    // ── Stream URL cache (trackId -> resolved Uri) ──
    // Docs: signed `url` lives ~10 minutes; we cache for 8 min to stay safe.
    private val streamUrlCache = mutableMapOf<String, android.net.Uri>()
    private val streamUrlCacheExpiry = mutableMapOf<String, Long>()
    private const val STREAM_CACHE_TTL_MS = 8 * 60 * 1000L // 8 min (URL valid ~10 min)

    /** How many upcoming tracks to keep pre-resolved — docs 7.3 says 3–5. */
    private const val PREFETCH_AHEAD = 3

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _volume = MutableStateFlow(0.7f)
    val volume: StateFlow<Float> = _volume

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queueFlow: StateFlow<List<Track>> = _queue

    private val _autoMixEnabled = MutableStateFlow(false)
    val autoMixEnabled: StateFlow<Boolean> = _autoMixEnabled

    // AutoMix persistence
    private var autoMixPrefs: android.content.SharedPreferences? = null

    fun loadAutoMixState(context: Context) {
        autoMixPrefs = context.getSharedPreferences("auto_mix", Context.MODE_PRIVATE)
        _autoMixEnabled.value = autoMixPrefs?.getBoolean("enabled", false) ?: false
    }

    private fun saveAutoMixState() {
        autoMixPrefs?.edit()?.putBoolean("enabled", _autoMixEnabled.value)?.apply()
    }

    private val _isMixing = MutableStateFlow(false)
    val isMixing: StateFlow<Boolean> = _isMixing

    private val _recentlyPlayed = MutableStateFlow<List<Track>>(emptyList())
    val recentlyPlayed: StateFlow<List<Track>> = _recentlyPlayed

    private val recentHistory = mutableListOf<Track>()

    /**
     * Добавить трек в недавно прослушанные (локальная история).
     */
    private fun addToRecent(track: Track) {
        recentHistory.removeAll { it.id == track.id }
        recentHistory.add(0, track)
        if (recentHistory.size > 30) recentHistory.removeLast()
        _recentlyPlayed.value = recentHistory.toList()

        // Save to persistent local storage
        appContext?.let { ctx ->
            com.liquidmusicglass.data.local.LocalStorage.addToHistory(ctx,
                com.liquidmusicglass.data.local.HistoryEntry(
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    coverUrl = track.coverUrl,
                    durationMs = track.durationMs
                )
            )
        }
    }

    // Shuffle & Repeat
    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled

    private val _repeatMode = MutableStateFlow(0) // 0=off, 1=all, 2=one
    val repeatMode: StateFlow<Int> = _repeatMode

    // Favorites (String IDs — поддержка локальных Long и ICM API String)
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds

    // Playlists (persisted in SharedPreferences)
    data class Playlist(
        val id: Long,
        val name: String,
        val trackIds: List<String>
    )

    private var nextPlaylistId = 1L
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    private var playlistPrefs: android.content.SharedPreferences? = null

    fun initPlaylists(context: android.content.Context) {
        playlistPrefs = context.getSharedPreferences("playlists", android.content.Context.MODE_PRIVATE)
        loadPlaylists()
    }

    private fun loadPlaylists() {
        try {
            val json = playlistPrefs?.getString("data", null) ?: return
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<Playlist>()
            var maxId = 0L
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getLong("id")
                if (id > maxId) maxId = id
                val ids = mutableListOf<String>()
                val tArr = obj.getJSONArray("t")
                for (j in 0 until tArr.length()) ids.add(tArr.getString(j))
                list.add(Playlist(id, obj.getString("n"), ids))
            }
            nextPlaylistId = maxId + 1
            _playlists.value = list
        } catch (_: Exception) {}
    }

    private fun savePlaylists() {
        try {
            val arr = org.json.JSONArray()
            _playlists.value.forEach { pl ->
                val obj = org.json.JSONObject()
                obj.put("id", pl.id)
                obj.put("n", pl.name)
                val tArr = org.json.JSONArray()
                pl.trackIds.forEach { tArr.put(it) }
                obj.put("t", tArr)
                arr.put(obj)
            }
            playlistPrefs?.edit()?.putString("data", arr.toString())?.apply()
        } catch (_: Exception) {}
    }

    // Theme: 0=System, 1=Dark, 2=Light (locked to Dark until light theme is ready)
    private val _themeMode = MutableStateFlow(1)
    val themeMode: StateFlow<Int> = _themeMode

    fun setThemeMode(mode: Int) {
        _themeMode.value = mode.coerceIn(0, 2)
    }

    // Sleep timer
    private var sleepTimerJob: Job? = null
    private val _sleepTimerMinutes = MutableStateFlow(0)
    val sleepTimerMinutes: StateFlow<Int> = _sleepTimerMinutes

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerMinutes.value = minutes
        if (minutes <= 0) return
        sleepTimerJob = scope.launch {
            delay(minutes * 60 * 1000L)
            controller?.pause()
            _isPlaying.value = false
            stopPositionUpdates()
            _sleepTimerMinutes.value = 0
        }
    }

    // ── App Context accessor for EndlessPlaybackEngine ──
    fun getAppContext(): Context? = appContext

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                startPositionUpdates()
            } else {
                stopPositionUpdates()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.e("PlayerController", "Player error: ${error.errorCodeName} | ${error.message}")
            // If stream URL expired or is invalid, try to re-resolve and skip to next
            when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                    // Stream URL likely expired — clear cache and try next track
                    val currentId = _currentTrack.value?.id
                    if (currentId != null) {
                        streamUrlCache.remove(currentId)
                        streamUrlCacheExpiry.remove(currentId)
                        android.util.Log.w("PlayerController", "Cleared expired URL cache for $currentId")
                    }
                    // Auto-skip to next track after short delay
                    scope.launch {
                        delay(500)
                        skipNext(appContext ?: return@launch)
                    }
                }
                else -> {
                    // Other errors — also try to skip forward
                    scope.launch {
                        delay(500)
                        skipNext(appContext ?: return@launch)
                    }
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val current = controller ?: return
            if (playbackState == Player.STATE_READY) {
                _durationMs.value = current.duration.coerceAtLeast(0L)
            }
            // STATE_ENDED: ExoPlayer auto-advances. Refill обрабатывается
            // в onMediaItemTransition — здесь ничего не делаем.
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaId = mediaItem?.mediaId ?: return
            // Seamless transition: log the *previous* wave track as completed before
            // we overwrite the tracking ids below (docs 8.4: `completed=true`).
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                logWavePlaybackOnSwitch(autoCompleted = true)
            }

            val index = queue.indexOfFirst { it.id == mediaId }
            if (index in queue.indices) {
                currentIndex = index
                val track = queue[index]
                _currentTrack.value = track
                _durationMs.value = track.durationMs
                _currentPositionMs.value = 0L
                addToRecent(track)

                // Start tracking the now-playing track for the next playback log.
                waveTrackId = track.id
                waveTrackStartTimeMs = System.currentTimeMillis()
                waveTrackPlayedMs = 0L

                // For online tracks: ensure the stream URL is resolved.
                if (track.isOnlineTrack) {
                    scope.launch {
                        val resolvedUri = getCachedOrResolveStreamUrl(track.id)
                        if (resolvedUri != null) {
                            if (resolvedUri != track.uri) {
                                updateQueueTrackUri(track.id, resolvedUri)
                                replaceMediaItemUri(index, resolvedUri, track)
                            }
                        } else {
                            android.util.Log.e("PlayerController", "Failed to resolve URL on transition for ${track.id}")
                        }
                    }
                }

                // Pre-fetch lyrics for current track in background
                scope.launch(Dispatchers.IO) {
                    if (track.isOnlineTrack && LyricsParser.getCachedLyrics(track.id) == null) {
                        try {
                            LyricsParser.fetchOnlineLyrics(track.id, track.title, track.artist)
                        } catch (_: Exception) {}
                    }
                }

                // Sync metadata with MediaSession for lock screen / notification
                AudioService.companionService?.syncCurrentMetadata()
            }
        }
    }

    /** Update the in-memory queue so a given track points at a resolved URI. */
    private fun updateQueueTrackUri(trackId: String, uri: android.net.Uri) {
        var changed = false
        val updated = queue.map { t ->
            if (t.id == trackId && t.uri != uri) {
                changed = true
                t.copy(uri = uri)
            } else t
        }
        if (changed) {
            queue = updated
            _queue.value = updated
        }
    }

    /**
     * Replace the MediaItem at `index` so ExoPlayer picks up the freshly
     * resolved signed URL on its next prepare/transition without restarting
     * the whole timeline. Available since Media3 1.2.
     */
    private fun replaceMediaItemUri(index: Int, uri: android.net.Uri, track: Track) {
        val item = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumArtist(track.artist)
                    .setArtworkUri(track.displayArtUri)
                    .build()
            )
            .build()
        try {
            val pc = controller
            if (pc != null && index in 0 until pc.mediaItemCount) {
                pc.replaceMediaItem(index, item)
                return
            }
            AudioService.companionPlayer?.let { exo ->
                if (index in 0 until exo.mediaItemCount) exo.replaceMediaItem(index, item)
            }
        } catch (_: Throwable) { /* best effort */ }
    }

    // ── Backwards-compatible aliases (deprecated, use EndlessPlaybackEngine directly) ──

    /**
     * Legacy alias for setting auto-refill context.
     * Maps to EndlessPlaybackEngine.RefillContext internally.
     */
    @Deprecated("Use EndlessPlaybackEngine.RefillContext via playFromList parameters")
    fun setAutoRefillContext(
        type: String,
        id: String? = null,
        name: String? = null,
        seedTrackId: String? = null
    ) {
        val ctxType = when (type) {
            "wave" -> EndlessPlaybackEngine.RefillContext.Type.WAVE
            "artist" -> EndlessPlaybackEngine.RefillContext.Type.ARTIST
            "album" -> EndlessPlaybackEngine.RefillContext.Type.ALBUM
            "search" -> EndlessPlaybackEngine.RefillContext.Type.SEARCH
            "genre" -> EndlessPlaybackEngine.RefillContext.Type.GENRE
            "playlist" -> EndlessPlaybackEngine.RefillContext.Type.PLAYLIST
            "library" -> EndlessPlaybackEngine.RefillContext.Type.LIBRARY
            else -> EndlessPlaybackEngine.RefillContext.Type.WAVE
        }
        endlessEngine.setRefillContext(
            EndlessPlaybackEngine.RefillContext(
                type = ctxType,
                id = id,
                name = name,
                seedTrackId = seedTrackId
            )
        )
    }

    @Deprecated("Use EndlessPlaybackEngine.reset()")
    fun clearAutoRefillContext() {
        endlessEngine.reset()
    }

    fun init(context: Context) {
        if (appContext != null) return

        appContext = context.applicationContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        audioManager?.let { am ->
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            _volume.value = if (max > 0) current.toFloat() / max.toFloat() else 0.7f
        }

        // Initialize ICM API with key from native .so (JNI) — most secure path
        // Fallback to BuildConfig (from local.properties / GitHub Secret) for CI builds
        val nativeKey = try { IcmKeyProvider.getApiKey() } catch (_: Throwable) { "" }
        val buildConfigKey = com.liquidmusicglass.BuildConfig.ICM_API_KEY
        val prefs = context.getSharedPreferences("icm", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", null)

        // Make sure IcmAuthRepository is initialized so its partner_user_id
        // is the single source of truth across the app. IcmAuthRepository.init()
        // is idempotent and safe to call multiple times.
        com.liquidmusicglass.api.icm.IcmAuthRepository.init(context)
        val partnerUserId = com.liquidmusicglass.api.icm.IcmAuthRepository.ensurePartnerUserId()

        val activeKey = when {
            nativeKey.isNotBlank() && nativeKey.startsWith("pk_") -> nativeKey
            buildConfigKey.isNotBlank() && buildConfigKey.startsWith("pk_") -> buildConfigKey
            !savedKey.isNullOrBlank() && savedKey.startsWith("pk_") -> savedKey
            else -> null
        }

        if (activeKey != null) {
            com.liquidmusicglass.api.icm.IcmRepository.init(activeKey, partnerUserId)
            // Promote any cached session token into the API client too.
            com.liquidmusicglass.api.icm.IcmAuthRepository.getSessionToken()?.let { token ->
                com.liquidmusicglass.api.icm.IcmRepository.setSessionToken(token)
            }
        } else {
            android.util.Log.e("PlayerController", "ICM API key not available. Native: '${nativeKey.take(3)}...', BuildConfig: '${buildConfigKey.take(3)}...', Saved: '${savedKey?.take(3)}...'")
        }

        // Load play history from local storage
        scope.launch {
            val history = com.liquidmusicglass.data.local.LocalStorage.getHistory(context)
            recentHistory.clear()
            recentHistory.addAll(
                history.map { h ->
                    Track(
                        id = h.trackId,
                        title = h.title,
                        artist = h.artist,
                        albumName = "",
                        uri = android.net.Uri.parse("https://byicloud.online/track/${h.trackId}"),
                        durationMs = h.durationMs,
                        albumId = 0L,
                        coverUrl = h.coverUrl
                    )
                }
            )
            _recentlyPlayed.value = recentHistory.toList()
        }

        // Load persisted AutoMix state
        loadAutoMixState(context)

        // ── Start global endless playback engine ──
        // REMOVED: startGlobalQueueMonitor() — event-driven refill via onMediaItemTransition

        scope.launch {
            obtainController(context)
        }
    }

    private suspend fun obtainController(context: Context): MediaController? {
        controller?.let { return it }
        if (mediaControllerUnavailable) return null

        while (isConnectingController) {
            delay(50)
            controller?.let { return it }
        }

        isConnectingController = true

        return try {
            val sessionToken = SessionToken(
                context.applicationContext,
                ComponentName(context.applicationContext, AudioService::class.java)
            )

            val builtController = try {
                kotlinx.coroutines.withTimeout(6_000) {
                    suspendCancellableCoroutine<MediaController?> { continuation ->
                        val future = MediaController.Builder(
                            context.applicationContext,
                            sessionToken
                        ).buildAsync()

                        future.addListener(
                            {
                                try {
                                    val result = future.get()
                                    if (continuation.isActive) {
                                        continuation.resume(result)
                                    }
                                } catch (_: Throwable) {
                                    if (continuation.isActive) {
                                        continuation.resume(null)
                                    }
                                }
                            },
                            MoreExecutors.directExecutor()
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.e("PlayerController", "obtainController timed out — falling back to direct player")
                mediaControllerUnavailable = true
                null
            }

            builtController?.let { newController ->
                controller = newController
                newController.removeListener(playerListener)
                newController.addListener(playerListener)
            }

            builtController
        } finally {
            isConnectingController = false
        }
    }

    /**
     * Получить текущую очередь.
     */
    fun getCurrentQueue(): List<Track> = queue.toList()

    /**
     * Установить очередь треков из ICM API.
     * Also syncs with ExoPlayer for seamless playback.
     */
    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        queue = tracks.toMutableList()
        _queue.value = tracks
        if (tracks.isNotEmpty() && startIndex in tracks.indices) {
            currentIndex = startIndex
            _currentTrack.value = tracks[startIndex]
            _durationMs.value = tracks[startIndex].durationMs
        }
        // Sync with ExoPlayer
        if (tracks.isEmpty()) return
        val pc = controller
        val safeIdx = startIndex.coerceIn(0, tracks.lastIndex)
        if (pc != null) {
            val mediaItems = tracks.map { buildMediaItem(it) }
            pc.setMediaItems(mediaItems, safeIdx, 0L)
        } else {
            AudioService.companionPlayer?.let { exo ->
                val mediaItems = tracks.map { buildMediaItem(it) }
                exo.setMediaItems(mediaItems, safeIdx, 0L)
            }
        }
    }

    /**
     * Добавить трек в конец очереди.
     * Если очередь пуста — устанавливает как текущий.
     * Также добавляет в ExoPlayer для бесшовного воспроизведения.
     */
    fun addToQueue(track: Track) {
        queue = queue + track
        _queue.value = queue
        if (currentIndex < 0) {
            currentIndex = 0
            _currentTrack.value = track
            _durationMs.value = track.durationMs
        }
        // Add to ExoPlayer for seamless playback
        val playerController = controller
        if (playerController != null) {
            val mediaItem = buildMediaItem(track)
            playerController.addMediaItem(mediaItem)
        } else {
            AudioService.companionPlayer?.addMediaItem(buildMediaItem(track))
        }
    }

    /**
     * Заменить очередь полным списком и сразу запустить выбранный индекс.
     * Используется на Поиске/Альбоме/Артисте — клик по треку из списка
     * формирует всю последующую очередь.
     *
     * ── АТОМАРНАЯ ОЧИСТКА: при ручном переключении на другой контент ──
     * старый бесконечный хвост очищается, мониторинг запускается заново.
     *
     * Resolves the signed URL for [startIndex] up-front so ExoPlayer's first
     * MediaItem contains a real CDN url (avoids the placeholder bug). The
     * remaining items get their URLs pre-fetched via [preloadUpcoming].
     */
    fun playFromList(
        context: Context,
        tracks: List<Track>,
        startIndex: Int,
        autoRefillType: String? = null,
        autoRefillId: String? = null,
        autoRefillName: String? = null,
        autoRefillSeedTrackId: String? = null
    ) {
        if (tracks.isEmpty()) return
        val idx = startIndex.coerceIn(0, tracks.lastIndex)

        // ── ATOMIC RESET: clear old endless tail and restart monitoring ──
        endlessEngine.reset()
        endlessEngine.registerTracks(tracks.map { it.id })

        // Set new refill context based on playback source
        val refillContext = when (autoRefillType) {
            "album" -> EndlessPlaybackEngine.RefillContext(
                type = EndlessPlaybackEngine.RefillContext.Type.ALBUM,
                id = autoRefillId,
                name = autoRefillName,
                seedTrackId = autoRefillSeedTrackId ?: tracks.getOrNull(idx)?.id
            )
            "artist" -> EndlessPlaybackEngine.RefillContext(
                type = EndlessPlaybackEngine.RefillContext.Type.ARTIST,
                id = autoRefillId,
                name = autoRefillName,
                seedTrackId = autoRefillSeedTrackId ?: tracks.getOrNull(idx)?.id
            )
            "search" -> EndlessPlaybackEngine.RefillContext(
                type = EndlessPlaybackEngine.RefillContext.Type.SEARCH,
                id = autoRefillId,
                name = autoRefillName,
                seedTrackId = autoRefillSeedTrackId ?: tracks.getOrNull(idx)?.id
            )
            "genre" -> EndlessPlaybackEngine.RefillContext(
                type = EndlessPlaybackEngine.RefillContext.Type.GENRE,
                id = autoRefillId,
                name = autoRefillName,
                genre = autoRefillName,
                seedTrackId = autoRefillSeedTrackId ?: tracks.getOrNull(idx)?.id
            )
            "playlist" -> EndlessPlaybackEngine.RefillContext(
                type = EndlessPlaybackEngine.RefillContext.Type.PLAYLIST,
                id = autoRefillId,
                name = autoRefillName,
                seedTrackId = autoRefillSeedTrackId ?: tracks.getOrNull(idx)?.id
            )
            "library" -> EndlessPlaybackEngine.RefillContext(
                type = EndlessPlaybackEngine.RefillContext.Type.LIBRARY,
                id = autoRefillId,
                name = autoRefillName,
                seedTrackId = autoRefillSeedTrackId ?: tracks.getOrNull(idx)?.id
            )
            "wave" -> EndlessPlaybackEngine.RefillContext(
                type = EndlessPlaybackEngine.RefillContext.Type.WAVE,
                id = autoRefillId,
                name = autoRefillName,
                seedTrackId = autoRefillSeedTrackId ?: tracks.getOrNull(idx)?.id
            )
            else -> {
                // Auto-detect context from track metadata
                val track = tracks.getOrNull(idx)
                if (track != null) {
                    val genre = detectGenre(track)
                    when {
                        genre != null -> EndlessPlaybackEngine.RefillContext(
                            type = EndlessPlaybackEngine.RefillContext.Type.GENRE,
                            name = genre,
                            genre = genre,
                            seedTrackId = track.id
                        )
                        track.artist.isNotBlank() -> EndlessPlaybackEngine.RefillContext(
                            type = EndlessPlaybackEngine.RefillContext.Type.ARTIST,
                            id = track.artist,
                            name = track.artist,
                            seedTrackId = track.id
                        )
                        else -> EndlessPlaybackEngine.RefillContext(
                            type = EndlessPlaybackEngine.RefillContext.Type.WAVE,
                            seedTrackId = track.id
                        )
                    }
                } else null
            }
        }
        refillContext?.let { endlessEngine.setRefillContext(it) }

        scope.launch {
            android.util.Log.d("PlayerController", "playFromList: ${tracks.size} tracks, startIndex=$idx, context=${refillContext?.type?.name}")

            // ── Step 1: Resolve stream URL for START track only (fast path) ──
            val startTrack = tracks[idx]
            val resolvedStartTrack = if (startTrack.isOnlineTrack) {
                val resolvedUri = try {
                    getCachedOrResolveStreamUrl(startTrack.id)
                } catch (e: Exception) {
                    android.util.Log.e("PlayerController", "Failed to resolve URL for start track ${startTrack.id}: ${e.message}")
                    null
                }
                if (resolvedUri != null) {
                    android.util.Log.d("PlayerController", "Resolved start track ${startTrack.id}: ${resolvedUri.toString().take(60)}...")
                    startTrack.copy(uri = resolvedUri)
                } else {
                    android.util.Log.e("PlayerController", "CRITICAL: Start track ${startTrack.id} failed to resolve!")
                    _isPlaying.value = false
                    return@launch
                }
            } else startTrack

            // ── Step 2: Build MediaItems for ALL tracks (use placeholder URIs for non-start) ──
            val mediaItems = tracks.map { track ->
                MediaItem.Builder()
                    .setMediaId(track.id)
                    .setUri(track.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setAlbumArtist(track.artist)
                            .setArtworkUri(track.displayArtUri)
                            .build()
                    )
                    .build()
            }

            // Update start track URI in mediaItems
            val updatedMediaItems = mediaItems.mapIndexed { i, item ->
                if (i == idx) {
                    MediaItem.Builder()
                        .setMediaId(resolvedStartTrack.id)
                        .setUri(resolvedStartTrack.uri)
                        .setMediaMetadata(item.mediaMetadata)
                        .build()
                } else item
            }

            // ── Step 3: Update internal queue state ──
            queue = tracks
            _queue.value = tracks
            currentIndex = idx
            _currentTrack.value = resolvedStartTrack
            _durationMs.value = resolvedStartTrack.durationMs
            _currentPositionMs.value = 0L
            _isPlaying.value = true

            // ── Step 4: Load into ExoPlayer and START playback ──
            val playerController = obtainController(context)
            if (playerController != null) {
                android.util.Log.d("PlayerController", "Using MediaController: setMediaItems(${updatedMediaItems.size}, $idx, 0)")
                playerController.setMediaItems(updatedMediaItems, idx, 0L)
                playerController.prepare()
                playerController.play()
            } else {
                val exo = AudioService.companionPlayer
                if (exo != null) {
                    android.util.Log.d("PlayerController", "Using direct ExoPlayer: setMediaItems(${updatedMediaItems.size}, $idx, 0)")
                    exo.setMediaItems(updatedMediaItems, idx, 0L)
                    exo.prepare()
                    exo.play()
                } else {
                    android.util.Log.e("PlayerController", "NO PLAYER AVAILABLE — cannot start playback")
                    _isPlaying.value = false
                    return@launch
                }
            }

            // ── Step 5: Start position updates and tracking ──
            startPositionUpdates()
            addToRecent(resolvedStartTrack)

            // ── Step 6: Log wave playback ──
            logWavePlaybackOnSwitch()
            waveTrackId = resolvedStartTrack.id
            waveTrackStartTimeMs = System.currentTimeMillis()
            waveTrackPlayedMs = 0L

            // ── Step 7: Resolve remaining tracks in background (non-blocking) ──
            scope.launch(Dispatchers.IO) {
                tracks.forEachIndexed { i, track ->
                    if (i != idx && track.isOnlineTrack) {
                        try {
                            getCachedOrResolveStreamUrl(track.id)
                        } catch (e: Exception) {
                            android.util.Log.e("PlayerController", "Background resolve failed for ${track.id}: ${e.message}")
                        }
                    }
                }
            }

            // ── Step 8: Pre-fetch lyrics ──
            scope.launch(Dispatchers.IO) {
                if (resolvedStartTrack.isOnlineTrack && LyricsParser.getCachedLyrics(resolvedStartTrack.id) == null) {
                    try {
                        LyricsParser.fetchOnlineLyrics(resolvedStartTrack.id, resolvedStartTrack.title, resolvedStartTrack.artist)
                    } catch (_: Exception) {}
                }
            }

            // ── Step 9: Refill check after safe delay (3s) ──
            scope.launch {
                delay(3000L)
                android.util.Log.d("PlayerController", "Triggering refill check after 3s delay")
                endlessEngine.checkAndRefillIfNeeded()
            }

            AudioService.companionService?.notifyManualNavigation()
        }
    }

    /**
     * Добавить трек в очередь и сразу воспроизвести.
     * Сохраняет существующую очередь, вставляет трек после текущего.
     */
    fun playNext(track: Track, context: Context) {
        if (queue.isEmpty()) {
            // Single track — set up wave context
            endlessEngine.reset()
            endlessEngine.registerTrack(track.id)
            val genre = detectGenre(track)
            val refillCtx = when {
                genre != null -> EndlessPlaybackEngine.RefillContext(
                    type = EndlessPlaybackEngine.RefillContext.Type.GENRE,
                    name = genre,
                    genre = genre,
                    seedTrackId = track.id
                )
                track.artist.isNotBlank() -> EndlessPlaybackEngine.RefillContext(
                    type = EndlessPlaybackEngine.RefillContext.Type.ARTIST,
                    id = track.artist,
                    name = track.artist,
                    seedTrackId = track.id
                )
                else -> EndlessPlaybackEngine.RefillContext(
                    type = EndlessPlaybackEngine.RefillContext.Type.WAVE,
                    seedTrackId = track.id
                )
            }
            endlessEngine.setRefillContext(refillCtx)
            setQueue(listOf(track), 0)
            playTrack(context, 0)
            scope.launch {
                delay(3000L)
                endlessEngine.checkAndRefillIfNeeded()
            }
            return
        }
        val insertIndex = (currentIndex + 1).coerceAtMost(queue.size)
        val newQueue = queue.toMutableList()
        newQueue.add(insertIndex, track)
        queue = newQueue
        _queue.value = queue
        endlessEngine.registerTrack(track.id)

        // Sync insert with ExoPlayer for seamless transitions
        val mediaItem = buildMediaItem(track)
        controller?.addMediaItem(insertIndex, mediaItem)
        AudioService.companionPlayer?.addMediaItem(insertIndex, mediaItem)

        playTrack(context, insertIndex)
    }

    private fun buildMediaItem(track: Track): MediaItem {
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumArtist(track.artist)
                    .setArtworkUri(track.displayArtUri)
                    .build()
            )
            .build()
    }

    fun setAutoMix(enabled: Boolean) {
        _autoMixEnabled.value = enabled
        saveAutoMixState()
        AudioService.setAutoMixEnabled(enabled)
    }

    /**
     * Воспроизвести трек из очереди по индексу.
     * Docs: "URL из поля url ответа /track подставляй прямо в плеер."
     * Если очередь уже загружена в ExoPlayer — просто seek, не перезагружаем.
     */
    fun playTrack(context: Context, index: Int) {
        if (index !in queue.indices) return

        // Fast path: if ExoPlayer already has this queue, just seek
        val pc = controller
        if (pc != null && pc.mediaItemCount == queue.size && index < pc.mediaItemCount) {
            val allMatch = queue.withIndex().all { (i, t) ->
                i < pc.mediaItemCount && pc.getMediaItemAt(i).mediaId == t.id
            }
            if (allMatch) {
                logWavePlaybackOnSwitch()
                pc.seekTo(index, 0L)
                pc.play()
                currentIndex = index
                _currentTrack.value = queue[index]
                _durationMs.value = queue[index].durationMs
                _currentPositionMs.value = 0L
                _isPlaying.value = true
                startPositionUpdates()
                addToRecent(queue[index])
                preloadNextTrack()
                return
            }
        }

        // Log previous wave track before switching
        logWavePlaybackOnSwitch()

        scope.launch {
            val track = queue[index]

            // Update UI state immediately
            currentIndex = index
            _currentTrack.value = track
            _durationMs.value = track.durationMs
            _currentPositionMs.value = 0L
            _isPlaying.value = true
            startPositionUpdates()

            // Start wave playback tracking
            waveTrackId = track.id
            waveTrackStartTimeMs = System.currentTimeMillis()
            waveTrackPlayedMs = 0L

            // Resolve current track URL immediately (fast start)
            android.util.Log.d("PlayerController", "playTrack: ${track.id} | online=${track.isOnlineTrack} | uri=${track.uri}")
            val currentUri = if (track.isOnlineTrack) {
                val resolved = getCachedOrResolveStreamUrl(track.id)
                if (resolved == null) {
                    android.util.Log.e("PlayerController", "Failed to resolve stream URL for ${track.id}")
                    _isPlaying.value = false
                    stopPositionUpdates()
                    return@launch
                }
                android.util.Log.d("PlayerController", "playTrack: resolved URL for ${track.id}: ${resolved.toString().take(80)}...")
                resolved
            } else {
                track.uri
            }

            // Build MediaItem list for the entire queue
            val mediaItems = queue.mapIndexed { i, t ->
                val uri = if (i == index) {
                    currentUri
                } else {
                    if (t.isOnlineTrack) {
                        val cached = getCachedStreamUrl(t.id)
                        cached ?: t.uri
                    } else {
                        t.uri
                    }
                }
                MediaItem.Builder()
                    .setMediaId(t.id)
                    .setUri(uri)
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

            // Try MediaController first, fallback to direct ExoPlayer
            val playerController = obtainController(context)
            android.util.Log.d("PlayerController", "playTrack: MediaController=${playerController != null}, companionPlayer=${AudioService.companionPlayer != null}")
            if (playerController != null) {
                android.util.Log.d("PlayerController", "playTrack: using MediaController")
                playerController.setMediaItems(mediaItems, index, 0L)
                playerController.prepare()
                playerController.play()
            } else {
                val exo = AudioService.companionPlayer
                if (exo != null) {
                    android.util.Log.d("PlayerController", "playTrack: using direct ExoPlayer")
                    exo.setMediaItems(mediaItems, index, 0L)
                    exo.prepare()
                    exo.play()
                } else {
                    android.util.Log.e("PlayerController", "playTrack: NO PLAYER AVAILABLE")
                    _isPlaying.value = false
                    stopPositionUpdates()
                    return@launch
                }
            }

            addToRecent(track)
            preloadNextTrack()

            // Pre-fetch lyrics for current track immediately
            scope.launch(Dispatchers.IO) {
                if (track.isOnlineTrack && LyricsParser.getCachedLyrics(track.id) == null) {
                    try {
                        LyricsParser.fetchOnlineLyrics(track.id, track.title, track.artist)
                    } catch (_: Exception) {}
                }
            }

            AudioService.companionService?.notifyManualNavigation()
        }
    }

    /**
     * Get cached stream URL without triggering a network request.
     */
    private fun getCachedStreamUrl(trackId: String): android.net.Uri? {
        val now = System.currentTimeMillis()
        val cached = streamUrlCache[trackId]
        val expiry = streamUrlCacheExpiry[trackId] ?: 0L
        return if (cached != null && now < expiry) cached else null
    }

    /**
     * Determine the effective stream quality based on user subscription status.
     */
    private fun getEffectiveQuality(requestedQuality: String? = null, trackId: String): String? {
        val isSecondary = trackId.startsWith("secondary_")
        val hasPremium = com.liquidmusicglass.api.icm.IcmAuthRepository.isPremium.value
        val maxQuality = com.liquidmusicglass.api.icm.IcmAuthRepository.maxQuality.value
        val allowedQualities = com.liquidmusicglass.api.icm.IcmAuthRepository.allowedQualities.value

        val desired = requestedQuality
            ?: com.liquidmusicglass.api.icm.IcmApi.getInstance().streamQuality
            ?: com.liquidmusicglass.api.icm.IcmStreamQuality.K256

        if (!hasPremium && maxQuality != null) {
            val qualityRank = mapOf(
                "128K" to 1, "256K" to 2, "320K" to 3, "ALAC" to 4,
                "MP3 128K" to 1, "MP3 256K" to 2, "MP3 320K" to 3
            )
            val desiredRank = qualityRank[desired] ?: 2
            val maxRank = qualityRank[maxQuality] ?: 2
            if (desiredRank > maxRank) {
                return maxQuality
            }
        }

        if (isSecondary && !hasPremium) {
            if (desired == "ALAC" || desired == "MP3 320K" || desired == "320K") {
                return "MP3 256K"
            }
        }

        return desired
    }

    private suspend fun getCachedOrResolveStreamUrl(trackId: String, requestedQuality: String? = null): android.net.Uri? {
        val now = System.currentTimeMillis()
        val cached = streamUrlCache[trackId]
        val expiry = streamUrlCacheExpiry[trackId] ?: 0L
        if (cached != null && now < expiry) {
            android.util.Log.d("PlayerController", "Stream URL cache hit for $trackId")
            return cached
        }

        return try {
            kotlinx.coroutines.withTimeout(15_000) {
                android.util.Log.d("PlayerController", "Resolving stream URL for $trackId...")
                val effectiveQuality = getEffectiveQuality(requestedQuality, trackId)
                val trackInfo = com.liquidmusicglass.api.icm.IcmRepository.getTrackInfo(trackId, quality = effectiveQuality)
                if (trackInfo != null) {
                    val uri = android.net.Uri.parse(trackInfo.url)
                    streamUrlCache[trackId] = uri
                    val apiExpiry = trackInfo.expiresAt * 1000L
                    val ttl = if (apiExpiry > now) {
                        (apiExpiry - now - 60_000L).coerceAtLeast(60_000L)
                    } else {
                        STREAM_CACHE_TTL_MS
                    }
                    streamUrlCacheExpiry[trackId] = now + ttl
                    android.util.Log.d("PlayerController", "Stream URL resolved for $trackId (quality=$effectiveQuality): ${trackInfo.url.take(60)}... (expires in ${ttl/1000}s)")
                    uri
                } else {
                    android.util.Log.e("PlayerController", "Stream URL resolve returned null for $trackId")
                    null
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            android.util.Log.e("PlayerController", "Stream URL resolve timeout for $trackId")
            null
        } catch (e: Exception) {
            android.util.Log.e("PlayerController", "Stream URL resolve error for $trackId: ${e.message}")
            null
        }
    }

    fun togglePlayPause(context: Context) {
        scope.launch {
            val playerController = obtainController(context)

            if (playerController == null) {
                if (queue.isNotEmpty()) {
                    playTrack(context, if (currentIndex >= 0) currentIndex else 0)
                }
                return@launch
            }

            if (playerController.isPlaying) {
                playerController.pause()
                _isPlaying.value = false
                stopPositionUpdates()
            } else {
                if (playerController.mediaItemCount == 0 && queue.isNotEmpty()) {
                    playTrack(context, if (currentIndex >= 0) currentIndex else 0)
                } else {
                    playerController.play()
                    _isPlaying.value = true
                    startPositionUpdates()
                }
            }
        }
    }

    fun skipNext(context: Context) {
        logWavePlaybackOnSwitch()

        val playerController = controller
        if (playerController != null && playerController.hasNextMediaItem()) {
            playerController.seekToNextMediaItem()
            playerController.play()
            AudioService.companionService?.notifyManualNavigation()
            return
        }

        if (queue.isEmpty()) return
        val nextIndex = if (currentIndex + 1 < queue.size) currentIndex + 1 else 0
        playTrack(context, nextIndex)
    }

    fun skipPrevious(context: Context) {
        logWavePlaybackOnSwitch()
        val playerController = controller
        if (playerController != null && playerController.currentPosition > 3000L) {
            playerController.seekTo(0L)
            _currentPositionMs.value = 0L
            AudioService.companionService?.notifyManualNavigation()
            return
        }

        if (playerController != null && playerController.hasPreviousMediaItem()) {
            playerController.seekToPreviousMediaItem()
            playerController.play()
            AudioService.companionService?.notifyManualNavigation()
            return
        }

        if (queue.isEmpty()) return
        val prevIndex = if (currentIndex > 0) currentIndex - 1 else queue.lastIndex
        playTrack(appContext ?: return, prevIndex)
    }

    fun seekTo(positionMs: Long) {
        val safePosition = positionMs.coerceIn(0L, (_durationMs.value - 500L).coerceAtLeast(0L))
        controller?.seekTo(safePosition)
        _currentPositionMs.value = safePosition
        AudioService.companionService?.notifyManualNavigation()
    }

    fun setVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        _volume.value = clamped

        audioManager?.let { am ->
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (clamped * max).toInt()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
    }

    // ── Shuffle ──
    fun toggleShuffle() {
        _shuffleEnabled.value = !_shuffleEnabled.value
        controller?.shuffleModeEnabled = _shuffleEnabled.value
    }

    // ── Repeat: 0→1→2→0 ──
    fun cycleRepeatMode() {
        val next = (_repeatMode.value + 1) % 3
        _repeatMode.value = next
        controller?.repeatMode = when (next) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ── Favorites (backed by Room DB via LibraryRepository) ──
    fun toggleFavorite(trackId: String) {
        scope.launch(Dispatchers.IO) {
            val repo = appContext?.let {
                com.liquidmusicglass.data.local.db.LibraryRepository.getInstance(it)
            } ?: return@launch
            val track = _currentTrack.value
            if (track != null && track.id == trackId) {
                repo.toggleFavorite(track)
            } else {
                repo.toggleFavoriteById(trackId)
            }
        }
    }

    fun setFavoriteIds(ids: Set<String>) {
        _favoriteIds.value = ids
    }

    fun isFavorite(trackId: String): Boolean = _favoriteIds.value.contains(trackId)

    fun getFavoriteTracks(): List<Track> {
        val ids = _favoriteIds.value
        return queue.filter { it.id in ids }
    }

    // ── Playlists ──
    fun createPlaylist(name: String): Playlist {
        val playlist = Playlist(id = nextPlaylistId++, name = name, trackIds = emptyList())
        _playlists.value = _playlists.value + playlist
        savePlaylists()
        return playlist
    }

    fun addToPlaylist(playlistId: Long, trackId: String) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == playlistId && trackId !in pl.trackIds) {
                pl.copy(trackIds = pl.trackIds + trackId)
            } else pl
        }
        savePlaylists()
    }

    fun removeFromPlaylist(playlistId: Long, trackId: String) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == playlistId) {
                pl.copy(trackIds = pl.trackIds.filter { it != trackId })
            } else pl
        }
        savePlaylists()
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == playlistId) pl.copy(name = newName) else pl
        }
        savePlaylists()
    }

    fun deletePlaylist(playlistId: Long) {
        _playlists.value = _playlists.value.filter { it.id != playlistId }
        savePlaylists()
    }

    fun getPlaylistTracks(playlistId: Long): List<Track> {
        val pl = _playlists.value.firstOrNull { it.id == playlistId } ?: return emptyList()
        return pl.trackIds.mapNotNull { trackId -> queue.firstOrNull { it.id == trackId } }
    }

    fun playPlaylist(context: Context, playlistId: Long) {
        val tracks = getPlaylistTracks(playlistId)
        if (tracks.isEmpty()) return
        val firstTrack = tracks.first()
        val idx = queue.indexOfFirst { it.id == firstTrack.id }
        if (idx >= 0) playTrack(context, idx)
    }

    // ── Share ──
    fun shareCurrentTrack(context: Context) {
        val track = _currentTrack.value ?: return
        val shareText = buildString {
            append("${track.title} by ${track.artist}")
            if (track.albumName.isNotBlank()) append(" — ${track.albumName}")
            append("\n\nhttps://music.apple.com/search?term=${
                java.net.URLEncoder.encode("${track.title} ${track.artist}", "UTF-8")
            }")
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, shareText)
        }
        val chooser = android.content.Intent.createChooser(intent, "Share Track")
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    // ── Preload next track ──
    private var preloadJob: Job? = null
    private var aggressivePrefetchJob: Job? = null

    /**
     * Backwards-compatible alias: prefetches the next [PREFETCH_AHEAD] tracks
     * so ExoPlayer has signed URLs and can start buffering before the
     * current item finishes. Docs 7.3: «pre-fetch 3–5 tracks».
     */
    fun preloadNextTrack() = preloadUpcoming()

    /**
     * Resolve `POST /track` URLs for the next [PREFETCH_AHEAD] queue items and
     * push them into ExoPlayer's MediaItems so the engine starts buffering
     * the streams in the background.
     *
     * Uses premium quality (320K/ALAC) if user has active subscription.
     * Also adds missing MediaItems to ExoPlayer so seamless auto-advance works.
     */
    fun preloadUpcoming() {
        preloadJob?.cancel()
        if (queue.isEmpty() || currentIndex < 0) return

        // Collect up to PREFETCH_AHEAD upcoming online tracks.
        val upcoming = mutableListOf<Pair<Int, Track>>()
        var idx = currentIndex + 1
        while (idx < queue.size && upcoming.size < PREFETCH_AHEAD) {
            val t = queue[idx]
            if (t.isOnlineTrack) {
                upcoming.add(idx to t)
            }
            idx++
        }
        if (upcoming.isEmpty()) return

        preloadJob = scope.launch {
            val pc = controller
            val exo = AudioService.companionPlayer
            val player = pc ?: exo

            for ((queueIndex, track) in upcoming) {
                try {
                    // Resolve stream URL with premium quality if available
                    val effectiveQuality = getPremiumQualityForPreload()
                    val uri = getCachedOrResolveStreamUrl(track.id, effectiveQuality) ?: continue
                    updateQueueTrackUri(track.id, uri)

                    val freshIndex = queue.indexOfFirst { it.id == track.id }
                    if (freshIndex < 0) continue
                    val updatedTrack = queue[freshIndex]

                    if (player != null) {
                        if (freshIndex < player.mediaItemCount) {
                            // MediaItem exists — update its URI so ExoPlayer can buffer
                            replaceMediaItemUri(freshIndex, uri, updatedTrack)
                        } else {
                            // MediaItem missing — add it to ExoPlayer's timeline
                            val mediaItem = buildMediaItem(updatedTrack)
                            player.addMediaItem(mediaItem)
                        }
                    }
                } catch (_: Exception) { /* best effort */ }
            }
        }
    }

    /**
     * Returns premium quality string for preloading if user has subscription.
     * Otherwise returns null (uses default quality).
     */
    private fun getPremiumQualityForPreload(): String? {
        return if (com.liquidmusicglass.api.icm.IcmAuthRepository.isPremium.value) {
            val allowed = com.liquidmusicglass.api.icm.IcmAuthRepository.allowedQualities.value
            when {
                "ALAC" in allowed -> "ALAC"
                "320K" in allowed -> "320K"
                else -> "320K"
            }
        } else null
    }

    /**
     * Start aggressive prefetch when track passes 50% (equator).
     * DEPRECATED: Use EndlessPlaybackEngine.startEquatorPrefetch() instead.
     * Kept for backwards compatibility.
     */
    fun startAggressivePrefetch() {
        // Now handled by EndlessPlaybackEngine in onMediaItemTransition
    }

    // ── Current queue access ──
    fun getCurrentIndex(): Int = currentIndex

    private var prefetchJob: Job? = null
    private const val PREFETCH_THRESHOLD_MS = 120_000L // 120 seconds before end (2 min)

    // Genre keywords for auto-track selection
    private val genreKeywords = mapOf(
        "electro" to "electro house",
        "house" to "electro house",
        "edm" to "electro house",
        "dubstep" to "electro house",
        "trance" to "trance",
        "techno" to "techno",
        "rock" to "rock",
        "metal" to "rock",
        "punk" to "rock",
        "rap" to "hip-hop",
        "hip-hop" to "hip-hop",
        "trap" to "hip-hop",
        "pop" to "pop",
        "dance" to "pop",
        "rnb" to "rnb",
        "jazz" to "jazz",
        "classical" to "classical",
        "country" to "country",
        "reggae" to "reggae",
        "latin" to "latin"
    )

    /**
     * Detect genre from track title and artist name.
     */
    fun detectGenre(track: Track): String? {
        val text = "${track.title} ${track.artist}".lowercase()
        for ((keyword, genre) in genreKeywords) {
            if (keyword in text) return genre
        }
        return null
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()

        positionJob = scope.launch {
            while (true) {
                val playerController = controller
                if (playerController != null) {
                    _currentPositionMs.value = playerController.currentPosition.coerceAtLeast(0L)
                    val duration = playerController.duration
                    if (duration > 0) {
                        _durationMs.value = duration
                    }
                }

                _isMixing.value = AudioService.getMixingState()

                delay(POSITION_UPDATE_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    // ── Wave Playback Logging ──

    /**
     * Log playback when switching away from a wave track.
     * Called automatically on skipNext / skipPrevious / playTrack and
     * on seamless ExoPlayer transitions.
     */
    private fun logWavePlaybackOnSwitch(autoCompleted: Boolean = false) {
        val trackId = waveTrackId ?: return
        val startTime = waveTrackStartTimeMs
        if (startTime <= 0) return

        val playedMs = waveTrackPlayedMs + (System.currentTimeMillis() - startTime)
        val playedSeconds = playedMs / 1000.0
        val totalSeconds = (_currentTrack.value?.durationMs ?: 0L) / 1000.0

        // Skip extremely short plays unless this was a seamless completion.
        if (!autoCompleted && playedSeconds < 3.0) return

        val completed = when {
            autoCompleted -> true
            totalSeconds > 0 -> playedSeconds >= totalSeconds * 0.85
            else -> null
        }
        val skipped = when {
            autoCompleted -> false
            totalSeconds > 0 -> playedSeconds < totalSeconds * 0.15
            else -> null
        }

        scope.launch {
            try {
                com.liquidmusicglass.api.icm.IcmRepository.logWavePlayback(
                    trackId = trackId,
                    playedSeconds = playedSeconds,
                    totalSeconds = totalSeconds.takeIf { it > 0 },
                    completed = completed,
                    skipped = skipped
                )
            } catch (_: Exception) {}
        }

        // Reset tracking
        waveTrackId = null
        waveTrackStartTimeMs = 0L
        waveTrackPlayedMs = 0L
    }
}
