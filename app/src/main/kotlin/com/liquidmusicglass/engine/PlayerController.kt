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

    // ── Wave playback tracking ──
    private var waveTrackStartTimeMs: Long = 0L
    private var waveTrackPlayedMs: Long = 0L
    private var waveTrackId: String? = null

    // ── Stream URL cache (trackId -> resolved Uri) ──
    // Docs: "file_id — cache for a day+ by (trackId + region + quality)"
    private val streamUrlCache = mutableMapOf<String, android.net.Uri>()
    private val streamUrlCacheExpiry = mutableMapOf<String, Long>()
    private const val STREAM_CACHE_TTL_MS = 8 * 60 * 1000L // 8 min (URL valid ~10 min)

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

    // Auto-refill context
    private var autoRefillContext: AutoRefillContext? = null

    data class AutoRefillContext(
        val type: String, // "wave", "artist", "album", "search", "genre"
        val id: String?, // artistId, albumId, moodId, query
        val name: String? = null
    )

    fun setAutoRefillContext(type: String, id: String? = null, name: String? = null) {
        autoRefillContext = AutoRefillContext(type, id, name)
    }

    fun clearAutoRefillContext() {
        autoRefillContext = null
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                startPositionUpdates()
            } else {
                stopPositionUpdates()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val current = controller ?: return
            if (playbackState == Player.STATE_READY) {
                _durationMs.value = current.duration.coerceAtLeast(0L)
            }
            if (playbackState == Player.STATE_ENDED) {
                // Queue ended — auto-refill if context is set
                val remainingTracks = queue.size - currentIndex - 1
                if (remainingTracks < 1 && autoRefillContext != null) {
                    scope.launch {
                        autoRefillQueue()
                        // Play next track if available
                        if (currentIndex + 1 < queue.size) {
                            playTrack(appContext ?: return@launch, currentIndex + 1)
                        }
                    }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaId = mediaItem?.mediaId ?: return
            val index = queue.indexOfFirst { it.id == mediaId }
            if (index in queue.indices) {
                currentIndex = index
                val track = queue[index]
                _currentTrack.value = track
                _durationMs.value = track.durationMs
                _currentPositionMs.value = 0L
                addToRecent(track)

                // Lazy resolve stream URL for this track if online
                // Docs: "Когда URL истёк — позови /track заново. У нас сработает свой кеш, ответ придёт за миллисекунды."
                if (track.isOnlineTrack) {
                    scope.launch {
                        val resolvedUri = getCachedOrResolveStreamUrl(track.id)
                        if (resolvedUri != null && resolvedUri != track.uri) {
                            // Update queue with resolved URI for continuous playback
                            queue = queue.map { t ->
                                if (t.id == track.id) t.copy(uri = resolvedUri) else t
                            }
                            _queue.value = queue
                        }
                        // Preload next track after transition
                        preloadNextTrack()
                    }
                }

                // Auto-refill queue when running low
                val remainingTracks = queue.size - index - 1
                if (remainingTracks < 3 && autoRefillContext != null) {
                    scope.launch {
                        autoRefillQueue()
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
            }
        }
    }

    /**
     * Auto-refill queue based on current context when running low.
     * Called from player listener when remaining tracks < 3.
     */
    private suspend fun autoRefillQueue() {
        val ctx = autoRefillContext ?: return
        val exclude = queue.map { it.id }
        val newTracks = mutableListOf<Track>()

        when (ctx.type) {
            "wave" -> {
                // Personal wave
                repeat(5) {
                    val response = com.liquidmusicglass.api.icm.IcmRepository.getWaveNext(
                        exclude = (exclude + newTracks.map { it.id }).takeIf { it.isNotEmpty() },
                        recentSkips = 0
                    )
                    if (response != null && response.status == "ok" && response.track != null) {
                        val wt = response.track
                        newTracks.add(Track(
                            id = wt.id, title = wt.title, artist = wt.artist ?: "Unknown Artist",
                            albumName = "", durationMs = wt.durationMs,
                            uri = Uri.parse("https://byicloud.online/track/${wt.id}"),
                            coverUrl = wt.cover, albumId = wt.collectionId?.hashCode()?.toLong() ?: -1L
                        ))
                    }
                }
            }
            "artist" -> {
                // More tracks from same artist
                val artistId = ctx.id ?: return
                val response = com.liquidmusicglass.api.icm.IcmRepository.getArtist(artistId)
                response?.topSongs?.shuffled()?.take(5)?.forEach { song ->
                    if (song.id !in exclude && song.id !in newTracks.map { it.id }) {
                        newTracks.add(Track(
                            id = song.id, title = song.title, artist = song.artist ?: "Unknown Artist",
                            albumName = "", durationMs = song.durationMs,
                            uri = Uri.parse("https://byicloud.online/track/${song.id}"),
                            coverUrl = song.cover, albumId = song.id.hashCode().toLong()
                        ))
                    }
                }
            }
            "album" -> {
                // More tracks from same album (shouldn't happen often, but handle)
                val albumId = ctx.id ?: return
                val response = com.liquidmusicglass.api.icm.IcmRepository.getAlbum(albumId)
                response?.tracks?.shuffled()?.take(5)?.forEach { track ->
                    if (track.id !in exclude && track.id !in newTracks.map { it.id }) {
                        newTracks.add(Track(
                            id = track.id, title = track.title, artist = track.artist ?: "Unknown Artist",
                            albumName = "", durationMs = track.durationMs,
                            uri = Uri.parse("https://byicloud.online/track/${track.id}"),
                            coverUrl = track.cover, albumId = track.collectionId?.hashCode()?.toLong() ?: -1L
                        ))
                    }
                }
            }
            "search", "genre" -> {
                // Similar tracks by search query
                val query = ctx.name ?: ctx.id ?: return
                val result = com.liquidmusicglass.api.icm.IcmRepository.searchAll(query)
                result?.items?.filter { it.isTrack && it.id !in exclude }?.shuffled()?.take(5)?.forEach { item ->
                    newTracks.add(Track(
                        id = item.id, title = item.title, artist = item.displayArtist,
                        albumName = item.album ?: "", durationMs = item.durationMs,
                        uri = Uri.parse("https://byicloud.online/track/${item.id}"),
                        coverUrl = item.cover, albumId = item.collectionId?.toLongOrNull() ?: 0L
                    ))
                }
            }
            "playlist" -> {
                // For playlists — refill with wave tracks (personalized)
                repeat(5) {
                    val response = com.liquidmusicglass.api.icm.IcmRepository.getWaveNext(
                        exclude = (exclude + newTracks.map { it.id }).takeIf { it.isNotEmpty() },
                        recentSkips = 0
                    )
                    if (response != null && response.status == "ok" && response.track != null) {
                        val wt = response.track
                        newTracks.add(Track(
                            id = wt.id, title = wt.title, artist = wt.artist ?: "Unknown Artist",
                            albumName = "", durationMs = wt.durationMs,
                            uri = Uri.parse("https://byicloud.online/track/${wt.id}"),
                            coverUrl = wt.cover, albumId = wt.collectionId?.hashCode()?.toLong() ?: -1L
                        ))
                    }
                }
            }
        }

        newTracks.forEach { addToQueue(it) }
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
        val savedUserId = prefs.getString("partner_user_id", null)

        val activeKey = when {
            nativeKey.isNotBlank() && nativeKey.startsWith("pk_") -> nativeKey
            buildConfigKey.isNotBlank() && buildConfigKey.startsWith("pk_") -> buildConfigKey
            !savedKey.isNullOrBlank() && savedKey.startsWith("pk_") -> savedKey
            else -> null
        }

        if (activeKey != null) {
            com.liquidmusicglass.api.icm.IcmRepository.init(activeKey, savedUserId)
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
     */
    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        queue = tracks.toMutableList()
        _queue.value = tracks
        if (tracks.isNotEmpty() && startIndex in tracks.indices) {
            currentIndex = startIndex
            _currentTrack.value = tracks[startIndex]
            _durationMs.value = tracks[startIndex].durationMs
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
     * Добавить трек в очередь и сразу воспроизвести.
     * Сохраняет существующую очередь, вставляет трек после текущего.
     * Автоматически подбирает похожие треки по жанру.
     */
    fun playNext(track: Track, context: Context) {
        if (queue.isEmpty()) {
            setQueue(listOf(track), 0)
            playTrack(context, 0)
            // Auto-populate with similar genre tracks
            scope.launch {
                populateSimilarTracks(track, context)
            }
            return
        }
        val insertIndex = (currentIndex + 1).coerceAtMost(queue.size)
        val newQueue = queue.toMutableList()
        newQueue.add(insertIndex, track)
        queue = newQueue
        _queue.value = queue
        playTrack(context, insertIndex)
        // Auto-populate with similar genre tracks
        scope.launch {
            populateSimilarTracks(track, context)
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
                    .setAlbumArtist(track.artist)
                    .setArtworkUri(track.displayArtUri)
                    .build()
            )
            .build()
    }

    fun setAutoMix(enabled: Boolean) {
        _autoMixEnabled.value = enabled
        AudioService.setAutoMixEnabled(enabled)
    }

    /**
     * Воспроизвести трек из очереди по индексу.
     * Docs: "URL из поля url ответа /track подставляй прямо в плеер."
     * Загружаем всю очередь в ExoPlayer как MediaItem list, с резолвленными URL.
     * Текущий трек резолвим сразу, остальные — с placeholder URI (резолвим lazy).
     */
    fun playTrack(context: Context, index: Int) {
        if (index !in queue.indices) return

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
            // Docs: "URL из поля url ответа /track подставляй прямо в плеер."
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
            // Current track gets resolved URL, others get placeholder (will be resolved on transition)
            val mediaItems = queue.mapIndexed { i, t ->
                val uri = if (i == index) {
                    currentUri
                } else {
                    // For upcoming tracks: use cached URL if available, else placeholder
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
     * Get cached stream URL or resolve from API.
     * Docs: "file_id — cache for a day+ by (trackId + region + quality)"
     * We cache resolved URLs for ~8 minutes (they expire at ~10 min).
     */
    private suspend fun getCachedOrResolveStreamUrl(trackId: String): android.net.Uri? {
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
                val url = com.liquidmusicglass.api.icm.IcmRepository.getStreamUrl(trackId)
                if (url != null) {
                    val uri = android.net.Uri.parse(url)
                    streamUrlCache[trackId] = uri
                    streamUrlCacheExpiry[trackId] = now + STREAM_CACHE_TTL_MS
                    android.util.Log.d("PlayerController", "Stream URL resolved for $trackId: ${url.take(60)}...")
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

    /**
     * Resolve stream URL for upcoming track (preload).
     */
    private suspend fun preloadStreamUrl(trackId: String): android.net.Uri? {
        return getCachedOrResolveStreamUrl(trackId)
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

    // ── Favorites ──
    fun toggleFavorite(trackId: String) {
        val current = _favoriteIds.value.toMutableSet()
        if (current.contains(trackId)) current.remove(trackId)
        else current.add(trackId)
        _favoriteIds.value = current
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

    fun preloadNextTrack() {
        preloadJob?.cancel()
        if (queue.isEmpty()) return
        val nextIdx = if (_shuffleEnabled.value) {
            (queue.indices).random()
        } else {
            (currentIndex + 1).coerceAtMost(queue.lastIndex)
        }
        if (nextIdx !in queue.indices || nextIdx == currentIndex) return
        val nextTrack = queue[nextIdx]
        if (!nextTrack.isOnlineTrack) return

        preloadJob = scope.launch {
            try {
                preloadStreamUrl(nextTrack.id)
            } catch (_: Exception) {}
        }
    }

    // ── Current queue access ──
    fun getCurrentIndex(): Int = currentIndex

    private var prefetchJob: Job? = null
    private const val PREFETCH_THRESHOLD_MS = 90_000L // 90 seconds before end

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
    private fun detectGenre(track: Track): String? {
        val text = "${track.title} ${track.artist}".lowercase()
        for ((keyword, genre) in genreKeywords) {
            if (keyword in text) return genre
        }
        return null
    }

    /**
     * Auto-populate queue with similar genre tracks.
     */
    private suspend fun populateSimilarTracks(track: Track, context: Context) {
        val genre = detectGenre(track) ?: return
        try {
            val api = com.liquidmusicglass.api.icm.IcmApi.getInstance()
            val result = api.search(query = genre)
            result.onSuccess { response ->
                val similarTracks = response.items
                    .filter { it.id != track.id && it.isTrack }
                    .shuffled()
                    .take(5)
                    .map { item ->
                        Track(
                            id = item.id,
                            title = item.title,
                            artist = item.displayArtist,
                            albumName = item.album ?: "",
                            uri = android.net.Uri.parse("https://byicloud.online/track/${item.id}"),
                            durationMs = 0L,
                            albumId = item.collectionId?.toLongOrNull() ?: 0L,
                            coverUrl = item.cover
                        )
                    }
                if (similarTracks.isNotEmpty()) {
                    val newQueue = queue.toMutableList()
                    val insertIdx = (currentIndex + 1).coerceAtMost(newQueue.size)
                    newQueue.addAll(insertIdx, similarTracks)
                    queue = newQueue
                    _queue.value = queue
                }
            }
        } catch (_: Exception) {}
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

                    // Prefetch next track when 90 seconds remaining
                    val remaining = duration - playerController.currentPosition
                    if (remaining in 1..PREFETCH_THRESHOLD_MS && prefetchJob?.isActive != true) {
                        preloadNextTrack()
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
     * Called automatically on skipNext/skipPrevious/playTrack.
     */
    private fun logWavePlaybackOnSwitch() {
        val trackId = waveTrackId ?: return
        val startTime = waveTrackStartTimeMs
        if (startTime <= 0) return

        val playedMs = waveTrackPlayedMs + (System.currentTimeMillis() - startTime)
        val playedSeconds = playedMs / 1000.0
        val totalSeconds = (_currentTrack.value?.durationMs ?: 0L) / 1000.0

        if (playedSeconds < 3.0) return // Skip very short plays

        val completed = if (totalSeconds > 0) playedSeconds >= totalSeconds * 0.85 else null
        val skipped = if (totalSeconds > 0) playedSeconds < totalSeconds * 0.15 else null

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
