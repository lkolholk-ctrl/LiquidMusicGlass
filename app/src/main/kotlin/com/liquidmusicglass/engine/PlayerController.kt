package com.liquidmusicglass.engine

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
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
import kotlin.coroutines.resume

@OptIn(UnstableApi::class)
object PlayerController {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null

    private var controller: MediaController? = null
    private var isConnectingController = false
    private var positionJob: Job? = null

    private var queue = listOf<Track>()
    private var currentIndex = -1

    private const val POSITION_UPDATE_MS = 200L

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
            }
        }
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

        scope.launch {
            obtainController(context)
        }
    }

    private suspend fun obtainController(context: Context): MediaController? {
        controller?.let { return it }

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

            val builtController = suspendCancellableCoroutine<MediaController?> { continuation ->
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
     * Добавить трек в недавно прослушанные.
     */
    private fun addToRecent(track: Track) {
        recentHistory.removeAll { it.id == track.id }
        recentHistory.add(0, track)
        if (recentHistory.size > 30) recentHistory.removeLast()
        _recentlyPlayed.value = recentHistory.toList()
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
        val playerController = controller
        if (playerController != null && playerController.hasNextMediaItem()) {
            playerController.seekToNextMediaItem()
            playerController.play()
            AudioService.companionService?.notifyManualNavigation()
            return
        }

        if (queue.isEmpty()) return
        playTrack(context, 0)
    }

    fun skipPrevious(context: Context) {
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

    // ── Current queue access ──
    fun getCurrentIndex(): Int = currentIndex

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
}