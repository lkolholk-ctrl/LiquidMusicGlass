package com.liquidmusicglass.engine

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.IcmTrackResponse
import com.liquidmusicglass.data.local.WaveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Strict playback scope isolation.
 * - Downloads / Playlist contexts are BOUNDED — the player must NEVER
 *   auto-advance beyond the injected local array or fetch global recommendations.
 * - Only [Global] allows the EndlessPlaybackEngine to refill the queue.
 */
sealed class PlaybackContext {
    object Downloads : PlaybackContext()
    data class Playlist(val id: String) : PlaybackContext()
    data class Album(val id: String) : PlaybackContext()
    data class Artist(val id: String) : PlaybackContext()
    object Global : PlaybackContext()
}

/**
 * PlayerController — единая точка управления воспроизведением.
 */
object PlayerController {

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var appContext: Context? = null
    val context: Context? get() = appContext
    private var controller: MediaController? = null
    private var isConnectingController = false
    private var mediaControllerUnavailable = false

    // ── Queue ──
    private var queue = listOf<Track>()
    private var currentIndex = -1

    // ── Playback Context (isolation gate) ──
    private var _playbackContext: PlaybackContext = PlaybackContext.Global
    val playbackContext: PlaybackContext get() = _playbackContext

    // ── Endless Playback (AutoMix) ──
    private val endlessEngine = EndlessPlaybackEngine(
        scope = ioScope,
        getController = { controller },
        getCompanionPlayer = { null }
    )

    /**
     * Единая точка входа для авто-дозаправки очереди волны. Безопасно дёргать из
     * нескольких триггеров (UI-bridge и service-listener) — EndlessPlaybackEngine
     * дедуплицирует через свой lock + throttle. Только для волны (Global).
     */
    fun ensureWaveRefill() {
        if (_playbackContext !is PlaybackContext.Global) return
        ioScope.launch { endlessEngine.checkAndRefillIfNeeded() }
    }

    /** Public accessor for the endless engine's refill context (mood/genre) */
    val waveRefillContext: kotlinx.coroutines.flow.StateFlow<EndlessPlaybackEngine.RefillContext?>
        get() = endlessEngine.refillContext

    // ── Stream URL cache ──
    private val streamUrlCache = java.util.concurrent.ConcurrentHashMap<String, CachedStreamUrl>()
    private const val STREAM_CACHE_TTL_MS = 10 * 60 * 1000L

    // In-flight резолвы: один и тот же трек резолвится максимум ОДНОЙ корутиной,
    // остальные ждут тот же результат — без дублирующих POST /track (их раньше
    // могло уходить 2-3 на трек: префетч + загрузчик + handleExpiredUrl).
    private val inFlightResolves = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<StreamResult>>()

    fun getValidCachedUri(trackId: String): Uri? {
        val now = System.currentTimeMillis()
        val cached = streamUrlCache[trackId]
        if (cached != null && now < (cached.expiresAtMs - 15_000L)) { // 15-секундный запас
            return cached.uri
        }
        return null
    }

    // ── Playback logging state ──
    private var playbackStartTimeMs: Long = 0L
    private var totalPlayedMs: Long = 0L
    private var lastPositionMs: Long = 0L
    // Индекс трека, для которого уже запущена предзагрузка следующего (раз на трек).
    private var preloadDoneForIndex: Int = -1

    // ── Consecutive Skips ──
    private var _consecutiveSkips = 0
    val consecutiveSkips: Int get() = _consecutiveSkips

    fun resetConsecutiveSkips() {
        _consecutiveSkips = 0
    }

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
        return lastPlayerPositionMs + (elapsed * _playbackSpeed.value).toLong()
    }

    /** Переякорить интерполяцию на текущей сглаженной позиции (play/pause/seek/speed). */
    private fun reanchorSmoothPosition() {
        lastPlayerPositionMs = getSmoothPositionMs()
        lastSyncTimeMs = SystemClock.elapsedRealtime()
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

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    @Volatile
    var audioServiceRef: AudioService? = null

    fun setPlaybackSpeed(speed: Float) {
        // re-anchor at current speed before switching, so position doesn't jump
        reanchorSmoothPosition()
        _playbackSpeed.value = speed.coerceIn(0.5f, 2.0f)
        audioServiceRef?.setPlaybackSpeed(_playbackSpeed.value)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }



    // ═══════════════════════════════════════════════════════════
    //  Playback Control
    // ═══════════════════════════════════════════════════════════

    /**
     * Play a track by its unique ID using the FULL native timeline.
     * NEVER inject a single MediaItem — always seek inside the populated timeline.
     */
    fun playTrackById(context: Context, trackId: String) {
        android.util.Log.d("VOIDPIXEL_MEDIA", "UI requested Playback for Track ID: $trackId")

        val currentQueue = queue
        val queueIndex = currentQueue.indexOfFirst { it.id == trackId }
        if (queueIndex == -1) {
            android.util.Log.e("VOIDPIXEL_MEDIA", "Track ID $trackId NOT FOUND in local queue!")
            return
        }

        ioScope.launch {
            val track = currentQueue.getOrNull(queueIndex) ?: return@launch

            withContext(Dispatchers.Main) {
                currentIndex = queueIndex
                _currentTrack.value = track
                _durationMs.value = track.durationMs
                _currentPositionMs.value = 0L
                _isBuffering.value = true
            }

            // ResolvingDataSource handles URL resolution on demand
            withContext(Dispatchers.Main) {
                val player = getPlayer(context)
                if (player != null) {
                    player.stop()
                    player.clearMediaItems()

                    // ── STRICT ID-MATCHED NAVIGATION ──
                    val targetIndex = (0 until player.mediaItemCount).indexOfFirst {
                        player.getMediaItemAt(it).mediaId == trackId
                    }

                    if (targetIndex != -1) {
                        player.seekTo(targetIndex, 0L)
                        player.prepare()
                        player.play()
                    } else {
                        // Track missing from timeline — rebuild queue
                        val allMediaItems = currentQueue.map { t -> buildMediaItem(t) }
                        player.setMediaItems(allMediaItems, queueIndex, 0L)
                        player.prepare()
                        player.play()
                        audioServiceRef?.setQueue(allMediaItems, queueIndex, 0L)
                    }
                    resetPlaybackLogging(track.durationMs)
                    prefetchAhead(context, queueIndex, depth = 3)
                } else {
                    android.util.Log.e("VOIDPIXEL_MEDIA", "No player available for trackId=$trackId")
                    _isBuffering.value = false
                }
            }
            addToRecent(track)
        }
    }

    fun playTrack(context: Context, index: Int) {
        val currentQueue = queue
        if (index !in currentQueue.indices) {
            android.util.Log.e("VOIDPIXEL_MEDIA", "playTrack called with invalid index=$index, queue size=${currentQueue.size}")
            return
        }
        val trackId = currentQueue[index].id
        playTrackById(context, trackId)
    }

    /**
     * Предзагружает (прогревает кэш) для следующих треков в очереди.
     */
    private fun prefetchAhead(context: Context, currentIndex: Int, depth: Int = 3) {
        val currentQueue = queue.toList()
        if (currentQueue.isEmpty()) return

        val endIndex = (currentIndex + 1 + depth).coerceAtMost(currentQueue.size)
        val indicesToPrefetch = (currentIndex + 1 until endIndex)

        ioScope.launch {
            indicesToPrefetch.forEach { idx ->
                val track = currentQueue.getOrNull(idx) ?: return@forEach
                if (track.isOnlineTrack) {
                    resolveStreamUrl(track.id)
                }
            }
            android.util.Log.d("PlayerController", "Pre-warmed caches for indices $indicesToPrefetch")
        }
    }

    fun addTracksToQueue(newTracks: List<Track>) {
        if (newTracks.isEmpty()) return
        mainScope.launch {
            // Anti-repeat: не добавляем то, что уже есть в очереди (защита от дублей,
            // даже если сервер/refill вернул пересекающийся трек).
            val existingIds = queue.mapTo(HashSet()) { it.id }
            val fresh = newTracks.filterNot { it.id in existingIds }
            if (fresh.isEmpty()) return@launch
            queue = queue + fresh
            _queueFlow.value = queue

            withContext(Dispatchers.Main) {
                val player = controller ?: appContext?.let { getPlayer(it) }
                player?.let { p ->
                    val mediaItems = fresh.map { track ->
                        buildMediaItem(track, track.uri)
                    }
                    p.addMediaItems(mediaItems)
                    
                    // Sync service-scoped solid queue reference
                    audioServiceRef?.addToQueue(mediaItems)
                    
                    // Сразу обновляем плейсхолдеры для свежих элементов
                    appContext?.let { prefetchAhead(it, currentIndex, depth = 3) }
                }
            }
        }
    }

    fun addTracksFromService(newTracks: List<Track>, mediaItems: List<MediaItem>) {
        queue = queue + newTracks
        _queueFlow.value = queue
        appContext?.let { prefetchAhead(it, currentIndex, depth = 3) }
        android.util.Log.d("VOIDPIXEL_MEDIA", "Sync queue from service: added ${newTracks.size} tracks, total=${queue.size}")
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
        if (tracks.isEmpty() || startIndex !in tracks.indices) {
            android.util.Log.e("VOIDPIXEL_MEDIA", "playFromList called with empty tracks or invalid startIndex=$startIndex")
            return
        }

        val startTrack = tracks[startIndex]
        android.util.Log.d("VOIDPIXEL_MEDIA", "playFromList: tracks=${tracks.size}, startIndex=$startIndex, startTrackId=${startTrack.id}")

        // ── Determine playback context BEFORE any async work ──
        val newContext = when {
            autoRefillType.equals("library", ignoreCase = true) && autoRefillId.equals("downloads", ignoreCase = true) ->
                PlaybackContext.Downloads
            autoRefillType.equals("playlist", ignoreCase = true) && autoRefillId != null ->
                PlaybackContext.Playlist(autoRefillId)
            autoRefillType.equals("album", ignoreCase = true) && autoRefillId != null ->
                PlaybackContext.Album(autoRefillId)
            autoRefillType.equals("artist", ignoreCase = true) && autoRefillId != null ->
                PlaybackContext.Artist(autoRefillId)
            else -> PlaybackContext.Global
        }

        // ── Check camp capabilities for crossfade ──
        val campManager = com.liquidmusicglass.camp.FeatureAccessManager.getInstance(context)
        val campCaps = campManager.capabilities.value
        if (!campCaps.flags[com.liquidmusicglass.camp.Feature.BACKGROUND_PLAYBACK]!!) {
            // Disable gapless/crossfade for YouTube tracks
            com.liquidmusicglass.engine.AppSettings.setGapless(false)
        }

        ioScope.launch {
            // ── ABSOLUTE QUEUE PURGE: wipe old queue before loading ──
            withContext(Dispatchers.Main) {
                val player = getPlayer(context)
                player?.let {
                    it.stop()
                    it.clearMediaItems()
                }
            }

            _playbackContext = newContext
            android.util.Log.d("VOIDPIXEL_MEDIA", "[CONTEXT_SET] $newContext")

            endlessEngine.reset()
            if (newContext is PlaybackContext.Global && autoRefillType != null) {
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
                endlessEngine.registerTracks(tracks.map { it.id })
            }

            // Resolve stream URL for the starting track upfront (fast — single IOS client)
            // Other tracks in the queue will be resolved on demand by ResolvingDataSource
            val startStreamResult = if (startTrack.isOnlineTrack) {
                resolveStreamUrl(startTrack.id)
            } else {
                StreamResult.Success(startTrack.uri)
            }

            when (startStreamResult) {
                is StreamResult.Success -> {
                    // Set queue BEFORE building MediaItems so resolveStreamUrlSync can find tracks
                    val immutableTracks = tracks.toList()
                    queue = immutableTracks
                    _queueFlow.value = immutableTracks
                    currentIndex = startIndex

                    val mediaItems = tracks.mapIndexed { i, track ->
                        // Start track gets resolved URL, others use trackId (resolved on demand)
                        val uri = if (i == startIndex) startStreamResult.uri else track.uri
                        buildMediaItem(track, uri)
                    }

                    withContext(Dispatchers.Main) {
                        _currentTrack.value = startTrack
                        _durationMs.value = startTrack.durationMs
                        _currentPositionMs.value = 0L
                        _isBuffering.value = true

                        val player = getPlayer(context)
                        player?.let {
                            it.stop()
                            it.clearMediaItems()
                            it.setMediaItems(mediaItems, startIndex, 0L)
                            it.prepare()
                            it.play()
                            resetPlaybackLogging(startTrack.durationMs)
                        }

                        audioServiceRef?.setQueue(mediaItems, startIndex, 0L)
                    }
                    addToRecent(startTrack)

                    if (newContext is PlaybackContext.Global) {
                        launch {
                            kotlinx.coroutines.delay(3000)
                            endlessEngine.checkAndRefillIfNeeded()
                        }
                    }

                    prefetchAhead(context, startIndex, depth = 3)
                }
                is StreamResult.Error -> {
                    android.util.Log.e("PlayerController", "Stream error for ${startTrack.id}: ${startStreamResult.code}")
                    withContext(Dispatchers.Main) {
                        _isBuffering.value = false
                        android.widget.Toast.makeText(context, "Failed to resolve track: ${startStreamResult.message ?: startStreamResult.code}", android.widget.Toast.LENGTH_SHORT).show()
                    }
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
                    val trackId = queue.getOrNull(currentIndex)?.id ?: queue.firstOrNull()?.id
                    if (trackId != null) {
                        playTrackById(context, trackId)
                    }
                } else {
                    player.play()
                }
            }
        }
    }

    fun skipNext(context: Context) {
        val currentQueue = queue
        if (currentQueue.isEmpty()) return
        val nextIndex = if (currentIndex + 1 < currentQueue.size) currentIndex + 1 else 0
        val nextTrackId = currentQueue.getOrNull(nextIndex)?.id ?: return
        mainScope.launch {
            val player = getPlayer(context)
            if (player != null) {
                val targetIndex = (0 until player.mediaItemCount).indexOfFirst {
                    player.getMediaItemAt(it).mediaId == nextTrackId
                }
                if (targetIndex != -1) {
                    player.playWhenReady = true
                    player.seekTo(targetIndex, 0L)
                } else {
                    playTrackById(context, nextTrackId)
                }
            } else {
                playTrackById(context, nextTrackId)
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
            val currentQueue = queue
            if (currentQueue.isEmpty()) return@launch
            val prevIndex = if (currentIndex > 0) currentIndex - 1 else currentQueue.lastIndex
            val prevTrackId = currentQueue.getOrNull(prevIndex)?.id ?: return@launch
            if (player != null) {
                val targetIndex = (0 until player.mediaItemCount).indexOfFirst {
                    player.getMediaItemAt(it).mediaId == prevTrackId
                }
                if (targetIndex != -1) {
                    player.playWhenReady = true
                    player.seekTo(targetIndex, 0L)
                } else {
                    playTrackById(context, prevTrackId)
                }
            } else {
                playTrackById(context, prevTrackId)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val safePosition = positionMs.coerceIn(0L, (_durationMs.value - 500L).coerceAtLeast(0L))
        mainScope.launch {
            getPlayer(appContext ?: return@launch)?.seekTo(safePosition)
            _currentPositionMs.value = safePosition
            lastPositionMs = safePosition
            // re-anchor smooth position to the new seek target
            lastPlayerPositionMs = safePosition
            lastSyncTimeMs = SystemClock.elapsedRealtime()
        }
    }

    fun setPlaying(playing: Boolean) {
        // re-anchor smooth position at the play/pause transition (uses old state)
        reanchorSmoothPosition()
        _isPlaying.value = playing
        lastIsPlaying = playing
        if (!playing && _isBuffering.value) {
            _isBuffering.value = false
        }
    }

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
            if (delta > 0 && delta < 2000L) { // Only log actual playing time, ignore seek jumps
                totalPlayedMs += delta
            }
            lastPositionMs = positionMs

            // ── Предзагрузка следующего трека за настраиваемые N секунд до конца ──
            // Когда до конца остаётся ≤ preloadLeadSeconds — заранее резолвим/прогреваем
            // следующие треки, чтобы переход был без паузы. Один раз на трек.
            val effDur = if (durationMs > 0L) durationMs else _durationMs.value
            if (effDur > 0L && preloadDoneForIndex != currentIndex) {
                val remaining = effDur - positionMs
                val leadMs = AppSettings.preloadLeadSeconds.value * 1000L
                if (remaining in 1..leadMs) {
                    preloadDoneForIndex = currentIndex
                    appContext?.let { prefetchAhead(it, currentIndex, depth = 2) }
                }
            }
        }
    }

    fun onTrackChanged(mediaId: String) {
        val currentQueue = queue
        val index = currentQueue.indexOfFirst { it.id == mediaId }
        if (index == -1) {
            android.util.Log.w("VOIDPIXEL_MEDIA", "[TRACK_CHANGED] mediaId=$mediaId not found in local queue")
            return
        }
        val track = currentQueue[index]
        currentIndex = index
        _currentTrack.value = track
        _durationMs.value = track.durationMs
        _currentPositionMs.value = 0L
        
        resetPlaybackLogging(track.durationMs)
        // Только ближайший следующий — для мгновенного скипа. Более глубокая
        // предзагрузка управляется настройкой «Preload next track» (по таймеру до конца).
        appContext?.let { prefetchAhead(it, index, depth = 1) }
    }

    fun onTrackEnded() {
        val currentQueue = queue
        val nextIndex = currentIndex + 1
        if (nextIndex < currentQueue.size) {
            val nextTrackId = currentQueue.getOrNull(nextIndex)?.id
            android.util.Log.d("VOIDPIXEL_MEDIA", "onTrackEnded: nextIndex=$nextIndex, nextTrackId=$nextTrackId")
        }
    }

    fun onPlaybackError(errorCodeName: String) {
        android.util.Log.e("PlayerController", "Playback error: $errorCodeName")
        _isBuffering.value = false
        _isPlaying.value = false
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        val immutableTracks = tracks.toList()
        queue = immutableTracks
        _queueFlow.value = immutableTracks
        currentIndex = startIndex
        
        // Also register tracks to endlessEngine if context is global
        if (_playbackContext is PlaybackContext.Global) {
            endlessEngine.registerTracks(immutableTracks.map { it.id })
        }
    }

    fun getCurrentQueue(): List<Track> = queue
    fun getCurrentIndex(): Int = currentIndex

    fun addToQueue(track: Track) {
        queue = queue + track
        _queueFlow.value = queue
        mainScope.launch {
            val player = controller ?: appContext?.let { getPlayer(it) }
            player?.addMediaItem(buildMediaItem(track, track.uri))
        }
    }

    fun setAutoRefillContext(type: String, id: String, name: String, seedTrackId: String? = null) {
        val newContext = when {
            type.equals("library", ignoreCase = true) && id.equals("downloads", ignoreCase = true) ->
                PlaybackContext.Downloads
            type.equals("playlist", ignoreCase = true) ->
                PlaybackContext.Playlist(id)
            type.equals("album", ignoreCase = true) ->
                PlaybackContext.Album(id)
            type.equals("artist", ignoreCase = true) ->
                PlaybackContext.Artist(id)
            else -> PlaybackContext.Global
        }
        _playbackContext = newContext
        android.util.Log.d("VOIDPIXEL_MEDIA", "[CONTEXT_SET] setAutoRefillContext: type=$type, id=$id, name=$name, seedTrackId=$seedTrackId -> context=$newContext")

        endlessEngine.reset()
        if (newContext is PlaybackContext.Global) {
            val refillType = try {
                EndlessPlaybackEngine.RefillContext.Type.valueOf(type.uppercase())
            } catch (e: Exception) {
                EndlessPlaybackEngine.RefillContext.Type.WAVE
            }
            endlessEngine.setRefillContext(
                EndlessPlaybackEngine.RefillContext(
                    type = refillType,
                    id = id,
                    name = name,
                    seedTrackId = seedTrackId
                )
            )
            endlessEngine.registerTracks(queue.map { it.id })
        }
    }

    fun clearAutoRefillContext() {
        _playbackContext = PlaybackContext.Global
        endlessEngine.reset()
        android.util.Log.d("VOIDPIXEL_MEDIA", "[CONTEXT_CLEAR] Context cleared, reset to Global")
    }

    fun playNext(track: Track, context: Context) {
        addToQueue(track)
        mainScope.launch {
            val player = controller ?: appContext?.let { getPlayer(it) }
            if (player != null && !player.isPlaying && player.mediaItemCount > 0) {
                playTrackById(context, track.id)
            }
        }
    }

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

    fun resolveStreamUrlSync(trackId: String): Uri? {
        val now = System.currentTimeMillis()
        val cached = streamUrlCache[trackId]

        if (cached != null && now < cached.expiresAtMs) {
            UiLogger.log("[SYNC] Cache hit for $trackId")
            return cached.uri
        }

        return try {
            val quality = getEffectiveQuality(trackId)
            val trackInfo = IcmRepository.getTrackInfoSync(trackId, quality = quality)

            if (trackInfo != null) {
                val uri = Uri.parse(trackInfo.url)
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
                uri
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveStreamUrl(trackId: String): StreamResult {
        val now = System.currentTimeMillis()
        val cached = streamUrlCache[trackId]
        if (cached != null && now < cached.expiresAtMs) {
            return StreamResult.Success(cached.uri)
        }

        // Уже резолвится этот трек — присоединяемся к тому же результату.
        inFlightResolves[trackId]?.let { return it.await() }

        val deferred = ioScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            doResolveStreamUrl(trackId)
        }
        val winner = inFlightResolves.putIfAbsent(trackId, deferred) ?: deferred
        if (winner !== deferred) {
            // Проиграли гонку постановки — ждём чужой (уже запущенный) резолв.
            return winner.await()
        }
        deferred.start()
        return try {
            deferred.await()
        } finally {
            inFlightResolves.remove(trackId, deferred)
        }
    }

    private suspend fun doResolveStreamUrl(trackId: String): StreamResult {
        return try {
            withTimeout(15_000) {
                val quality = getEffectiveQuality(trackId)
                val trackInfo = IcmRepository.getTrackInfo(trackId, quality = quality)

                if (trackInfo != null) {
                    cacheAndReturn(trackId, trackInfo)
                } else {
                    val error = IcmRepository.lastError.value
                    val apiException = IcmRepository.lastApiException.value
                    
                    when {
                        error?.contains("region_unavailable") == true || error?.contains("451") == true -> {
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
                        val currentQueue = queue
                        val track = currentQueue.find { it.id == trackId } ?: return@withContext
                        val currentPosition = player.currentPosition
                        val newItem = buildMediaItem(track, result.uri)
                        val targetIndex = (0 until player.mediaItemCount).indexOfFirst {
                            player.getMediaItemAt(it).mediaId == trackId
                        }
                        if (targetIndex != -1) {
                            player.replaceMediaItem(targetIndex, newItem)
                            player.seekTo(targetIndex, currentPosition)
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }
                }
            }
        }
    }

    private fun getEffectiveQuality(trackId: String): String? {
        val track = queue.find { it.id == trackId }
        return com.liquidmusicglass.api.icm.IcmAuthRepository.getEffectiveQuality(
            trackId = trackId,
            source = track?.source
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  Playback Logging
    // ═══════════════════════════════════════════════════════════

    private fun resetPlaybackLogging(durationMs: Long) {
        playbackStartTimeMs = System.currentTimeMillis()
        totalPlayedMs = 0L
        lastPositionMs = 0L
        preloadDoneForIndex = -1
    }

    private fun logPreviousTrack(track: Track, playedMs: Long) {
        val durationSec = track.durationMs / 1000f
        val playedSec = playedMs / 1000f

        val isCompleted = playedMs >= 30_000L
        val isSkipped = !isCompleted

        // Determine if it was skipped for the recommendation engine (less than 15% played)
        val isSkippedForServer = if (track.durationMs > 0L) playedMs < 0.15f * track.durationMs else isSkipped
        if (isSkippedForServer) {
            _consecutiveSkips++
        } else {
            _consecutiveSkips = 0
        }

        val sourceStr = when (_playbackContext) {
            is PlaybackContext.Downloads -> "downloads"
            is PlaybackContext.Playlist -> "playlist"
            is PlaybackContext.Album -> "album"
            is PlaybackContext.Artist -> "artist"
            is PlaybackContext.Global -> "wave"
        }

        android.util.Log.d("PlayerController", "[LOG_PREVIOUS] track=${track.title} | played=${playedMs}ms | isCompleted=$isCompleted | consecutiveSkips=$_consecutiveSkips | source=$sourceStr")

        appContext?.let { ctx ->
            ioScope.launch {
                try {
                    val repo = WaveRepository.getInstance(ctx)
                    if (isCompleted) {
                        repo.logListening(track, playedMs, sourceStr)
                        repo.logTrackPlayed(track)
                    } else {
                        repo.logTrackSkipped(track)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PlayerController", "Room logging failed for ${track.title}", e)
                }
            }
        }

        // Also log to ICM API wave playback.
        // skipped=true — НЕГАТИВНЫЙ сигнал для волны. Шлём его ТОЛЬКО в контексте волны
        // (Global): пролистывание трека в альбоме/плейлисте — это осознанная навигация,
        // а не «меньше такого», и не должно портить персонализацию. Позитивный сигнал
        // (completed) шлём в любом контексте — дослушанный трек = подтверждение вкуса.
        val isWaveContext = _playbackContext is PlaybackContext.Global
        ioScope.launch {
            try {
                IcmRepository.logWavePlayback(
                    trackId = track.id,
                    playedSeconds = playedSec.toDouble(),
                    totalSeconds = durationSec.toDouble(),
                    completed = if (track.durationMs > 0L) playedMs >= 0.85f * track.durationMs else isCompleted,
                    skipped = isSkippedForServer && isWaveContext
                )
            } catch (_: Exception) {}
        }
    }

    fun logFinalPlayback() {
        val track = _currentTrack.value
        if (track != null && totalPlayedMs > 0L) {
            logPreviousTrack(track, totalPlayedMs)
            resetPlaybackLogging(0L)
            _currentTrack.value = null
        }
    }

    private fun buildMediaItem(track: Track, uri: Uri = track.uri): MediaItem {
        val mediaUri = if (track.isOnlineTrack && uri.scheme != "file") {
            Uri.Builder()
                .scheme(StreamingDataSource.SCHEME_LIQUID)
                .authority("track")
                .appendQueryParameter(StreamingDataSource.PARAM_TRACK_ID, track.id)
                .appendQueryParameter(StreamingDataSource.PARAM_URL, uri.toString())
                .build()
        } else {
            uri
        }

        val metaBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumArtist(track.artist)
            .setArtworkUri(track.displayArtUri)

        if (track.durationMs > 0) {
            metaBuilder.setDurationMs(track.durationMs)
        }

        val item = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(mediaUri)
            .setMediaMetadata(metaBuilder.build())
            .build()

        return item
    }

    private suspend fun getPlayer(context: Context): MediaController? {
        controller?.let { return it }
        if (mediaControllerUnavailable) return null

        try {
            val serviceIntent = android.content.Intent(context.applicationContext, AudioService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(serviceIntent)
            } else {
                context.applicationContext.startService(serviceIntent)
            }
        } catch (_: Exception) {}

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

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            setPlaying(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // ── Unified room logging for previous track ──
            val prevTrack = _currentTrack.value
            if (prevTrack != null) {
                logPreviousTrack(prevTrack, totalPlayedMs)
            }

            if (mediaItem != null) {
                mediaItem.mediaId?.let { mediaId ->
                    android.util.Log.d("VOIDPIXEL_MEDIA", "[BRIDGE_TRANSITION] Transitioned to mediaId=$mediaId, reason=$reason")
                    onTrackChanged(mediaId)

                    // ── Endless refill background monitoring ──
                    val player = controller
                    if (player != null) {
                        val total = player.mediaItemCount
                        val current = player.currentMediaItemIndex
                        val remaining = if (total > 0 && current >= 0) (total - current) else 0
                        if (_playbackContext is PlaybackContext.Global && remaining < 3) {
                            ioScope.launch {
                                endlessEngine.checkAndRefillIfNeeded(remaining)
                            }
                        }
                    }
                }
            } else {
                android.util.Log.d("VOIDPIXEL_MEDIA", "[BRIDGE_TRANSITION] Transitioned to null (playback stopped/ended), reason=$reason")
                _currentTrack.value = null
                _currentPositionMs.value = 0L
                _durationMs.value = 0L
            }
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

    /**
     * «Волна по треку» (станция, как у Яндекса): строит очередь вокруг [seedTrack]
     * через ICM `wave/next?seed_track_id`, ставит сам трек первым и продолжает
     * похожими; авто-рефилл держит ту же станцию по seed.
     */
    fun startTrackWave(context: Context, seedTrack: Track) {
        ioScope.launch {
            val repo = com.liquidmusicglass.data.local.WaveRepository.getInstance(context)
            val station = repo.buildWaveQueue(seedTrackId = seedTrack.id)
            val queue = if (station.isEmpty()) {
                listOf(seedTrack)
            } else {
                buildList {
                    add(seedTrack)
                    addAll(station.filter { it.id != seedTrack.id })
                }
            }
            playFromList(
                context = context,
                tracks = queue,
                startIndex = 0,
                autoRefillType = "WAVE",
                seedTrackId = seedTrack.id
            )
        }
    }

    /**
     * Волна по артисту. API не имеет seed_artist_id, поэтому берём топ-трек артиста как
     * seed и строим вокруг него станцию (≈ радио по артисту, как у Яндекса).
     */
    fun startArtistWave(context: Context, artistId: String, artistName: String? = null) {
        ioScope.launch {
            val seed = try {
                com.liquidmusicglass.api.icm.IcmRepository.getArtistTopTracks(artistId).firstOrNull()
            } catch (_: Exception) {
                null
            }
            if (seed == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Couldn't start artist wave",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            startTrackWave(context, seed)
        }
    }

    fun setFavoriteIds(ids: Set<String>) { _favoriteIds.value = ids }
    fun isFavorite(trackId: String): Boolean = _favoriteIds.value.contains(trackId)

    private fun addToRecent(track: Track) {
        val current = _recentlyPlayed.value.toMutableList()
        current.removeAll { it.id == track.id }
        current.add(0, track)
        _recentlyPlayed.value = current.take(50)

        appContext?.let { ctx ->
            com.liquidmusicglass.data.local.LocalStorage.addToHistory(
                ctx,
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
