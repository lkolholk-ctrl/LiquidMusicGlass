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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * PlayerController — единая точка управления воспроизведением.
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

    // ── Endless Playback (AutoMix) ──
    private val endlessEngine = EndlessPlaybackEngine(
        scope = ioScope,
        getController = { controller },
        getCompanionPlayer = { null }
    )

    // ── Stream URL cache ──
    private val streamUrlCache = mutableMapOf<String, CachedStreamUrl>()
    private const val STREAM_CACHE_TTL_MS = 10 * 60 * 1000L

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

    private var lastPlayerPositionMs: Long = 0L
    private var lastSyncTimeMs: Long = 0L
    private var lastIsPlaying: Boolean = false

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

    private val _themeMode = MutableStateFlow(0)
    val themeMode: StateFlow<Int> = _themeMode
    fun setThemeMode(mode: Int) { _themeMode.value = mode }

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume
    fun setVolume(value: Float) { _volume.value = value.coerceIn(0f, 1f) }

    private val _autoMixEnabled = MutableStateFlow(false)
    val autoMixEnabled: StateFlow<Boolean> = _autoMixEnabled
    fun setAutoMix(enabled: Boolean) { _autoMixEnabled.value = enabled }

    private val _isMixing = MutableStateFlow(false)
    val isMixing: StateFlow<Boolean> = _isMixing
    fun setMixing(mixing: Boolean) { _isMixing.value = mixing }

    private var autoMixEngine: ServiceBackedAutoMixEngine? = null

    fun setAutoMixEngine(engine: ServiceBackedAutoMixEngine?) {
        autoMixEngine = engine
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ═══════════════════════════════════════════════════════════
    //  Playback Control
    // ═══════════════════════════════════════════════════════════

    fun playTrack(context: Context, index: Int) {
        if (index !in queue.indices) return

        ioScope.launch {
            val track = queue[index]

            withContext(Dispatchers.Main) {
                currentIndex = index
                _currentTrack.value = track
                _durationMs.value = track.durationMs
                _currentPositionMs.value = 0L
                _isBuffering.value = true
            }

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
                            val currentMediaItem = buildMediaItem(track, streamResult.uri)
                            player.setMediaItem(currentMediaItem)
                            player.prepare()
                            player.play()
                            resetPlaybackLogging(track.durationMs)

                            // Запускаем предзагрузку оконных треков
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
     * ИСПРАВЛЕНО: Теперь заменяет невалидные Uri-плейсхолдеры в очереди на реальные ссылки.
     */
    private fun prefetchAhead(context: Context, currentIndex: Int, depth: Int = 3) {
        if (queue.isEmpty()) return

        val endIndex = (currentIndex + 1 + depth).coerceAtMost(queue.size)
        val indicesToPrefetch = (currentIndex + 1 until endIndex)

        ioScope.launch {
            val resolved = indicesToPrefetch.map { idx ->
                val track = queue[idx]
                val result = if (track.isOnlineTrack) {
                    resolveStreamUrl(track.id)
                } else {
                    StreamResult.Success(track.uri)
                }
                idx to result
            }

            withContext(Dispatchers.Main) {
                val player = getPlayer(context) ?: return@withContext
                var updatedCount = 0

                for ((idx, result) in resolved) {
                    if (result is StreamResult.Success) {
                        val track = queue[idx]
                        val mediaItem = buildMediaItem(track, result.uri)
                        
                        // КРИТИЧЕСКИЙ ФИКС: Если индекс существует в ExoPlayer — заменяем его, а не аппендим в хвост!
                        if (idx < player.mediaItemCount) {
                            player.replaceMediaItem(idx, mediaItem)
                        } else {
                            player.addMediaItem(mediaItem)
                        }
                        updatedCount++
                    }
                }

                if (updatedCount > 0) {
                    android.util.Log.d(
                        "PlayerController",
                        "Pre-fetched and synchronized $updatedCount tracks in ExoPlayer queue."
                    )
                }
            }
        }
    }

    fun addTracksToQueue(newTracks: List<Track>) {
        if (newTracks.isEmpty()) return
        mainScope.launch {
            val oldSize = queue.size
            queue = queue + newTracks
            _queueFlow.value = queue
            
            withContext(Dispatchers.Main) {
                val player = controller ?: appContext?.let { getPlayer(it) }
                player?.let { p ->
                    val mediaItems = newTracks.map { track ->
                        buildMediaItem(track, track.uri)
                    }
                    p.addMediaItems(mediaItems)
                    
                    // Сразу обновляем плейсхолдеры для свежих элементов
                    appContext?.let { prefetchAhead(it, currentIndex, depth = 3) }
                }
            }
        }
    }

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

            endlessEngine.reset()
            if (autoRefillType != null) {
                val type = try {
                    EndlessPlaybackEngine.RefillContext.Type.valueOf(autoRefillType.uppercase())
                } catch (e: Exception) {
                    EndlessPlaybackEngine.RefillContext.Type.WAVE
                }
                endlessEngine.setRefillContext(
                    EndlessPlaybackEngine.RefillContext(
                        type = type,
                        id = autoRefillId,
                        name = autoRefillName,
                        seedTrackId = seedTrackId
                    )
                )
            }
            endlessEngine.registerTracks(tracks.map { it.id })

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
                            it.setMediaItems(mediaItems, startIndex, 0L)
                            it.prepare()
                            it.play()
                            resetPlaybackLogging(startTrack.durationMs)
                        }
                    }
                    addToRecent(startTrack)

                    launch {
                        kotlinx.coroutines.delay(3000)
                        endlessEngine.checkAndRefillIfNeeded()
                    }

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
        mainScope.launch {
            val player = getPlayer(context)
            if (player != null && nextIndex > currentIndex && player.mediaItemCount > nextIndex) {
                player.seekToNextMediaItem()
            } else {
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

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
        lastIsPlaying = playing
        if (!playing) _isBuffering.value = false
    }

    // Фикс: Принимаем живой durationMs и обновляем StateFlow для Seekbar и обратного отсчета
    fun updatePosition(positionMs: Long, durationMs: Long) {
        _currentPositionMs.value = positionMs
        lastPlayerPositionMs = positionMs
        lastSyncTimeMs = SystemClock.elapsedRealtime()

        if (durationMs > 0L && _durationMs.value != durationMs) {
            _durationMs.value = durationMs
            _currentTrack.value?.let { track ->
                if (track.durationMs != durationMs) {
                    _currentTrack.value = track.copy(durationMs = durationMs)
                }
            }
        }

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
            
            // ИСПРАВЛЕНО: Сдвигаем окно предзагрузки вперед, выпрямляя ссылки
            appContext?.let { prefetchAhead(it, index, depth = 3) }

            ioScope.launch {
                endlessEngine.checkAndRefillIfNeeded()
            }
        }
    }

    fun onTrackEnded() {
        logPlayback(completed = true, skipped = false)
        val nextIndex = currentIndex + 1
        if (nextIndex < queue.size) {
            appContext?.let { ctx ->
                mainScope.launch {
                    val player = getPlayer(ctx)
                    if (player != null && nextIndex > currentIndex && player.mediaItemCount > nextIndex) {
                        player.seekToNextMediaItem()
                        player.play()
                    } else {
                        playTrack(ctx, nextIndex)
                    }
                }
            }
        }
    }

    fun onPlaybackError(errorCodeName: String) {
        android.util.Log.e("PlayerController", "Playback error: $errorCodeName")
        _isBuffering.value = false
        _isPlaying.value = false
    }

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

    fun setAutoRefillContext(type: String, id: String, name: String, seedTrackId: String? = null) {}
    fun clearAutoRefillContext() {}
    fun playNext(track: Track, context: Context) { addToQueue(track) }

    // ═══════════════════════════════════════════════════════════
    //  Stream URL Resolution & External Bridge Fixes
    // ═══════════════════════════════════════════════════════════

    /**
     * ДОБАВЛЕНО: Публичный мост для AutoMixEngine, чтобы вторичный плеер не падал на заглушках.
     */
    suspend fun getValidStreamUri(trackId: String): Uri? {
        return when (val result = resolveStreamUrl(trackId)) {
            is StreamResult.Success -> result.uri
            else -> null
        }
    }

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

        if (cached != null && now < cached.expiresAtMs) {
            return StreamResult.Success(cached.uri)
        }

        return try {
            withTimeout(15_000) {
                val quality = getEffectiveQuality(trackId)
                val trackInfo = IcmRepository.getTrackInfo(trackId, quality = quality)

                if (trackInfo != null) {
                    cacheAndReturn(trackId, trackInfo)
                } else {
                    val error = IcmRepository.lastError.value
                    when {
                        error?.contains("region_unavailable") == true ||
                        error?.contains("451") == true -> {
                            val apiException = IcmRepository.lastApiException.value
                            val requiredRegion = apiException?.requiredRegion
                            
                            if (requiredRegion != null) {
                                val retryTrackInfo = IcmRepository.getTrackInfo(trackId, quality = quality, region = requiredRegion)
                                if (retryTrackInfo != null) {
                                    cacheAndReturn(trackId, retryTrackInfo)
                                } else {
                                    StreamResult.Error("region_unavailable", "Failed after region switch")
                                }
                            } else {
                                StreamResult.Error("region_unavailable", error)
                            }
                        }
                        error?.contains("source_not_allowed") == true || error?.contains("403") == true -> {
                            StreamResult.Error("source_not_allowed", error)
                        }
                        error?.contains("track_not_found") == true || error?.contains("404") == true -> {
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
        return StreamResult.Success(uri)
    }

    fun handleExpiredUrl(context: Context, trackId: String) {
        ioScope.launch {
            streamUrlCache.remove(trackId)
            val result = resolveStreamUrl(trackId)
            if (result is StreamResult.Success) {
                withContext(Dispatchers.Main) {
                    val player = getPlayer(context) ?: return@withContext
                    val currentMediaItem = player.currentMediaItem
                    if (currentMediaItem?.mediaId == trackId) {
                        val newItem = buildMediaItem(queue[currentIndex], result.uri)
                        player.replaceMediaItem(currentIndex, newItem)
                        player.prepare()
                        player.play()
                    }
                }
            }
        }
    }

    private fun getEffectiveQuality(trackId: String): String? {
        val allowed = com.liquidmusicglass.api.icm.IcmAuthRepository.allowedQualities.value
        val desired = com.liquidmusicglass.api.icm.IcmApi.getInstance().streamQuality ?: "256K"

        if (allowed.isNotEmpty() && !allowed.contains(desired)) {
            return if (allowed.contains("256K")) "256K" else allowed.firstOrNull() ?: "128K"
        }

        val hasPremium = com.liquidmusicglass.api.icm.IcmAuthRepository.isPremium.value
        if (!hasPremium && trackId.startsWith("secondary_")) {
            if (desired == "ALAC" || desired == "320K") return "256K"
        }
        return desired
    }

    // ═══════════════════════════════════════════════════════════
    //  Playback Logging
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

        val isCompleted = completed || (playedSec >= 0.85f * durationSec)
        val isSkipped = !isCompleted && (skipped || (playedSec < 0.15f * durationSec))

        val isWaveTrack = track.id.startsWith("wave_") ||
                (queue.isNotEmpty() && currentIndex >= 0 &&
                        queue.getOrNull(currentIndex)?.let { it.id == track.id } != null &&
                        autoMixEnabled.value)

        if (!isWaveTrack) return

        ioScope.launch {
            try {
                IcmRepository.logWavePlayback(
                    trackId = track.id,
                    playedSeconds = playedSec.toDouble(),
                    totalSeconds = durationSec.toDouble(),
                    completed = isCompleted,
                    skipped = isSkipped
                )
            } catch (_: Exception) {}
        }
    }

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

    fun setFavoriteIds(ids: Set<String>) { _favoriteIds.value = ids }
    fun isFavorite(trackId: String): Boolean = _favoriteIds.value.contains(trackId)

    private fun addToRecent(track: Track) {
        val current = _recentlyPlayed.value.toMutableList()
        current.removeAll { it.id == track.id }
        current.add(0, track)
        _recentlyPlayed.value = current.take(50)
    }

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
