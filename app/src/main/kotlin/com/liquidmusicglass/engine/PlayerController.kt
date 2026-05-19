package com.liquidmusicglass.engine

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.IcmTrackResponse
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
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * PlayerController — единая точка управления воспроизведением.
 *
 * Архитектура (строго по ICM API docs + Media3):
 *   1. Все сетевые операции — в ioScope (Dispatchers.IO)
 *   2. Все UI StateFlow обновления — с Main dispatcher
 *   3. ExoPlayer API (prepare, play, pause) — только с Main dispatcher
 *   4. Stream URL lifecycle: POST /track → cache → play → expired → re-POST
 *   5. Playback logging: POST /library/wave/playback on skip/end
 */
object PlayerController {

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var appContext: Context? = null
    private var controller: MediaController? = null
    private var isConnectingController = false
    private var mediaControllerUnavailable = false

    // ── Queue ──
    private var queue = listOf<Track>()
    private var currentIndex = -1

    // ── Stream URL cache (ICM API: file_id cacheable 24h) ──
    private val streamUrlCache = mutableMapOf<String, CachedStreamUrl>()
    private const val STREAM_CACHE_TTL_MS = 10 * 60 * 1000L  // ~600s per docs, use 10min safe

    // ── Playback logging state ──
    private var playbackStartTimeMs: Long = 0L
    private var totalPlayedMs: Long = 0L
    private var lastPositionMs: Long = 0L

    // ── StateFlow (UI observes these) ──
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs

    // ── Smooth time interpolation for 60/120 FPS lyrics ──
    private var lastPlayerPositionMs: Long = 0L
    private var lastSyncTimeMs: Long = 0L
    private var lastIsPlaying: Boolean = false

    /**
     * Returns smoothly interpolated position for butter-smooth lyrics animation.
     * Call this from UI frame callbacks (withFrameMillis) at display refresh rate.
     */
    fun getSmoothPositionMs(): Long {
        if (!lastIsPlaying) return lastPlayerPositionMs
        val elapsed = SystemClock.elapsedRealtime() - lastSyncTimeMs
        return lastPlayerPositionMs + elapsed
    }

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _queueFlow = MutableStateFlow<List<Track>>(emptyList())
    val queueFlow: StateFlow<List<Track>> = _queueFlow

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled

    private val _repeatMode = MutableStateFlow(0)
    val repeatMode: StateFlow<Int> = _repeatMode

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds

    private val _recentlyPlayed = MutableStateFlow<List<Track>>(emptyList())
    val recentlyPlayed: StateFlow<List<Track>> = _recentlyPlayed

    // Legacy theme mode stub (moved to AppSettings)
    private val _themeMode = MutableStateFlow(0)
    val themeMode: StateFlow<Int> = _themeMode
    fun setThemeMode(mode: Int) { _themeMode.value = mode }

    // Legacy stubs (AudioEffectsEngine / AutoMix removed)
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume
    fun setVolume(value: Float) { _volume.value = value.coerceIn(0f, 1f) }

    private val _autoMixEnabled = MutableStateFlow(false)
    val autoMixEnabled: StateFlow<Boolean> = _autoMixEnabled
    fun setAutoMix(enabled: Boolean) { _autoMixEnabled.value = enabled }

    private val _isMixing = MutableStateFlow(false)
    val isMixing: StateFlow<Boolean> = _isMixing
    fun setMixing(mixing: Boolean) { _isMixing.value = mixing }

    // ── AutoMix Engine reference (set by AudioService) ──
    private var autoMixEngine: ServiceBackedAutoMixEngine? = null

    fun setAutoMixEngine(engine: ServiceBackedAutoMixEngine?) {
        autoMixEngine = engine
    }

    // ── Init ──
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ═══════════════════════════════════════════════════════════
    //  Playback Control
    // ═══════════════════════════════════════════════════════════

    /**
     * Play track by index in current queue.
     *
     * Gapless playback architecture:
     * 1. Устанавливаем текущий трек через setMediaItem (очищает очередь)
     * 2. Сразу после prepare() асинхронно добавляем следующий трек через addMediaItem()
     * 3. ExoPlayer через CacheDataSource начинает буферить следующий трек в фоне
     * 4. При переходе к следующему треку — seamless, без затыков
     */
    fun playTrack(context: Context, index: Int) {
        if (index !in queue.indices) return

        ioScope.launch {
            val track = queue[index]

            // Update UI state immediately
            withContext(Dispatchers.Main) {
                currentIndex = index
                _currentTrack.value = track
                _durationMs.value = track.durationMs
                _currentPositionMs.value = 0L
                _isBuffering.value = true
            }

            // Resolve stream URL on IO
            val streamResult = if (track.isOnlineTrack) {
                resolveStreamUrl(track.id)
            } else {
                StreamResult.Success(track.uri)
            }

            when (streamResult) {
                is StreamResult.Success -> {
                    withContext(Dispatchers.Main) {
                        val player = getPlayer(context)
                        if (player != null) {
                            // Set current track (clears queue)
                            val currentMediaItem = buildMediaItem(track, streamResult.uri)
                            player.setMediaItem(currentMediaItem)
                            player.prepare()
                            player.play()
                            resetPlaybackLogging(track.durationMs)

                            // Pre-fetch 3-5 tracks ahead per ICM API spec
                            prefetchAhead(context, index, depth = 3)
                        } else {
                            android.util.Log.e("PlayerController", "No player available")
                            _isBuffering.value = false
                        }
                    }
                    addToRecent(track)
                }
                is StreamResult.Error -> {
                    android.util.Log.e("PlayerController", "Stream error for ${track.id}: ${streamResult.code}")
                    withContext(Dispatchers.Main) {
                        _isBuffering.value = false
                    }
                }
            }
        }
    }

    /**
     * ICM API: Предзагружай 3-5 треков вперёд для бесшовного воспроизведения.
     *
     * Архитектура предзагрузки (строго по ICM API спецификации):
     * 1. file_id кешируется 24ч+ — повторный POST /track с тем же trackId
     *    возвращает мгновенный ответ (кеш сработает).
     * 2. signed URL действует ~600 сек — предзагруженные треки должны
     *    начать играть в пределах этого окна.
     * 3. Холодные треки (первый запрос): 3-10 сек подготовки.
     *    Используем ?async=1 для вторичного каталога.
     * 4. Предзагружаем 3-5 треков вперёд от currentIndex.
     *
     * @param context Context
     * @param currentIndex индекс текущего играющего трека
     * @param depth количество треков для предзагрузки (default: 3)
     */
    private fun prefetchAhead(context: Context, currentIndex: Int, depth: Int = 3) {
        if (queue.isEmpty()) return

        val endIndex = (currentIndex + 1 + depth).coerceAtMost(queue.size)
        val indicesToPrefetch = (currentIndex + 1 until endIndex)

        ioScope.launch {
            // Сначала резолвим все URL параллельно (file_id кеш 24ч+)
            val resolved = indicesToPrefetch.map { idx ->
                val track = queue[idx]
                val result = if (track.isOnlineTrack) {
                    resolveStreamUrl(track.id)
                } else {
                    StreamResult.Success(track.uri)
                }
                idx to result
            }

            // Затем добавляем в плеер на Main dispatcher
            withContext(Dispatchers.Main) {
                val player = getPlayer(context) ?: return@withContext
                var addedCount = 0

                for ((idx, result) in resolved) {
                    if (result is StreamResult.Success) {
                        val track = queue[idx]
                        val mediaItem = buildMediaItem(track, result.uri)
                        player.addMediaItem(mediaItem)
                        addedCount++
                    }
                }

                if (addedCount > 0) {
                    android.util.Log.d(
                        "PlayerController",
                        "Pre-fetched $addedCount tracks ahead (indices ${currentIndex + 1}..${endIndex - 1})"
                    )
                }
            }
        }
    }

    /**
     * Play a list of tracks from specified start index.
     * Legacy parameters (autoRefill*, seedTrackId) kept for API compatibility — no-op.
     */
    fun playFromList(
        context: Context,
        tracks: List<Track>,
        startIndex: Int = 0,
        autoRefillType: String? = null,
        autoRefillId: String? = null,
        autoRefillName: String? = null,
        seedTrackId: String? = null
    ) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return

        ioScope.launch {
            val startTrack = tracks[startIndex]

            // Resolve start track URL
            val streamResult = if (startTrack.isOnlineTrack) {
                resolveStreamUrl(startTrack.id)
            } else {
                StreamResult.Success(startTrack.uri)
            }

            when (streamResult) {
                is StreamResult.Success -> {
                    val mediaItems = tracks.mapIndexed { i, track ->
                        val uri = if (i == startIndex) streamResult.uri else track.uri
                        buildMediaItem(track, uri)
                    }

                    withContext(Dispatchers.Main) {
                        queue = tracks
                        _queueFlow.value = tracks
                        currentIndex = startIndex
                        _currentTrack.value = startTrack
                        _durationMs.value = startTrack.durationMs
                        _currentPositionMs.value = 0L
                        _isBuffering.value = true

                        val player = getPlayer(context)
                        player?.let {
                            // Текущий трек + предзагружаем 3-5 следующих
                            val currentItem = buildMediaItem(startTrack, streamResult.uri)
                            it.setMediaItem(currentItem)
                            it.prepare()
                            it.play()
                            resetPlaybackLogging(startTrack.durationMs)
                        }
                    }
                    addToRecent(startTrack)

                    // ICM API: Предзагружай 3-5 треков вперёд для бесшовного воспроизведения
                    prefetchAhead(context, startIndex, depth = 3)
                }
                is StreamResult.Error -> {
                    android.util.Log.e("PlayerController", "Stream error for ${startTrack.id}: ${streamResult.code}")
                    withContext(Dispatchers.Main) { _isBuffering.value = false }
                }
            }
        }
    }

    fun togglePlayPause(context: Context) {
        mainScope.launch {
            val player = getPlayer(context) ?: return@launch
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.mediaItemCount == 0 && queue.isNotEmpty()) {
                    playTrack(context, if (currentIndex >= 0) currentIndex else 0)
                } else {
                    player.play()
                }
            }
        }
    }

    fun skipNext(context: Context) {
        logPlayback(completed = false, skipped = true)
        autoMixEngine?.onManualNavigation()
        if (queue.isEmpty()) return
        val nextIndex = if (currentIndex + 1 < queue.size) currentIndex + 1 else 0
        // Gapless: используем seekToNextMediaItem вместо playTrack()
        // Это сохраняет очередь и не сбрасывает предзагруженные треки
        mainScope.launch {
            val player = getPlayer(context)
            if (player != null && nextIndex > currentIndex && player.mediaItemCount > nextIndex) {
                // ExoPlayer уже имеет трек в очереди — просто переходим
                player.seekToNextMediaItem()
            } else {
                // Очередь пуста или трек не предзагружен — запускаем с нуля
                playTrack(context, nextIndex)
            }
        }
    }

    fun skipPrevious(context: Context) {
        mainScope.launch {
            val player = getPlayer(context)
            if (player != null && player.currentPosition > 3000L) {
                player.seekTo(0L)
                _currentPositionMs.value = 0L
                return@launch
            }
            logPlayback(completed = false, skipped = true)
            autoMixEngine?.onManualNavigation()
            if (queue.isEmpty()) return@launch
            val prevIndex = if (currentIndex > 0) currentIndex - 1 else queue.lastIndex
            // Gapless: используем seekToPreviousMediaItem если возможно
            if (player != null && prevIndex < currentIndex && prevIndex >= 0) {
                player.seekToPreviousMediaItem()
            } else {
                playTrack(context, prevIndex)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val safePosition = positionMs.coerceIn(0L, (_durationMs.value - 500L).coerceAtLeast(0L))
        mainScope.launch {
            getPlayer(appContext ?: return@launch)?.seekTo(safePosition)
            _currentPositionMs.value = safePosition
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  State updates from AudioService
    // ═══════════════════════════════════════════════════════════

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
        lastIsPlaying = playing
        if (!playing) _isBuffering.value = false
    }

    fun updatePosition(positionMs: Long) {
        _currentPositionMs.value = positionMs
        lastPlayerPositionMs = positionMs
        lastSyncTimeMs = SystemClock.elapsedRealtime()
        // Accumulate played time
        if (_isPlaying.value) {
            val delta = positionMs - lastPositionMs
            if (delta > 0) totalPlayedMs += delta
            lastPositionMs = positionMs
        }
    }

    fun onTrackChanged(mediaId: String) {
        val index = queue.indexOfFirst { it.id == mediaId }
        if (index in queue.indices) {
            currentIndex = index
            val track = queue[index]
            _currentTrack.value = track
            _durationMs.value = track.durationMs
            _currentPositionMs.value = 0L
        }
    }

    fun onTrackEnded() {
        logPlayback(completed = true, skipped = false)
        val nextIndex = currentIndex + 1
        if (nextIndex < queue.size) {
            // ExoPlayer автоматически перешёл на следующий трек из очереди (addMediaItem)
            // Но URL мог истечь (>600s). Проверяем и ре-резолвим если нужно.
            currentIndex = nextIndex
            val nextTrack = queue[nextIndex]
            _currentTrack.value = nextTrack
            _durationMs.value = nextTrack.durationMs
            _currentPositionMs.value = 0L
            resetPlaybackLogging(nextTrack.durationMs)

            // Предзагружаем следующие 3 трека
            appContext?.let { prefetchAhead(it, nextIndex, depth = 3) }
        }
    }

    fun onPlaybackError(errorCodeName: String) {
        android.util.Log.e("PlayerController", "Playback error: $errorCodeName")
        _isBuffering.value = false
        _isPlaying.value = false
    }

    // ═══════════════════════════════════════════════════════════
    //  Queue Management
    // ═══════════════════════════════════════════════════════════

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        queue = tracks
        _queueFlow.value = tracks
        currentIndex = startIndex
    }

    fun getCurrentQueue(): List<Track> = queue
    fun getCurrentIndex(): Int = currentIndex

    fun addToQueue(track: Track) {
        queue = queue + track
        _queueFlow.value = queue
    }

    // ═══════════════════════════════════════════════════════════
    //  Legacy AutoMix stubs (removed — kept for API compatibility)
    // ═══════════════════════════════════════════════════════════

    fun setAutoRefillContext(type: String, id: String, name: String, seedTrackId: String? = null) {
        // AutoMixEngine removed — no-op
    }

    fun clearAutoRefillContext() {
        // AutoMixEngine removed — no-op
    }

    fun playNext(track: Track, context: Context) {
        addToQueue(track)
    }

    // ═══════════════════════════════════════════════════════════
    //  Stream URL Resolution (ICM API compliant)
    // ═══════════════════════════════════════════════════════════

    private sealed class StreamResult {
        data class Success(val uri: Uri) : StreamResult()
        data class Error(val code: String, val message: String?) : StreamResult()
    }

    private data class CachedStreamUrl(
        val uri: Uri,
        val expiresAtMs: Long,
        val fileId: String?
    )

    private suspend fun resolveStreamUrl(trackId: String): StreamResult {
        val now = System.currentTimeMillis()
        val cached = streamUrlCache[trackId]

        // Check cache first
        if (cached != null && now < cached.expiresAtMs) {
            android.util.Log.d("PlayerController", "Cache hit for $trackId")
            return StreamResult.Success(cached.uri)
        }

        return try {
            withTimeout(15_000) {
                val quality = getEffectiveQuality(trackId)
                val trackInfo = IcmRepository.getTrackInfo(trackId, quality = quality)

                if (trackInfo != null) {
                    cacheAndReturn(trackId, trackInfo)
                } else {
                    // Check if error is region-related (451)
                    val error = IcmRepository.lastError.value
                    when {
                        error?.contains("region_unavailable") == true ||
                        error?.contains("451") == true -> {
                            // Retry with required_region if available
                            // For now, return error
                            StreamResult.Error("region_unavailable", error)
                        }
                        error?.contains("source_not_allowed") == true ||
                        error?.contains("403") == true -> {
                            StreamResult.Error("source_not_allowed", error)
                        }
                        error?.contains("track_not_found") == true ||
                        error?.contains("404") == true -> {
                            StreamResult.Error("track_not_found", error)
                        }
                        error?.contains("early_access") == true -> {
                            StreamResult.Error("early_access", error)
                        }
                        else -> StreamResult.Error("unknown", error)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerController", "URL resolve failed: ${e.message}")
            StreamResult.Error("network_error", e.message)
        }
    }

    private fun cacheAndReturn(trackId: String, trackInfo: IcmTrackResponse): StreamResult {
        val uri = Uri.parse(trackInfo.url)
        val now = System.currentTimeMillis()

        // Calculate TTL from expires_at (seconds → ms)
        val ttl = if (trackInfo.expiresAt > 0) {
            (trackInfo.expiresAt * 1000L - now - 60_000L).coerceAtLeast(60_000L)
        } else {
            STREAM_CACHE_TTL_MS
        }

        streamUrlCache[trackId] = CachedStreamUrl(
            uri = uri,
            expiresAtMs = now + ttl,
            fileId = trackInfo.fileId
        )

        android.util.Log.d("PlayerController", "Cached URL for $trackId, expires in ${ttl / 1000}s")
        return StreamResult.Success(uri)
    }

    /**
     * Handle expired URL during playback — re-resolve and retry.
     * Called when player gets 403 invalid_or_expired_signature.
     */
    fun handleExpiredUrl(context: Context, trackId: String) {
        ioScope.launch {
            // Clear cache for this track
            streamUrlCache.remove(trackId)
            android.util.Log.d("PlayerController", "Cleared expired cache for $trackId, re-resolving...")

            val result = resolveStreamUrl(trackId)
            when (result) {
                is StreamResult.Success -> {
                    withContext(Dispatchers.Main) {
                        val player = getPlayer(context) ?: return@withContext
                        val currentMediaItem = player.currentMediaItem
                        if (currentMediaItem?.mediaId == trackId) {
                            // Replace current item with fresh URL
                            val newItem = buildMediaItem(queue[currentIndex], result.uri)
                            player.replaceMediaItem(currentIndex, newItem)
                            player.prepare()
                            player.play()
                        }
                    }
                }
                is StreamResult.Error -> {
                    android.util.Log.e("PlayerController", "Re-resolve failed: ${result.code}")
                }
            }
        }
    }

    private fun getEffectiveQuality(trackId: String): String? {
        val hasPremium = com.liquidmusicglass.api.icm.IcmAuthRepository.isPremium.value
        val desired = com.liquidmusicglass.api.icm.IcmApi.getInstance().streamQuality ?: "256K"

        // ICM API docs: secondary catalog limited to 256K without premium
        if (!hasPremium && trackId.startsWith("secondary_")) {
            if (desired == "ALAC" || desired == "320K") return "256K"
        }
        return desired
    }

    // ═══════════════════════════════════════════════════════════
    //  Playback Logging (POST /library/wave/playback)
    // ═══════════════════════════════════════════════════════════

    private fun resetPlaybackLogging(durationMs: Long) {
        playbackStartTimeMs = System.currentTimeMillis()
        totalPlayedMs = 0L
        lastPositionMs = 0L
    }

    private fun logPlayback(completed: Boolean, skipped: Boolean) {
        val track = _currentTrack.value ?: return
        val durationSec = track.durationMs / 1000f
        val playedSec = totalPlayedMs / 1000f

        // Auto-calculate completed/skipped per ICM API docs:
        // completed: played >= 0.85 * total
        // skipped: played < 0.15 * total
        val isCompleted = completed || (playedSec >= 0.85f * durationSec)
        val isSkipped = skipped || (playedSec < 0.15f * durationSec)

        // POST /library/wave/playback — только для треков из Wave (радио)
        // Обычные треки (альбомы, поиск, плейлисты) — не логируем
        val isWaveTrack = track.id.startsWith("wave_") ||
                (queue.isNotEmpty() && currentIndex >= 0 &&
                        queue.getOrNull(currentIndex)?.let { it.id == track.id } != null &&
                        autoMixEnabled.value)

        if (!isWaveTrack) {
            android.util.Log.d("PlayerController", "Skip wave playback log for non-wave track: ${track.id}")
            return
        }

        ioScope.launch {
            try {
                IcmRepository.logWavePlayback(
                    trackId = track.id,
                    playedSeconds = playedSec.toDouble(),
                    totalSeconds = durationSec.toDouble(),
                    completed = isCompleted,
                    skipped = isSkipped
                )
                android.util.Log.d("PlayerController", "Logged wave playback: ${track.id}, played=${playedSec}s, completed=$isCompleted")
            } catch (e: Exception) {
                android.util.Log.e("PlayerController", "Wave playback log failed: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  MediaItem Builders
    // ═══════════════════════════════════════════════════════════

    private fun buildMediaItem(track: Track, uri: Uri = track.uri): MediaItem {
        return MediaItem.Builder()
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
    }

    private fun buildMediaItems(tracks: List<Track>, startIndex: Int, startUri: Uri): List<MediaItem> {
        return tracks.mapIndexed { i, track ->
            val uri = if (i == startIndex) startUri else track.uri
            buildMediaItem(track, uri)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  MediaController / Player Access
    // ═══════════════════════════════════════════════════════════

    private suspend fun getPlayer(context: Context): MediaController? {
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
                withTimeout(6_000) {
                    suspendCancellableCoroutine<MediaController?> { continuation ->
                        val future = MediaController.Builder(
                            context.applicationContext, sessionToken
                        ).buildAsync()
                        future.addListener({
                            try {
                                val result = future.get()
                                if (continuation.isActive) continuation.resume(result)
                            } catch (_: Throwable) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }, MoreExecutors.directExecutor())
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                mediaControllerUnavailable = true
                null
            }
            builtController?.let {
                controller = it
                it.addListener(PlayerStateBridge())
            }
            builtController
        } finally {
            isConnectingController = false
        }
    }

    private class PlayerStateBridge : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Favorites & Playlists
    // ═══════════════════════════════════════════════════════════

    fun toggleFavorite(trackId: String) {
        ioScope.launch {
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

    // ═══════════════════════════════════════════════════════════
    //  Recently Played
    // ═══════════════════════════════════════════════════════════

    private fun addToRecent(track: Track) {
        val current = _recentlyPlayed.value.toMutableList()
        current.removeAll { it.id == track.id }
        current.add(0, track)
        _recentlyPlayed.value = current.take(50)
    }

    // ═══════════════════════════════════════════════════════════
    //  Shuffle / Repeat
    // ═══════════════════════════════════════════════════════════

    fun toggleShuffle() {
        _shuffleEnabled.value = !_shuffleEnabled.value
        mainScope.launch {
            getPlayer(appContext ?: return@launch)?.shuffleModeEnabled = _shuffleEnabled.value
        }
    }

    fun setShuffle(enabled: Boolean) {
        _shuffleEnabled.value = enabled
        mainScope.launch {
            getPlayer(appContext ?: return@launch)?.shuffleModeEnabled = enabled
        }
    }

    fun cycleRepeatMode() {
        val next = (_repeatMode.value + 1) % 3
        _repeatMode.value = next
        mainScope.launch {
            getPlayer(appContext ?: return@launch)?.repeatMode = when (next) {
                1 -> Player.REPEAT_MODE_ALL
                2 -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun setRepeatMode(mode: Int) {
        val clamped = mode.coerceIn(0, 2)
        _repeatMode.value = clamped
        mainScope.launch {
            getPlayer(appContext ?: return@launch)?.repeatMode = when (clamped) {
                1 -> Player.REPEAT_MODE_ALL
                2 -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
}
