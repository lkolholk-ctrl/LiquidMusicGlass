package com.liquidmusicglass.api.icm

import com.liquidmusicglass.engine.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for ICM Music Partner API.
 * Abstracts API calls, caching, and model conversion.
 */
object IcmRepository {

    private val api = IcmApi.getInstance()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _lastApiException = MutableStateFlow<IcmApiException?>(null)
    val lastApiException: StateFlow<IcmApiException?> = _lastApiException

    private var _lastException: Exception? = null

    // ── Терминальные «бизнес-гейты» (анти-ANR: остановить серии ретраев) ──
    // 403 subscription_required на /me/preferences и 405 на /library/likes — это НЕ
    // сетевые ошибки, ретрай их не исправит. Один раз получив такую — больше не
    // дёргаем эндпоинт (иначе recomposition/повторный init = «кричит на сервер»).
    // Сбрасываются при новой сессии (логин).
    @Volatile private var preferencesBlocked = false
    @Volatile private var likesBlocked = false

    /** Сбросить бизнес-гейты (на новый логин/сессию — состояние подписки могло измениться). */
    fun resetBusinessGates() {
        preferencesBlocked = false
        likesBlocked = false
    }

    /** Default region */
    var region: String
        get() = api.defaultRegion
        set(value) { api.defaultRegion = value }

    /** Stream quality */
    var streamQuality: String?
        get() = api.streamQuality
        set(value) { api.streamQuality = value }

    /**
     * Initialize with API key.
     * Get key: https://byicloud.online/partners
     */
    fun init(apiKey: String, partnerUserId: String? = null) {
        api.apiKey = apiKey
        api.sessionToken = null
        api.partnerUserId = partnerUserId
        _isInitialized.value = true
        _lastError.value = null
        _lastApiException.value = null
    }

    /**
     * Initialize with session token (for client requests).
     */
    fun initWithToken(sessionToken: String, partnerUserId: String? = null) {
        api.apiKey = null
        api.sessionToken = sessionToken
        api.partnerUserId = partnerUserId
        _isInitialized.value = true
        _lastError.value = null
        _lastApiException.value = null
    }

    /**
     * Update only the partner_user_id (e.g. after authentication completes).
     * Keeps the existing apiKey/sessionToken intact.
     */
    fun setPartnerUserId(partnerUserId: String?) {
        api.partnerUserId = partnerUserId
        resetBusinessGates()  // авторизация изменилась — даём подписке/likes шанс заново
    }

    /** Update the session token (used after /session/issue or Telegram OAuth). */
    fun setSessionToken(sessionToken: String?) {
        api.sessionToken = sessionToken
        resetBusinessGates()
    }

    /** Current partner user id used as X-Partner-User-Id. */
    val partnerUserId: String?
        get() = api.partnerUserId

    /**
     * Reset initialization.
     */
    fun reset() {
        api.apiKey = null
        api.sessionToken = null
        api.partnerUserId = null
        _isInitialized.value = false
        _lastError.value = null
        _lastException = null
    }

    /**
     * Check API health.
     */
    suspend fun health(): Result<IcmHealthResponse> {
        return api.health().also { result ->
            result.exceptionOrNull()?.let {
                _lastException = it as? Exception
                _lastError.value = it.message
            }
        }
    }

    /**
     * Search tracks only (isTrack=true).
     * @param query Search string
     * @param region Region override
     * @param source Music source: "apple", "vk", "all"
     * @param limit Max results
     */
    suspend fun searchTracks(
        query: String,
        region: String? = null,
        source: String? = null,
        limit: Int? = null
    ): List<Track> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val result = api.search(trimmed, region, source, limit)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
            ?.items
            ?.filter { it.isTrack }
            ?.map { it.toTrack() }
            ?: emptyList()
    }

    // ── Кэш поиска: дока ICM рекомендует кешировать /search на 60с по ключу
    // (query+region). Тап по чипу истории / повторный ввод того же запроса =
    // мгновенная выдача без сети и без rate-limit-hit. LRU на 30 записей. ──
    private class CachedSearch(val response: IcmSearchResponse, val at: Long)
    private val searchCache = object : LinkedHashMap<String, CachedSearch>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSearch>) =
            size > 30
    }
    private const val SEARCH_CACHE_TTL_MS = 60_000L

    /**
     * Search — all results (tracks + albums + artists).
     * @param query Search string
     * @param region Region override
     * @param source Music source: "apple", "vk", "all"
     * @param limit Max results
     */
    suspend fun searchAll(
        query: String,
        region: String? = null,
        source: String? = null,
        limit: Int? = null
    ): IcmSearchResponse? {
        val trimmed = query.trim()
        if (trimmed.length < 2) return null

        val cacheKey = "${region ?: ""}|${source ?: ""}|${limit ?: 0}|${trimmed.lowercase()}"
        synchronized(searchCache) {
            searchCache[cacheKey]?.let {
                if (System.currentTimeMillis() - it.at < SEARCH_CACHE_TTL_MS) return it.response
            }
        }

        val result = api.search(trimmed, region, source, limit)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
            _lastApiException.value = it as? IcmApiException
        }
        return result.getOrNull()?.also { resp ->
            synchronized(searchCache) {
                searchCache[cacheKey] = CachedSearch(resp, System.currentTimeMillis())
            }
        }
    }

    /**
     * Search VK tracks only.
     */
    suspend fun searchVkTracks(
        query: String,
        region: String? = null,
        limit: Int? = null
    ): List<Track> {
        return searchTracks(query, region, IcmSearchSource.VK, limit)
    }

    /**
     * Search from all sources (Apple + VK).
     */
    suspend fun searchAllSources(
        query: String,
        region: String? = null,
        limit: Int? = null
    ): IcmSearchResponse? {
        return searchAll(query, region, IcmSearchSource.ALL, limit)
    }

    // ═══════════════════════════════════════════════════════════
    //  Home Screen Content (Banners, New Releases, Charts)
    // ═══════════════════════════════════════════════════════════

    /**
     * Load home screen content blocks.
     * Parallel async requests + JSON caching for offline-first experience.
     */
    suspend fun loadHomeContent(): IcmHomeResponse = coroutineScope {
        // ─── Parallel search for all content blocks (Personalized via local Room DB) ───
        val context = com.liquidmusicglass.engine.PlayerController.context
        var topArtists = emptyList<String>()
        var topGenres = emptyList<String>()
        if (context != null) {
            try {
                val db = com.liquidmusicglass.data.local.db.AppDatabase.getInstance(context)
                val dao = db.playbackHistoryDao()
                topArtists = dao.getTopArtists(5)
                topGenres = dao.getTopGenres(3)
            } catch (e: Exception) {
                android.util.Log.e("IcmRepository", "Failed to query personalization data from Room", e)
            }
        }

        // Popular tracks (Personalized if topArtists is available)
        val popularDeferred = async {
            val queries = if (topArtists.isNotEmpty()) {
                topArtists.take(3)
            } else {
                listOf(
                    listOf("top hits 2025", "viral hits", "trending now"),
                    listOf("popular songs", "hot tracks", "chart toppers"),
                    listOf("best songs", "top tracks", "hit parade"),
                    listOf("viral songs", "trending hits", "top music")
                ).random()
            }
            searchToHomeItems(queries, 10, filterTracks = true)
        }
        
        // Banners / Featured
        val bannerDeferred = async {
            val queries = listOf(
                listOf("top hits", "popular", "trending"),
                listOf("viral", "hot", "featured"),
                listOf("best new", "rising", "buzzing")
            ).random()
            searchToHomeItems(queries, 6, filterTracks = true, genreTag = true)
        }
        
        // New Releases (Personalized by genre if topGenres is available)
        val newReleasesDeferred = async {
            val queries = if (topGenres.isNotEmpty()) {
                topGenres.take(3)
            } else {
                listOf(
                    listOf("new releases", "new music", "latest"),
                    listOf("fresh drops", "new albums", "just released"),
                    listOf("this week", "new singles", "debut")
                ).random()
            }
            searchToHomeItems(queries, 10, filterAlbums = true)
        }
        
        // Charts
        val chartsDeferred = async {
            val queries = listOf(
                listOf("top 100", "chart", "hot", "viral"),
                listOf("billboard", "ranking", "top 50", "trending"),
                listOf("most played", "global hits", "top chart", "ranked")
            ).random()
            var rank = 1
            searchToHomeItems(queries, 15, filterTracks = true) { item ->
                item.copy(rank = rank++)
            }
        }
        
        // Await all in parallel
        val (popular, banners, newReleases, charts) = awaitAll(
            popularDeferred, bannerDeferred, newReleasesDeferred, chartsDeferred
        )
        
        val blocks = mutableListOf<IcmHomeBlock>()
        
        if (popular.isNotEmpty()) {
            val title = if (topArtists.isNotEmpty()) "More from your favorite artists" else "Popular"
            blocks.add(IcmHomeBlock(id = "popular", title = title, type = "popular", items = popular))
        }
        if (banners.isNotEmpty()) {
            blocks.add(IcmHomeBlock(id = "banners", title = "Featured", type = "banner", items = banners))
        }
        if (newReleases.isNotEmpty()) {
            val title = if (topGenres.isNotEmpty()) "Based on your heavy rotation" else "New Releases"
            blocks.add(IcmHomeBlock(id = "new_releases", title = title, type = "new_releases", items = newReleases))
        }
        if (charts.isNotEmpty()) {
            blocks.add(IcmHomeBlock(id = "charts", title = "Top Charts", type = "charts", items = charts))
        }
        
        // Wave recommendations (sequential, needs exclude tracking)
        if (api.partnerUserId != null) {
            val waveItems = loadWaveRecommendations()
            if (waveItems.isNotEmpty()) {
                blocks.add(IcmHomeBlock(
                    id = "recommendations",
                    title = "Made For You",
                    type = "recommendations",
                    items = waveItems
                ))
            }
        }
        
        IcmHomeResponse(blocks = blocks)
    }
    
    /**
     * Helper: search queries and convert to HomeItems.
     */
    private suspend fun searchToHomeItems(
        queries: List<String>,
        maxItems: Int,
        filterTracks: Boolean = false,
        filterAlbums: Boolean = false,
        genreTag: Boolean = false,
        transform: ((IcmHomeItem) -> IcmHomeItem)? = null
    ): List<IcmHomeItem> {
        val items = mutableListOf<IcmHomeItem>()
        for (query in queries) {
            if (items.size >= maxItems) break
            try {
                val result = searchAll(query, limit = maxItems, source = IcmSearchSource.ALL)
                result?.items?.forEach { item ->
                    if (items.size >= maxItems) return@forEach
                    val shouldInclude = when {
                        filterTracks && filterAlbums -> true
                        filterTracks -> item.isTrack
                        filterAlbums -> item.isAlbum || item.collectionId != null
                        else -> true
                    }
                    if (shouldInclude) {
                        var homeItem = IcmHomeItem(
                            id = item.id,
                            title = item.title,
                            artist = item.displayArtist,
                            artistId = item.artistId,
                            cover = item.cover,
                            duration = item.duration,
                            source = item.source,
                            collectionId = item.collectionId,
                            album = item.album,
                            genre = if (genreTag) query else null,
                            // Сохраняем тип сущности, чтобы UI не угадывал по collectionId.
                            isAlbum = item.isAlbum,
                            isArtist = item.isArtist
                        )
                        transform?.let { homeItem = it(homeItem) }
                        items.add(homeItem)
                    }
                }
            } catch (_: Exception) {
                // Skip failed query, continue with next
            }
        }
        return items
    }
    
    /**
     * Load wave recommendations sequentially (needs exclude tracking).
     */
    private suspend fun loadWaveRecommendations(): List<IcmHomeItem> {
        val waveItems = mutableListOf<IcmHomeItem>()
        val excludeIds = mutableListOf<String>()
        
        val seedCandidates = mutableListOf<String>()
        seedCandidates.addAll(com.liquidmusicglass.engine.PlayerController.recentlyPlayed.value.map { it.id })
        
        val context = com.liquidmusicglass.engine.PlayerController.context
        if (context != null) {
            seedCandidates.addAll(com.liquidmusicglass.data.local.LocalStorage.getHistory(context).map { it.trackId })
            try {
                val favs = com.liquidmusicglass.data.local.db.LibraryRepository.getInstance(context).getAllFavoritesAsTracks()
                seedCandidates.addAll(favs.map { it.id })
            } catch (_: Exception) {}
        }
        val cleanSeeds = seedCandidates.distinct().filter { it.isNotBlank() }
        
        repeat(5) { i ->
            val seedTrackId = cleanSeeds.getOrNull(i % cleanSeeds.size.coerceAtLeast(1))
            try {
                val response = getWaveNext(
                    seedTrackId = seedTrackId,
                    exclude = excludeIds.takeIf { it.isNotEmpty() },
                    recentSkips = 0
                )
                if (response != null && response.status == "ok" && response.track != null) {
                    val trackId = response.track.id
                    excludeIds.add(trackId)
                    waveItems.add(IcmHomeItem(
                        id = trackId,
                        title = response.track.title,
                        artist = response.track.artist ?: "Unknown Artist",
                        cover = response.track.cover,
                        duration = response.track.durationMs,
                        source = response.track.source
                    ))
                }
            } catch (_: Exception) {
                // Skip failed wave request
            }
        }
        return waveItems
    }

    /**
     * Load charts (top charts from Apple Music).
     */
    suspend fun loadCharts(): List<IcmChart> {
        val chartDefinitions = listOf(
            Triple("top100", "Top 100 USA", "top 100 usa"),
            Triple("viral", "Viral Hits", "viral hits"),
            Triple("global", "Global Top 50", "global top 50"),
            Triple("trending", "Trending Now", "trending now"),
            Triple("hot", "Hot Tracks", "hot tracks"),
            Triple("new", "New Music", "new music friday")
        )

        val charts = mutableListOf<IcmChart>()
        for ((id, name, query) in chartDefinitions) {
            try {
                val result = searchAll(query, limit = 5, source = IcmSearchSource.APPLE)
                val tracks = result?.items?.filter { it.isTrack }?.take(5) ?: emptyList()
                if (tracks.isNotEmpty()) {
                    charts.add(
                        IcmChart(
                            id = id,
                            name = name,
                            query = query,
                            cover = tracks.firstOrNull()?.cover,
                            tracks = tracks
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        return charts
    }

    suspend fun getStreamUrl(trackId: String, region: String? = null, source: String? = null): String? {
        val quality = IcmAuthRepository.getEffectiveQuality(trackId, source)
        val result = api.getTrack(trackId, region, quality)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.url
    }

    /**
     * Get full TrackResponse (including expires_at).
     */
    suspend fun getTrackInfo(trackId: String, region: String? = null, quality: String? = null): IcmTrackResponse? {
        android.util.Log.d("IcmRepository", "[VK_DEBUG] getTrackInfo called: trackId=$trackId, region=$region, quality=$quality")
        val result = api.getTrack(trackId, region, quality)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
            if (it is IcmApiException) {
                _lastApiException.value = it
                android.util.Log.e("IcmRepository", "[VK_DEBUG] API error: code=${it.code}, errorCode=${it.errorCode}, message=${it.message}, requiredRegion=${it.requiredRegion}")
            } else {
                android.util.Log.e("IcmRepository", "[VK_DEBUG] Generic error: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        result.getOrNull()?.let {
            android.util.Log.d("IcmRepository", "[VK_DEBUG] API success: source=${it.source}, quality=${it.quality}, url=${it.url.take(60)}...")
        }
        return result.getOrNull()
    }

    fun getTrackInfoSync(trackId: String, region: String? = null, quality: String? = null): IcmTrackResponse? {
        val result = api.getTrackSync(trackId, region, quality)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
            if (it is IcmApiException) {
                _lastApiException.value = it
            }
        }
        return result.getOrNull()
    }

    /**
     * Album tracks as Track list.
     */
    suspend fun getAlbumTracks(albumId: String, region: String? = null): List<Track> {
        val result = api.getAlbum(albumId, region)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.tracks?.map { it.toTrack() } ?: emptyList()
    }

    /**
     * Album info.
     */
    suspend fun getAlbum(albumId: String, region: String? = null): IcmAlbumResponse? {
        val result = api.getAlbum(albumId, region)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Artist top tracks.
     */
    suspend fun getArtistTopTracks(artistId: String, region: String? = null): List<Track> {
        val result = api.getArtist(artistId, region)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.topSongs?.map { it.toTrack() } ?: emptyList()
    }

    /**
     * Artist info.
     */
    suspend fun getArtist(artistId: String, region: String? = null): IcmArtistResponse? {
        val result = api.getArtist(artistId, region)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Track metadata.
     */
    suspend fun getTrackMeta(trackId: String): IcmTrackMeta? {
        val result = api.getTrackMeta(trackId)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Editorial Apple Music playlist (id starts with pl.).
     * ICM API uses the same /album/{id} endpoint for playlists.
     */
    suspend fun getPlaylist(playlistId: String, region: String? = null): IcmAlbumResponse? {
        val result = api.getPlaylist(playlistId, region)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Song lyrics.
     */
    suspend fun getLyrics(trackId: String): IcmLyricsResponse? {
        val result = api.getLyrics(trackId)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Sign cover URL for custom covers.
     */
    suspend fun signCover(fileId: String): String? {
        val result = api.signCover(fileId)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.url
    }

    /**
     * Get user's liked tracks from library.
     * Requires partnerUserId to be set and user to be linked.
     */
    suspend fun getLibraryLikes(
        source: String? = null,
        limit: Int? = null,
        offset: Int? = null
    ): IcmLibraryLikesResponse? {
        // Терминально: 405 (метод не поддержан) / 403 → эндпоинт недоступен, не зовём.
        if (likesBlocked) return null
        val result = api.getLibraryLikes(source, limit, offset)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
            val code = (it as? IcmApiException)?.code
            if (code == 405 || code == 403) likesBlocked = true
        }
        return result.getOrNull()
    }

    /**
     * Like a track in cloud library.
     */
    suspend fun likeTrack(trackId: String): Boolean {
        val result = api.likeTrack(trackId)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.isSuccess
    }

    /**
     * Unlike a track in cloud library.
     */
    suspend fun unlikeTrack(trackId: String): Boolean {
        val result = api.unlikeTrack(trackId)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.isSuccess
    }

    /**
     * Get user's artist subscriptions from library.
     * Requires partnerUserId to be set and user to be linked.
     */
    suspend fun getLibrarySubscriptions(
        source: String? = null,
        limit: Int? = null,
        offset: Int? = null
    ): IcmLibrarySubscriptionsResponse? {
        val result = api.getLibrarySubscriptions(source, limit, offset)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Get next track from user's personal wave (radio).
     * Requires partnerUserId to be set and user to be linked.
     * Call repeatedly to build a personalized radio queue.
     *
     * Preload 3-5 tracks ahead for seamless playback.
     *
     * @param seedTrackId Optional track ID to create a "station based on track"
     * @param exclude Track IDs to exclude (current queue) — merged with Room history
     * @param recentSkips Number of consecutive skips (skip-streak fallback)
     * @param region Region override
     * @param source Source override
     * @param mood Mood filter (e.g., "energetic", "chill", "focus")
     * @param genre Genre filter (e.g., "electronic", "rock", "jazz")
     * @param historyLimit How many recent track IDs to fetch from Room for exclusion (default 200)
     */
    suspend fun getWaveNext(
        seedTrackId: String? = null,
        exclude: List<String>? = null,
        recentSkips: Int? = null,
        region: String? = null,
        source: String? = null,
        mood: String? = null,
        genre: String? = null,
        historyLimit: Int = 200
    ): IcmWaveResponse? {
        // Build exclude list from: (1) caller-provided IDs, (2) Room playback history
        val roomExclude = try {
            val ctx = com.liquidmusicglass.engine.PlayerController.context
            if (ctx != null) {
                val db = com.liquidmusicglass.data.local.db.AppDatabase.getInstance(ctx)
                db.playbackHistoryDao().getRecentTrackIds(historyLimit)
            } else emptyList()
        } catch (e: Exception) {
            android.util.Log.w("IcmRepository", "Failed to read Room exclude list: ${e.message}")
            emptyList()
        }
        val mergedExclude = ((exclude ?: emptyList()) + roomExclude).distinct()

        val result = api.getWaveNext(
            seedTrackId,
            mergedExclude.takeIf { it.isNotEmpty() },
            recentSkips,
            region,
            source,
            mood,
            genre
        )
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Build a wave queue by calling API multiple times.
     * Recommended: preload 3-5 tracks for seamless playback.
     * Stops early when the API reports `status == "empty"` (no more candidates).
     */
    suspend fun buildWaveQueue(count: Int = 5, seedTrackId: String? = null): List<com.liquidmusicglass.engine.Track> {
        val tracks = mutableListOf<com.liquidmusicglass.engine.Track>()
        val exclude = mutableListOf<String>()
        for (i in 0 until count) {
            val lastHttpCode = getLastHttpCode()
            val lastErrCode = getLastErrorCode()
            if (lastHttpCode == 429 || lastErrCode == "rate_limited" || lastErrCode == "ip_temporarily_blocked") {
                break
            }
            if (i > 0) {
                kotlinx.coroutines.delay(150)
            }
            val response = getWaveNext(seedTrackId, exclude.takeIf { it.isNotEmpty() }) ?: break
            if (response.status == "empty") break
            val track = response.track ?: continue
            tracks.add(track.toTrack())
            exclude.add(track.id)
        }
        return tracks
    }

    /**
     * Send wave feedback (less/more track/artist/genre).
     */
    suspend fun sendWaveFeedback(feedbackType: String, value: String): Boolean {
        val result = api.sendWaveFeedback(feedbackType, value)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.ok == true
    }

    // ── Доставка сигналов волны для оффлайн-очереди (WaveSignalQueue) ──
    // DELIVERED — сервер ответил (сигнал учтён/проигнорирован — неважно);
    // RETRY     — сеть/5xx/429/авторизация: сигнал стоит докинуть позже;
    // REJECTED  — постоянная 4xx (битый запрос): копить бессмысленно.
    enum class DeliveryResult { DELIVERED, RETRY, REJECTED }

    private fun classifyDelivery(result: Result<*>): DeliveryResult {
        if (result.isSuccess) return DeliveryResult.DELIVERED
        val e = result.exceptionOrNull()
        return if (e is IcmApiException &&
            e.code in 400..499 && e.code !in setOf(401, 403, 408, 429)
        ) DeliveryResult.REJECTED else DeliveryResult.RETRY
    }

    suspend fun deliverWaveFeedback(feedbackType: String, value: String): DeliveryResult =
        classifyDelivery(api.sendWaveFeedback(feedbackType, value))

    suspend fun deliverWavePlayback(
        trackId: String,
        playedSeconds: Double,
        totalSeconds: Double?,
        completed: Boolean?,
        skipped: Boolean?
    ): DeliveryResult =
        classifyDelivery(api.logWavePlayback(trackId, playedSeconds, totalSeconds, completed, skipped))

    /**
     * Reset wave history and preferences.
     */
    suspend fun resetWave(): Boolean {
        val result = api.resetWave()
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.isSuccess == true
    }

    /**
     * Get popular artists for wave onboarding.
     * Does NOT require partnerUserId.
     */
    suspend fun getWavePopularArtists(): List<IcmWaveOnboardingArtist> {
        val result = api.getWavePopularArtists()
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull() ?: emptyList()
    }

    /**
     * Check wave onboarding status.
     */
    suspend fun getWaveOnboarding(): IcmWaveOnboardingResponse? {
        val result = api.getWaveOnboarding()
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Save user's artist selection for wave onboarding.
     */
    suspend fun saveWaveOnboarding(artists: List<IcmWaveOnboardingArtistSave>): Boolean {
        val result = api.saveWaveOnboarding(artists)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.ok == true
    }

    /**
     * Log playback event for wave ranking improvement.
     * Call when user finishes/skips/switches a wave track.
     */
    suspend fun logWavePlayback(
        trackId: String,
        playedSeconds: Double,
        totalSeconds: Double? = null,
        completed: Boolean? = null,
        skipped: Boolean? = null
    ): Boolean {
        val result = api.logWavePlayback(trackId, playedSeconds, totalSeconds, completed, skipped)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.logged == true
    }

    // ═══════════════════════════════════════════════════════════
    //  Personal Cabinet (/me/*)
    // ═══════════════════════════════════════════════════════════

    /**
     * Get user's preferred stream quality.
     * Requires linked user with active subscription.
     */
    suspend fun getUserQuality(): IcmUserQualityResponse? {
        val result = api.getUserQuality()
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Set user's preferred stream quality.
     * Requires linked user with active subscription.
     */
    suspend fun setUserQuality(quality: String): IcmUserQualityResponse? {
        val result = api.setUserQuality(quality)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Get current user preferences (quality, region, hide_explicit, source).
     * Docs 8.5.
     */
    suspend fun getUserPreferences(): IcmUserPreferences? {
        // Терминально: подписки нет (403) → не дёргаем эндпоинт повторно.
        if (preferencesBlocked) return null
        val result = api.getUserPreferences()
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
            if ((it as? IcmApiException)?.code == 403) preferencesBlocked = true
        }
        return result.getOrNull()
    }

    /**
     * Update user preferences. Only non-null fields in [prefs] are sent to the
     * server. Docs 8.5.
     */
    suspend fun updateUserPreferences(prefs: IcmUserPreferences): IcmUserPreferences? {
        val result = api.updateUserPreferences(prefs)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Get user profile (icm_user_id, email, subscription).
     */
    suspend fun getUserProfile(): IcmUserProfile? {
        val result = api.getUserProfile()
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Get user's ICM subscription information.
     */
    suspend fun getUserSubscription(): IcmSubscriptionResponse? {
        val result = api.getUserSubscription()
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Get user's current and available regions.
     */
    suspend fun getUserRegion(): IcmRegionResponse? {
        val result = api.getUserRegion()
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Update user's region.
     */
    suspend fun updateUserRegion(region: String): IcmUpdateRegionResponse? {
        val result = api.updateUserRegion(region)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Start playlist import from Yandex or Apple.
     */
    suspend fun importPlaylist(source: String, url: String, name: String? = null): IcmPlaylistImportResponse? {
        IcmApiFileLogger.log("D", "IcmRepository", "importPlaylist START: source=$source, url=$url, name=$name")
        val result = api.importPlaylist(source, url, name)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
            IcmApiFileLogger.log("E", "IcmRepository", "importPlaylist error: ${it.javaClass.simpleName}: ${it.message}")
            if (it is IcmApiException) {
                IcmApiFileLogger.log("E", "IcmRepository", "API error: code=${it.code}, errorCode=${it.errorCode}, body=${it.message}")
            }
        }
        result.getOrNull()?.let {
            IcmApiFileLogger.log("D", "IcmRepository", "importPlaylist success: playlistId=${it.playlistId}, jobId=${it.jobId}, status=${it.status}, pollAfter=${it.pollAfter}")
        }
        return result.getOrNull()
    }

    /**
     * Preview playlist without importing it.
     */
    suspend fun previewPlaylist(source: String, url: String): IcmPlaylistPreviewResponse? {
        val result = api.previewPlaylist(source, url)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Get the status of an asynchronous playlist import job.
     */
    suspend fun getImportJobStatus(jobId: String): IcmPlaylistImportJobResponse? {
        IcmApiFileLogger.log("D", "IcmRepository", "getImportJobStatus START: jobId=$jobId")
        val result = api.getImportJobStatus(jobId)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
            IcmApiFileLogger.log("E", "IcmRepository", "getImportJobStatus error: ${it.javaClass.simpleName}: ${it.message}")
            if (it is IcmApiException) {
                IcmApiFileLogger.log("E", "IcmRepository", "API error: code=${it.code}, errorCode=${it.errorCode}")
            }
        }
        result.getOrNull()?.let {
            IcmApiFileLogger.log("D", "IcmRepository", "getImportJobStatus success: status=${it.status}, progress=${it.progress}, playlistId=${it.playlistId}, error=${it.error}, message=${it.message}")
        }
        return result.getOrNull()
    }

    /**
     * Get a list of the user's imported playlists.
     */
    suspend fun getUserPlaylists(limit: Int = 50, offset: Int = 0): IcmUserPlaylistsResponse? {
        val result = api.getUserPlaylists(limit, offset)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Get tracks from an imported playlist.
     */
    suspend fun getUserPlaylistTracks(playlistId: String, limit: Int = 200, offset: Int = 0): IcmUserPlaylistTracksResponse? {
        val result = api.getUserPlaylistTracks(playlistId, limit, offset)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Delete an imported playlist.
     */
    suspend fun deleteUserPlaylist(playlistId: String): IcmDeletePlaylistResponse? {
        val result = api.deleteUserPlaylist(playlistId)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    // ═══════════════════════════════════════════════════════════
    //  Batch & Async
    // ═══════════════════════════════════════════════════════════

    /**
     * Batch track metadata — up to 50 per request.
     * Saves rate-limit and removes round-trip.
     */
    suspend fun getBatchTrackMeta(trackIds: List<String>): IcmBatchTrackMetaResponse? {
        if (trackIds.isEmpty()) {
            _lastError.value = "trackIds must not be empty"
            return null
        }
        if (trackIds.size > 50) {
            _lastError.value = "trackIds max 50, got ${trackIds.size}"
            return null
        }
        val result = api.getBatchTrackMeta(trackIds)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Get stream URL with async fallback.
     * If track is cold — automatically polls job until ready.
     * @param maxPollAttempts Max polling attempts (default 30 = ~60 sec)
     * @param pollIntervalMs Interval between attempts (default 2000ms)
     */
    suspend fun getStreamUrlAsync(
        trackId: String,
        region: String? = null,
        quality: String? = IcmAuthRepository.getEffectiveQuality(trackId),
        maxPollAttempts: Int = 30,
        pollIntervalMs: Long = 2000
    ): String? {
        val result = api.getTrackAsync(trackId, region, quality)
        val exception = result.exceptionOrNull()

        if (exception is IcmAsyncPendingException) {
            return pollAsyncJob(exception.pending, maxPollAttempts, pollIntervalMs)
        }

        exception?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }

        return result.getOrNull()?.url
    }

    /**
     * Full TrackResponse with async fallback.
     */
    suspend fun getTrackInfoAsync(
        trackId: String,
        region: String? = null,
        quality: String? = IcmAuthRepository.getEffectiveQuality(trackId),
        maxPollAttempts: Int = 30,
        pollIntervalMs: Long = 2000
    ): IcmTrackResponse? {
        val result = api.getTrackAsync(trackId, region, quality)
        val exception = result.exceptionOrNull()

        if (exception is IcmAsyncPendingException) {
            val ready = pollAsyncJobFull(exception.pending, maxPollAttempts, pollIntervalMs)
            return ready?.let {
                IcmTrackResponse(
                    trackId = it.trackId,
                    fileId = it.fileId,
                    source = it.source,
                    quality = it.quality,
                    artistId = it.artistId,
                    url = it.url,
                    expiresAt = it.expiresAt
                )
            }
        }

        exception?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    private suspend fun pollAsyncJob(
        pending: IcmAsyncTrackPending,
        maxAttempts: Int,
        intervalMs: Long
    ): String? {
        var attempts = 0
        while (attempts < maxAttempts) {
            if (IcmRateGate.isBanned()) return null
            delay(pending.pollAfterSeconds.coerceAtLeast(1) * 1000L)
            val pollResult = api.pollAsyncJob(pending.jobId)
            pollResult.getOrNull()?.let { ready ->
                if (ready.status == "ready") {
                    return ready.url
                }
            }
            pollResult.exceptionOrNull()?.let {
                _lastError.value = "Poll failed: ${it.message}"
                return null
            }
            attempts++
        }
        _lastError.value = "Async job ${pending.jobId} timed out after $maxAttempts attempts"
        return null
    }

    private suspend fun pollAsyncJobFull(
        pending: IcmAsyncTrackPending,
        maxAttempts: Int,
        intervalMs: Long
    ): IcmAsyncTrackReady? {
        var attempts = 0
        while (attempts < maxAttempts) {
            if (IcmRateGate.isBanned()) return null
            delay(pending.pollAfterSeconds.coerceAtLeast(1) * 1000L)
            val pollResult = api.pollAsyncJob(pending.jobId)
            pollResult.getOrNull()?.let { ready ->
                if (ready.status == "ready") {
                    return ready
                }
            }
            pollResult.exceptionOrNull()?.let {
                _lastError.value = "Poll failed: ${it.message}"
                return null
            }
            attempts++
        }
        _lastError.value = "Async job ${pending.jobId} timed out after $maxAttempts attempts"
        return null
    }

    // ═══════════════════════════════════════════════════════════
    //  Account Linking
    // ═══════════════════════════════════════════════════════════

    /**
     * Generate URL for linking user account to ICM.
     * Requires partnerId — your ID in ICM system (from /health response partnerId).
     */
    fun buildAccountLinkUrl(
        partnerId: String,
        partnerUserId: String,
        redirectUri: String,
        state: String
    ): String {
        return api.buildAccountLinkUrl(partnerId, partnerUserId, redirectUri, state)
    }

    /**
     * Parse callback from ICM after linking.
     * Verify state matches what was sent.
     */
    fun parseAccountLinkCallback(
        state: String,
        linked: Boolean,
        icmUserId: String? = null,
        error: String? = null
    ): IcmAccountLinkCallback {
        return api.parseAccountLinkCallback(state, linked, icmUserId, error)
    }

    // ═══════════════════════════════════════════════════════════
    //  Email Account Linking (S2S only)
    // ═══════════════════════════════════════════════════════════

    /**
     * Request email OTP for account linking.
     * Auto-registers new ICM account if email doesn't exist.
     */
    suspend fun requestEmailLink(
        partnerUserId: String,
        email: String,
        state: String? = null
    ): IcmEmailLinkResponse? {
        val result = api.requestEmailLink(partnerUserId, email, state)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Verify email OTP and link account.
     */
    suspend fun verifyEmailLink(nonce: String, otp: String): IcmEmailVerifyResponse? {
        val result = api.verifyEmailLink(nonce, otp)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Change password for linked user.
     */
    suspend fun changePassword(
        partnerUserId: String,
        currentPassword: String,
        newPassword: String
    ): Boolean {
        val result = api.changePassword(partnerUserId, currentPassword, newPassword)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.changed == true
    }

    /**
     * Reset password for linked user.
     */
    suspend fun resetPassword(partnerUserId: String): Boolean {
        val result = api.resetPassword(partnerUserId)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.reset == true
    }

    // ═══════════════════════════════════════════════════════════
    //  Error Handling Helpers
    // ═══════════════════════════════════════════════════════════

    /** Check if last error is region unavailable (451). */
    fun isRegionUnavailable(): Boolean {
        val error = _lastError.value ?: return false
        return error.contains(IcmErrorCodes.REGION_UNAVAILABLE)
    }

    /** Check if last error is rate limited (429). */
    fun isRateLimited(): Boolean {
        val error = _lastError.value ?: return false
        return error.contains(IcmErrorCodes.RATE_LIMITED)
    }

    /** Check if last error is query too short (400). */
    fun isQueryTooShort(): Boolean {
        val error = _lastError.value ?: return false
        return error.contains(IcmErrorCodes.QUERY_TOO_SHORT)
    }

    /** Check if last error is query spam detected (429). */
    fun isQuerySpamDetected(): Boolean {
        val error = _lastError.value ?: return false
        return error.contains(IcmErrorCodes.QUERY_SPAM_DETECTED)
    }

    /** Check if last error is source not allowed (403). */
    fun isSourceNotAllowed(): Boolean {
        val error = _lastError.value ?: return false
        return error.contains(IcmErrorCodes.SOURCE_NOT_ALLOWED)
    }

    /** Check if last error is early access (presave). */
    fun isEarlyAccess(): Boolean {
        val error = _lastError.value ?: return false
        return error.contains(IcmErrorCodes.EARLY_ACCESS)
    }

    /** Get recommended region from last error (451 region_unavailable). */
    fun getRequiredRegion(): String? {
        val ex = _lastException as? IcmApiException ?: return null
        return ex.requiredRegion
    }

    /** Get retry-after from last error 429 (rate_limited). */
    fun getRetryAfter(): Int? {
        val ex = _lastException as? IcmApiException ?: return null
        return ex.retryAfter
    }

    /** Get error code from last error. */
    fun getLastErrorCode(): String? {
        val ex = _lastException as? IcmApiException ?: return null
        return ex.errorCode
    }

    /** Get source from last error (403 source_not_allowed). */
    fun getLastErrorSource(): String? {
        val ex = _lastException as? IcmApiException ?: return null
        return ex.source
    }

    /** Get full HTTP code from last error. */
    fun getLastHttpCode(): Int? {
        val ex = _lastException as? IcmApiException ?: return null
        return ex.code
    }

    /** Clear last error. */
    fun clearError() {
        _lastException = null
        _lastError.value = null
    }

    /**
     * Поиск треков по жанру для "Моей волны".
     * Используется когда нужно дополнить очередь треками конкретного жанра.
     */
    suspend fun searchTracksByGenre(genre: String, limit: Int = 10): List<Track> {
        return try {
            // Используем существующий searchAll с фильтрацией по жанру
            val results = searchAll(genre, limit = limit * 2)
            results?.items?.map { item ->
                Track(
                    id = item.id,
                    title = item.title,
                    artist = item.displayArtist,
                    albumName = item.album ?: "",
                    uri = android.net.Uri.parse("https://byicloud.online/track/${item.id}"),
                    durationMs = item.durationMs,
                    albumId = item.collectionId?.hashCode()?.toLong() ?: -1L,
                    coverUrl = item.cover,
                    isExplicit = item.isExplicit,
                    isCustom = item.isCustom,
                    source = item.source,
                    genre = genre // присваиваем жанр из запроса
                )
            }?.take(limit) ?: emptyList()
        } catch (e: Exception) {
            _lastException = e
            _lastError.value = e.message
            emptyList()
        }
    }
}
