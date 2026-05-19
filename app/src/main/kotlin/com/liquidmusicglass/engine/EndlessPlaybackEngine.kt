package com.liquidmusicglass.engine

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Глобальное ядро бесконечного стриминга (Endless Loop Mode).
 *
 * Отвечает за:
 * 1. Постоянный мониторинг очереди ExoPlayer — когда остаётся < 3 треков, инициирует добор.
 * 2. Динамический добор треков через wave/recommendations API с дедупликацией.
 * 3. Агрессивный префетч на 50% экватора трека для ML AutoMix.
 * 4. Атомарную очистку очереди при смене контекста пользователем.
 *
 * Инкапсулировано в PlayerController — UI-экраны просто вызывают playFromList().
 */
@OptIn(UnstableApi::class)
internal class EndlessPlaybackEngine(
    private val scope: CoroutineScope,
    private val getController: () -> androidx.media3.session.MediaController?,
    private val getCompanionPlayer: () -> androidx.media3.exoplayer.ExoPlayer?
) {

    companion object {
        /** Сколько треков должно остаться до конца очереди, чтобы запустить добор. */
        const val REFILL_THRESHOLD = 3

        /** Сколько треков запрашивать за один batch. */
        const val REFILL_BATCH_SIZE = 12

        /** Максимальный размер истории playedIds для дедупликации. */
        const val MAX_PLAYED_HISTORY = 500

        /** Интервал проверки позиции для префетча (мс). */
        const val POSITION_CHECK_INTERVAL_MS = 500L

        /** Порог экватора для агрессивного префетча (0.5 = 50%). */
        const val EQUATOR_THRESHOLD = 0.5

        /** Минимальный интервал между refill-запросами (мс) — защита от спама. */
        const val MIN_REFILL_INTERVAL_MS = 3000L
    }

    // ── State ──

    /** Текущий контекст автодобора (wave/artist/album/search/genre/playlist/library). */
    private val _refillContext = MutableStateFlow<RefillContext?>(null)
    val refillContext: StateFlow<RefillContext?> = _refillContext

    /** История всех ID треков, которые уже играли или находятся в очереди. */
    private val playedIds = ConcurrentHashMap.newKeySet<String>()

    /** Счётчик сгенерированных ID для защиты от дубликатов при быстрых refill. */
    private val pendingRefillIds = ConcurrentHashMap.newKeySet<String>()

    /** Флаг: идёт ли сейчас refill-запрос. */
    private val isRefilling = AtomicBoolean(false)

    /** Timestamp последнего refill-запроса. */
    private val lastRefillTime = AtomicLong(0L)

    /** Job для мониторинга позиции трека (префетч на экваторе). */
    private var equatorPrefetchJob: Job? = null

    /** Job для фонового мониторинга остатка очереди. */
    private var queueMonitorJob: Job? = null

    /** Счётчик успешных refill-циклов (для метрик). */
    private val refillCounter = AtomicInteger(0)

    // ── Refill Context ──

    /**
     * Контекст для автодобора треков.
     * Определяет, откуда брать следующие треки когда очередь заканчивается.
     */
    data class RefillContext(
        val type: Type,
        /** ID артиста, альбома, плейлиста, seed-трека и т.д. */
        val id: String? = null,
        /** Имя/название для поисковых запросов. */
        val name: String? = null,
        /** Seed track ID для wave-станции. */
        val seedTrackId: String? = null,
        /** Жанр для жанрового добора. */
        val genre: String? = null,
        /** Список артистов для артист-based добора. */
        val artistIds: List<String> = emptyList()
    ) {
        enum class Type {
            WAVE,       // Персональная волна / радио
            ARTIST,     // Треки того же артиста + похожие
            ALBUM,      // Другие треки артиста альбома
            SEARCH,     // Поисковый запрос
            GENRE,      // Жанровой добор
            PLAYLIST,   // Плейлист
            LIBRARY,    // Медиатека (лайки)
            SIMILAR     // Похожие на текущий трек
        }
    }

    // ── Public API ──

    /**
     * Установить контекст автодобора. Вызывается при старте воспроизведения
     * из любого экрана (Album, Search, Artist, Library и т.д.).
     */
    fun setRefillContext(context: RefillContext?) {
        _refillContext.value = context
        android.util.Log.d("EndlessEngine", "Refill context set: ${context?.type?.name ?: "null"}, id=${context?.id}")
    }

    /**
     * Полная очистка состояния движка.
     * Вызывается при ручном переключении на другой альбом/трек пользователем.
     */
    fun reset() {
        android.util.Log.d("EndlessEngine", "Resetting engine state")
        cancelAllJobs()
        playedIds.clear()
        pendingRefillIds.clear()
        isRefilling.set(false)
        lastRefillTime.set(0L)
        refillCounter.set(0)
        _refillContext.value = null
    }

    /**
     * Запустить глобальный мониторинг очереди.
     * Должен вызываться один раз при инициализации PlayerController.
     */
    fun startGlobalQueueMonitor() {
        stopGlobalQueueMonitor()
        queueMonitorJob = scope.launch {
            while (isActive) {
                checkAndRefillIfNeeded()
                delay(1000) // Проверяем каждую секунду
            }
        }
        android.util.Log.d("EndlessEngine", "Global queue monitor started")
    }

    fun stopGlobalQueueMonitor() {
        queueMonitorJob?.cancel()
        queueMonitorJob = null
    }

    /**
     * Зарегистрировать трек как "известный" — добавляет в playedIds.
     * Вызывается при каждом добавлении трека в очередь.
     */
    fun registerTrack(trackId: String) {
        playedIds.add(trackId)
        // Ограничиваем размер истории
        if (playedIds.size > MAX_PLAYED_HISTORY) {
            val toRemove = playedIds.iterator()
            var removed = 0
            while (toRemove.hasNext() && removed < 50) {
                toRemove.next()
                toRemove.remove()
                removed++
            }
        }
    }

    /**
     * Зарегистрировать список треков как "известных".
     */
    fun registerTracks(trackIds: List<String>) {
        playedIds.addAll(trackIds)
    }

    /**
     * Запустить агрессивный префетч на экваторе текущего трека.
     * Вызывается при смене трека.
     */
    fun startEquatorPrefetch(
        track: Track,
        onPrefetch: suspend () -> Unit,
        onRefillNeeded: suspend () -> Unit
    ) {
        equatorPrefetchJob?.cancel()
        equatorPrefetchJob = scope.launch {
            val duration = track.durationMs
            if (duration <= 0) return@launch

            val equatorMs = (duration * EQUATOR_THRESHOLD).toLong()

            // Получаем текущий плеер для мониторинга позиции
            val player = getController() ?: getCompanionPlayer() ?: return@launch

            // Ждём достижения экватора
            while (isActive) {
                val currentPos = player.currentPosition.coerceAtLeast(0L)
                if (currentPos >= equatorMs) {
                    break
                }
                // Проверяем, не сменился ли трек
                val currentMediaId = player.currentMediaItem?.mediaId
                if (currentMediaId != track.id) {
                    return@launch // Трек сменился — отменяем
                }
                delay(POSITION_CHECK_INTERVAL_MS)
            }

            // Достигли экватора — запускаем префетч
            if (isActive) {
                android.util.Log.d("EndlessEngine", "Equator reached for ${track.id}, triggering prefetch")
                onPrefetch()

                // Проверяем, не пора ли refill
                val remaining = getRemainingTracks()
                if (remaining < REFILL_THRESHOLD) {
                    onRefillNeeded()
                }
            }
        }
    }

    fun cancelEquatorPrefetch() {
        equatorPrefetchJob?.cancel()
        equatorPrefetchJob = null
    }

    /**
     * Проверить, нужен ли refill, и выполнить его.
     * Публичный метод для ручного вызова (например, из UI).
     */
    suspend fun checkAndRefillIfNeeded(): Boolean {
        val remaining = getRemainingTracks()
        if (remaining < REFILL_THRESHOLD) {
            return performRefill()
        }
        return false
    }

    /**
     * Получить количество оставшихся треков в очереди (включая текущий).
     */
    fun getRemainingTracks(): Int {
        val player = getController() ?: getCompanionPlayer() ?: return 0
        val total = player.mediaItemCount
        val current = player.currentMediaItemIndex
        return if (total > 0 && current >= 0) (total - current) else 0
    }

    /**
     * Получить количество треков после текущего.
     */
    fun getUpcomingTracks(): Int {
        val player = getController() ?: getCompanionPlayer() ?: return 0
        val total = player.mediaItemCount
        val current = player.currentMediaItemIndex
        return if (total > 0 && current >= 0) (total - current - 1).coerceAtLeast(0) else 0
    }

    // ── Private: Refill Logic ──

    /**
     * Выполнить refill очереди новыми треками.
     * Потокобезопасно — только один refill одновременно.
     */
    private suspend fun performRefill(): Boolean {
        // Защита от одновременных refill
        if (!isRefilling.compareAndSet(false, true)) {
            android.util.Log.d("EndlessEngine", "Refill already in progress, skipping")
            return false
        }

        // Защита от спама
        val now = System.currentTimeMillis()
        val last = lastRefillTime.get()
        if (now - last < MIN_REFILL_INTERVAL_MS) {
            isRefilling.set(false)
            return false
        }

        return try {
            lastRefillTime.set(now)
            android.util.Log.d("EndlessEngine", "Starting refill, context=${_refillContext.value?.type?.name}")

            val context = _refillContext.value
            if (context == null) {
                android.util.Log.w("EndlessEngine", "No refill context set, using WAVE fallback")
            }

            val newTracks = fetchRefillTracks(context)

            if (newTracks.isNotEmpty()) {
                addTracksToQueue(newTracks)
                refillCounter.incrementAndGet()
                android.util.Log.d("EndlessEngine", "Refill complete: added ${newTracks.size} tracks")
                true
            } else {
                android.util.Log.w("EndlessEngine", "Refill returned no tracks")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("EndlessEngine", "Refill failed: ${e.message}")
            false
        } finally {
            isRefilling.set(false)
        }
    }

    /**
     * Запросить новые треки из API в зависимости от контекста.
     */
    private suspend fun fetchRefillTracks(context: RefillContext?): List<Track> {
        val effectiveContext = context ?: RefillContext(RefillContext.Type.WAVE)
        val excludeIds = buildExcludeSet()

        return when (effectiveContext.type) {
            RefillContext.Type.WAVE -> fetchWaveTracks(
                seedTrackId = effectiveContext.seedTrackId,
                excludeIds = excludeIds
            )
            RefillContext.Type.ARTIST -> fetchArtistTracks(
                artistId = effectiveContext.id,
                artistName = effectiveContext.name,
                excludeIds = excludeIds
            )
            RefillContext.Type.ALBUM -> fetchAlbumRefill(
                albumId = effectiveContext.id,
                excludeIds = excludeIds
            )
            RefillContext.Type.SEARCH -> fetchSearchRefill(
                query = effectiveContext.name ?: effectiveContext.id ?: "",
                excludeIds = excludeIds
            )
            RefillContext.Type.GENRE -> fetchGenreRefill(
                genre = effectiveContext.genre ?: effectiveContext.name ?: "",
                excludeIds = excludeIds
            )
            RefillContext.Type.PLAYLIST -> fetchWaveTracks(
                seedTrackId = effectiveContext.seedTrackId,
                excludeIds = excludeIds
            )
            RefillContext.Type.LIBRARY -> fetchLibraryRefill(excludeIds = excludeIds)
            RefillContext.Type.SIMILAR -> fetchWaveTracks(
                seedTrackId = effectiveContext.seedTrackId ?: effectiveContext.id,
                excludeIds = excludeIds
            )
        }
    }

    /**
     * Построить множество ID для исключения (played + pending + текущая очередь).
     */
    private fun buildExcludeSet(): Set<String> {
        val exclude = mutableSetOf<String>()
        exclude.addAll(playedIds)
        exclude.addAll(pendingRefillIds)

        // Добавляем ID из текущей очереди ExoPlayer
        val player = getController() ?: getCompanionPlayer()
        player?.let { p ->
            for (i in 0 until p.mediaItemCount) {
                p.getMediaItemAt(i)?.mediaId?.let { exclude.add(it) }
            }
        }

        return exclude
    }

    // ── API Fetch Methods ──

    /**
     * Запросить треки из персональной волны (wave).
     * Главный fallback для всех контекстов.
     */
    private suspend fun fetchWaveTracks(
        seedTrackId: String? = null,
        excludeIds: Set<String>
    ): List<Track> {
        val tracks = mutableListOf<Track>()
        val localExclude = excludeIds.toMutableSet()

        repeat(REFILL_BATCH_SIZE) {
            try {
                val response = com.liquidmusicglass.api.icm.IcmRepository.getWaveNext(
                    seedTrackId = seedTrackId.takeIf { tracks.isEmpty() },
                    exclude = localExclude.takeIf { it.isNotEmpty() }?.toList(),
                    recentSkips = 0
                )

                if (response == null || response.status != "ok") {
                    if (response?.status == "empty") return@repeat
                    return@repeat
                }

                val waveTrack = response.track ?: return@repeat

                // Дедупликация
                if (waveTrack.id in localExclude || waveTrack.id in playedIds) {
                    localExclude.add(waveTrack.id)
                    return@repeat
                }

                val track = com.liquidmusicglass.engine.Track(
                    id = waveTrack.id,
                    title = waveTrack.title,
                    artist = waveTrack.artist ?: "Unknown Artist",
                    albumName = "",
                    uri = Uri.parse("https://byicloud.online/track/${waveTrack.id}"),
                    durationMs = waveTrack.durationMs,
                    albumId = waveTrack.collectionId?.hashCode()?.toLong() ?: waveTrack.id.hashCode().toLong(),
                    coverUrl = waveTrack.cover?.replace("1000x1000", "600x600")
                )
                tracks.add(track)
                localExclude.add(waveTrack.id)
                pendingRefillIds.add(waveTrack.id)

            } catch (e: Exception) {
                android.util.Log.e("EndlessEngine", "Wave fetch error: ${e.message}")
                return@repeat
            }
        }

        return tracks
    }

    /**
     * Запросить треки артиста + похожих.
     */
    private suspend fun fetchArtistTracks(
        artistId: String?,
        artistName: String?,
        excludeIds: Set<String>
    ): List<Track> {
        val tracks = mutableListOf<Track>()
        val localExclude = excludeIds.toMutableSet()

        // 1. Пробуем получить топ-треки артиста
        if (!artistId.isNullOrBlank()) {
            try {
                val response = com.liquidmusicglass.api.icm.IcmRepository.getArtist(artistId)
                response?.topSongs
                    ?.filter { it.id !in localExclude && it.id !in playedIds }
                    ?.shuffled()
                    ?.take(REFILL_BATCH_SIZE / 2)
                    ?.forEach { song ->
                        tracks.add(com.liquidmusicglass.engine.Track(
                            id = song.id,
                            title = song.title,
                            artist = song.artist,
                            albumName = "",
                            uri = Uri.parse("https://byicloud.online/track/${song.id}"),
                            durationMs = song.durationMs,
                            albumId = song.artistId?.hashCode()?.toLong() ?: song.id.hashCode().toLong(),
                            coverUrl = song.cover.replace("1000x1000", "600x600"),
                            artists = emptyList()
                        ))
                        localExclude.add(song.id)
                    }
            } catch (e: Exception) {
                android.util.Log.e("EndlessEngine", "Artist fetch error: ${e.message}")
            }
        }

        // 2. Добираем wave треками
        if (tracks.size < REFILL_BATCH_SIZE) {
            val waveTracks = fetchWaveTracks(
                seedTrackId = artistId,
                excludeIds = localExclude
            )
            tracks.addAll(waveTracks.take(REFILL_BATCH_SIZE - tracks.size))
        }

        return tracks
    }

    /**
     * Добор для альбома — другие треки артиста.
     */
    private suspend fun fetchAlbumRefill(
        albumId: String?,
        excludeIds: Set<String>
    ): List<Track> {
        val tracks = mutableListOf<Track>()
        val localExclude = excludeIds.toMutableSet()

        if (!albumId.isNullOrBlank()) {
            try {
                val response = com.liquidmusicglass.api.icm.IcmRepository.getAlbum(albumId)
                response?.tracks
                    ?.filter { it.id !in localExclude && it.id !in playedIds }
                    ?.shuffled()
                    ?.take(REFILL_BATCH_SIZE)
                    ?.forEach { track ->
                        tracks.add(com.liquidmusicglass.engine.Track(
                            id = track.id,
                            title = track.title,
                            artist = track.artist,
                            albumName = "",
                            uri = Uri.parse("https://byicloud.online/track/${track.id}"),
                            durationMs = track.durationMs,
                            albumId = track.collectionId?.hashCode()?.toLong() ?: track.id.hashCode().toLong(),
                            coverUrl = track.cover.replace("1000x1000", "600x600"),
                            artists = emptyList()
                        ))
                        localExclude.add(track.id)
                    }
            } catch (e: Exception) {
                android.util.Log.e("EndlessEngine", "Album refill error: ${e.message}")
            }
        }

        // Fallback на wave
        if (tracks.isEmpty()) {
            tracks.addAll(fetchWaveTracks(excludeIds = localExclude))
        }

        return tracks
    }

    /**
     * Добор для поискового запроса.
     */
    private suspend fun fetchSearchRefill(
        query: String,
        excludeIds: Set<String>
    ): List<Track> {
        val tracks = mutableListOf<Track>()
        val localExclude = excludeIds.toMutableSet()

        try {
            val result = com.liquidmusicglass.api.icm.IcmRepository.searchAll(query)
            result?.items
                ?.filter { it.isTrack && it.id !in localExclude && it.id !in playedIds }
                ?.shuffled()
                ?.take(REFILL_BATCH_SIZE)
                ?.forEach { item ->
                    tracks.add(com.liquidmusicglass.engine.Track(
                        id = item.id,
                        title = item.title,
                        artist = item.displayArtist,
                        albumName = item.album ?: "",
                        uri = Uri.parse("https://byicloud.online/track/${item.id}"),
                        durationMs = item.durationMs,
                        albumId = item.collectionId?.toLongOrNull() ?: 0L,
                        coverUrl = item.cover
                    ))
                    localExclude.add(item.id)
                }
        } catch (e: Exception) {
            android.util.Log.e("EndlessEngine", "Search refill error: ${e.message}")
        }

        // Fallback на wave
        if (tracks.isEmpty()) {
            tracks.addAll(fetchWaveTracks(excludeIds = localExclude))
        }

        return tracks
    }

    /**
     * Жанровой добор через поиск.
     */
    private suspend fun fetchGenreRefill(
        genre: String,
        excludeIds: Set<String>
    ): List<Track> {
        return fetchSearchRefill(query = genre, excludeIds = excludeIds)
    }

    /**
     * Добор из медиатеки (лайки) — shuffle лайкнутых треков.
     */
    private suspend fun fetchLibraryRefill(excludeIds: Set<String>): List<Track> {
        val tracks = mutableListOf<Track>()
        try {
            val appContext = getAppContext()
            if (appContext != null) {
                val repo = com.liquidmusicglass.data.local.db.LibraryRepository.getInstance(appContext)
                val favorites = repo.getAllFavoritesAsTracks()
                    .filter { it.id !in excludeIds && it.id !in playedIds }
                    .shuffled()
                    .take(REFILL_BATCH_SIZE)

                tracks.addAll(favorites)
            }
        } catch (e: Exception) {
            android.util.Log.e("EndlessEngine", "Library refill error: ${e.message}")
        }

        // Fallback на wave
        if (tracks.isEmpty()) {
            tracks.addAll(fetchWaveTracks(excludeIds = excludeIds))
        }

        return tracks
    }

    // ── Queue Management ──

    /**
     * Атомарно добавить треки в конец очереди ExoPlayer.
     * Не сбивает текущий индекс воспроизведения.
     */
    private fun addTracksToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return

        val player = getController() ?: getCompanionPlayer() ?: return

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

        // Добавляем в конец очереди — текущий индекс не меняется
        player.addMediaItems(mediaItems)

        // Регистрируем треки
        tracks.forEach { registerTrack(it.id) }

        android.util.Log.d("EndlessEngine", "Added ${tracks.size} tracks to queue end")
    }

    // ── Utility ──

    private fun cancelAllJobs() {
        equatorPrefetchJob?.cancel()
        equatorPrefetchJob = null
        queueMonitorJob?.cancel()
        queueMonitorJob = null
    }

    private fun getAppContext(): android.content.Context? {
        // Получаем контекст через PlayerController
        return com.liquidmusicglass.engine.PlayerController.getAppContext()
    }

    /**
     * Получить статистику движка (для дебага).
     */
    fun getStats(): EngineStats {
        return EngineStats(
            playedHistorySize = playedIds.size,
            pendingRefillSize = pendingRefillIds.size,
            refillCount = refillCounter.get(),
            isRefilling = isRefilling.get(),
            currentContext = _refillContext.value?.type?.name
        )
    }

    data class EngineStats(
        val playedHistorySize: Int,
        val pendingRefillSize: Int,
        val refillCount: Int,
        val isRefilling: Boolean,
        val currentContext: String?
    )
}
