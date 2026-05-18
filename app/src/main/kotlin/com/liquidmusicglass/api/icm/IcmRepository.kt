package com.liquidmusicglass.api.icm

import com.liquidmusicglass.engine.Track
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

    private var _lastException: Exception? = null

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
    }

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
        if (query.isBlank()) return emptyList()
        val result = api.search(query, region, source, limit)
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
        val result = api.search(query, region, source, limit)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
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

    /**
     * Get signed stream URL.
     */
    suspend fun getStreamUrl(trackId: String, region: String? = null): String? {
        val result = api.getTrack(trackId, region)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.url
    }

    /**
     * Get full TrackResponse (including expires_at).
     */
    suspend fun getTrackInfo(trackId: String, region: String? = null): IcmTrackResponse? {
        val result = api.getTrack(trackId, region)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
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
     * Get user's liked tracks from library.
     * Requires partnerUserId to be set and user to be linked.
     */
    suspend fun getLibraryLikes(): IcmLibraryLikesResponse? {
        val result = api.getLibraryLikes()
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Get user's artist subscriptions from library.
     * Requires partnerUserId to be set and user to be linked.
     */
    suspend fun getLibrarySubscriptions(): IcmLibrarySubscriptionsResponse? {
        val result = api.getLibrarySubscriptions()
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
     */
    suspend fun getWaveNext(seedTrackId: String? = null): IcmWaveResponse? {
        val result = api.getWaveNext(seedTrackId)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Build a wave queue by calling API multiple times.
     * Recommended: preload 3-5 tracks for seamless playback.
     */
    suspend fun buildWaveQueue(count: Int = 5, seedTrackId: String? = null): List<com.liquidmusicglass.engine.Track> {
        val tracks = mutableListOf<com.liquidmusicglass.engine.Track>()
        repeat(count) {
            val response = getWaveNext(seedTrackId)
            val track = response?.track?.toTrack()
            if (track != null) tracks.add(track)
        }
        return tracks
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
        quality: String? = null,
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
        quality: String? = null,
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
            delay(pending.pollAfterSeconds * 1000L)
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
            delay(pending.pollAfterSeconds * 1000L)
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
}
