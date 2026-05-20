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

    private val _lastApiException = MutableStateFlow<IcmApiException?>(null)
    val lastApiException: StateFlow<IcmApiException?> = _lastApiException

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
    }

    /** Update the session token (used after /session/issue or Telegram OAuth). */
    fun setSessionToken(sessionToken: String?) {
        api.sessionToken = sessionToken
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

    // ═══════════════════════════════════════════════════════════
    //  Home Screen Content (Banners, New Releases, Charts)
    // ═══════════════════════════════════════════════════════════

    /**
     * Load home screen content blocks.
     * Since the backend does not have a dedicated /home endpoint,
     * we construct blocks from available APIs:
     * - Banners: search tracks with popular queries
     * - New Releases: search albums with "new" query
     * - Charts: search tracks with trending queries
     * - Recommendations: wave next tracks (if user is linked)
     */
    suspend fun loadHomeContent(): IcmHomeResponse {
        val blocks = mutableListOf<IcmHomeBlock>()

        // ─── Banners: popular tracks ───
        val bannerQueries = listOf("top hits", "popular", "trending")
        val bannerItems = mutableListOf<IcmHomeItem>()
        for (query in bannerQueries) {
            if (bannerItems.size >= 6) break
            val result = searchAll(query, limit = 5, source = IcmSearchSource.ALL)
            result?.items
                ?.filter { it.isTrack }
                ?.take(6 - bannerItems.size)
                ?.forEach { item ->
                    bannerItems.add(
                        IcmHomeItem(
                            id = item.id,
                            title = item.title,
                            artist = item.displayArtist,
                            artistId = item.artistId,
                            cover = item.cover,
                            duration = item.duration,
                            source = item.source,
                            genre = query
                        )
                    )
                }
        }
        if (bannerItems.isNotEmpty()) {
            blocks.add(
                IcmHomeBlock(
                    id = "banners",
                    title = "Featured",
                    type = "banner",
                    items = bannerItems
                )
            )
        }

        // ─── New Releases: albums ───
        val newReleaseQueries = listOf("new releases", "new music", "latest")
        val newReleaseItems = mutableListOf<IcmHomeItem>()
        for (query in newReleaseQueries) {
            if (newReleaseItems.size >= 10) break
            val result = searchAll(query, limit = 10, source = IcmSearchSource.ALL)
            result?.items
                ?.filter { it.isAlbum || it.collectionId != null }
                ?.take(10 - newReleaseItems.size)
                ?.forEach { item ->
                    newReleaseItems.add(
                        IcmHomeItem(
                            id = item.id,
                            title = item.title,
                            artist = item.displayArtist,
                            artistId = item.artistId,
                            cover = item.cover,
                            collectionId = item.collectionId,
                            album = item.album,
                            source = item.source
                        )
                    )
                }
        }
        if (newReleaseItems.isNotEmpty()) {
            blocks.add(
                IcmHomeBlock(
                    id = "new_releases",
                    title = "New Releases",
                    type = "new_releases",
                    items = newReleaseItems
                )
            )
        }

        // ─── Charts: trending tracks with rank ───
        val chartQueries = listOf("top 100", "chart", "hot", "viral")
        val chartItems = mutableListOf<IcmHomeItem>()
        var rank = 1
        for (query in chartQueries) {
            if (chartItems.size >= 15) break
            val result = searchAll(query, limit = 10, source = IcmSearchSource.ALL)
            result?.items
                ?.filter { it.isTrack }
                ?.take(15 - chartItems.size)
                ?.forEach { item ->
                    chartItems.add(
                        IcmHomeItem(
                            id = item.id,
                            title = item.title,
                            artist = item.displayArtist,
                            artistId = item.artistId,
                            cover = item.cover,
                            duration = item.duration,
                            source = item.source,
                            rank = rank++
                        )
                    )
                }
        }
        if (chartItems.isNotEmpty()) {
            blocks.add(
                IcmHomeBlock(
                    id = "charts",
                    title = "Top Charts",
                    type = "charts",
                    items = chartItems
                )
            )
        }

        // ─── Recommendations: wave tracks (if linked) ───
        if (api.partnerUserId != null) {
            val waveItems = mutableListOf<IcmHomeItem>()
            repeat(5) {
                val response = getWaveNext(
                    seedTrackId = waveItems.firstOrNull()?.id,
                    exclude = waveItems.map { it.id }.takeIf { it.isNotEmpty() },
                    recentSkips = 0
                )
                if (response != null && response.status == "ok" && response.track != null) {
                    waveItems.add(
                        IcmHomeItem(
                            id = response.track.id,
                            title = response.track.title,
                            artist = response.track.artist ?: "Unknown Artist",
                            cover = response.track.cover,
                            duration = response.track.durationMs,
                            source = "wave"
                        )
                    )
                }
            }
            if (waveItems.isNotEmpty()) {
                blocks.add(
                    IcmHomeBlock(
                        id = "recommendations",
                        title = "Made For You",
                        type = "recommendations",
                        items = waveItems
                    )
                )
            }
        }

        return IcmHomeResponse(blocks = blocks)
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
    suspend fun getTrackInfo(trackId: String, region: String? = null, quality: String? = null): IcmTrackResponse? {
        val result = api.getTrack(trackId, region, quality)
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
     * Get user's liked tracks from library.
     * Requires partnerUserId to be set and user to be linked.
     */
    suspend fun getLibraryLikes(
        source: String? = null,
        limit: Int? = null,
        offset: Int? = null
    ): IcmLibraryLikesResponse? {
        val result = api.getLibraryLikes(source, limit, offset)
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
     * @param exclude Track IDs to exclude (current queue)
     * @param recentSkips Number of consecutive skips (skip-streak fallback)
     * @param region Region override
     */
    suspend fun getWaveNext(
        seedTrackId: String? = null,
        exclude: List<String>? = null,
        recentSkips: Int? = null,
        region: String? = null,
        source: String? = null
    ): IcmWaveResponse? {
        val result = api.getWaveNext(seedTrackId, exclude, recentSkips, region, source)
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
        repeat(count) {
            val response = getWaveNext(seedTrackId, exclude.takeIf { it.isNotEmpty() }) ?: return@repeat
            if (response.status == "empty") return@repeat
            val track = response.track ?: return@repeat
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

    /**
     * Reset wave history and preferences.
     */
    suspend fun resetWave(): Boolean {
        val result = api.resetWave()
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.ok == true
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
        val result = api.getUserPreferences()
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
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
}
