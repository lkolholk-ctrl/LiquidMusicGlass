package com.liquidmusicglass.api.icm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * ICM Music Partner API client.
 * Documentation: https://byicloud.online/partners/api-docs
 *
 * Uses API key (X-Partner-Key) or session token (Authorization: Bearer).
 *
 * Get key: https://byicloud.online/partners
 */
class IcmApi private constructor() {

    companion object {
        const val BASE_URL = "https://byicloud.online/api/partner"

        @Volatile
        private var instance: IcmApi? = null

        fun getInstance(): IcmApi {
            return instance ?: synchronized(this) {
                instance ?: IcmApi().also { instance = it }
            }
        }

        fun resetInstance() {
            instance = null
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
        .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
        .certificatePinner(
            CertificatePinner.Builder()
                .add("byicloud.online", "sha256/2i/FBT2COdMdWfsx9OzKJt/iyOR4QNSfLavhUxAR2Jc=")
                .build()
        )
        .build()

    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    /** Partner API key (pk_<id>_<random>) */
    var apiKey: String? = null

    /** Session token (JWT) — alternative to apiKey for client requests */
    var sessionToken: String? = null

    /** Default search region */
    var defaultRegion: String = "us"

    /** Stream quality: "128K", "256K", "320K", "ALAC" or null for default */
    var streamQuality: String? = IcmStreamQuality.K256

    /** Partner user id for analytics and per-user settings (X-Partner-User-Id) */
    var partnerUserId: String? = null

    /** Callback for X-Request-Id tracing */
    var onRequestId: ((String) -> Unit)? = null

    private inline fun <reified T> parseResponse(body: okhttp3.ResponseBody?): T {
        val text = body?.string() ?: throw IcmApiException(0, "Empty response body")
        return json.decodeFromString(text)
    }

    private fun extractRequestId(response: okhttp3.Response): String? {
        return response.header("X-Request-Id")
    }

    private fun buildRequest(url: String, method: String = "GET", body: String? = null): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "LiquidMusicGlass/1.0")
            .header("Accept", "application/json")

        // Auth: session token takes priority
        sessionToken?.let {
            builder.header("Authorization", "Bearer $it")
        } ?: run {
            // Dynamic partner key — NEVER hardcoded
            val partnerKey = com.liquidmusicglass.api.icm.IcmAuthRepository.getPartnerKey()
            if (partnerKey.isNotBlank()) {
                builder.header("X-Partner-Key", partnerKey)
            } else {
                // Fallback to apiKey field (legacy compat)
                apiKey?.let { key -> builder.header("X-Partner-Key", key) }
            }
        }

        partnerUserId?.let {
            builder.header("X-Partner-User-Id", it)
        }

        if (body != null) {
            val requestBody = body.toRequestBody(mediaTypeJson)
            builder.method(method, requestBody)
        } else if (method != "GET") {
            builder.method(method, "".toRequestBody(null))
        }

        return builder.build()
    }

    private suspend inline fun <reified T> execute(
        endpoint: String,
        method: String = "GET",
        body: String? = null,
        async: Boolean = false
    ): Result<T> {
        return withContext(Dispatchers.IO) {
            try {
                val url = if (async) "$BASE_URL$endpoint?async=1" else "$BASE_URL$endpoint"
                val request = buildRequest(url, method, body)
                val response = client.newCall(request).execute()

                extractRequestId(response)?.let { onRequestId?.invoke(it) }

                when {
                    response.isSuccessful -> {
                        Result.success(parseResponse<T>(response.body))
                    }
                    response.code == 202 -> {
                        // Async pending — parse as pending response
                        val pending = parseResponse<IcmAsyncTrackPending>(response.body)
                        Result.failure(IcmAsyncPendingException(pending))
                    }
                    else -> {
                        val errorText = response.body?.string() ?: "HTTP ${response.code}"
                        val error = try {
                            json.decodeFromString<IcmError>(errorText)
                        } catch (_: Exception) {
                            null
                        }
                        // Prefer the canonical HTTP Retry-After header, fall back to body field.
                        val retryAfterHeader = response.header("Retry-After")?.toIntOrNull()
                        Result.failure(IcmApiException(
                            response.code,
                            errorText,
                            error?.error,
                            error?.requiredRegion,
                            retryAfterHeader ?: error?.retryAfter,
                            error?.source
                        ))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════

    /**
     * Check API health and key configuration.
     */
    suspend fun health(): Result<IcmHealthResponse> = execute("/health")

    /**
     * Issue session token for client requests.
     * Requires apiKey.
     * Call after Telegram auth to get JWT.
     */
    suspend fun issueSession(
        partnerUserId: String,
        hideExplicit: Boolean = false
    ): Result<IcmSessionResponse> {
        val body = json.encodeToString(
            IcmSessionRequest(partnerUserId = partnerUserId, hideExplicit = hideExplicit)
        )
        return execute("/session/issue", method = "POST", body = body)
    }

    /**
     * Issue a fresh session token if no live one is cached, otherwise just
     * return a stub success with the existing token information. Per docs
     * 12.1, `partner_session_token` is cached locally up to `expires_in`,
     * so we only mint a new one when we don't already have one.
     */
    suspend fun refreshSessionIfNeeded(
        partnerUserId: String,
        hideExplicit: Boolean = false
    ): Result<IcmSessionResponse> {
        if (sessionToken != null) {
            return Result.success(
                IcmSessionResponse(
                    partnerSessionToken = sessionToken!!,
                    expiresIn = 0,
                    partnerUserId = partnerUserId,
                    scopes = emptyList()
                )
            )
        }
        return issueSession(partnerUserId, hideExplicit).also { result ->
            result.getOrNull()?.let { sessionToken = it.partnerSessionToken }
        }
    }

/**
 * Search tracks, albums, and artists.
 * @param query Search string (up to 200 chars, min 2 alphanumeric)
 * @param region Region (us/ru/nz), null uses defaultRegion
 * @param source Music source: "primary" (default), "secondary", "all". Per ICM API docs.
 * @param limit Max results (clamped to partner.config.search.max_results)
 * @return Search response with mixed items (artists, albums, tracks)
 */
suspend fun search(
    query: String,
    region: String? = null,
    source: String? = null,
    limit: Int? = null
): Result<IcmSearchResponse> {
    val r = region ?: defaultRegion
    val encQuery = java.net.URLEncoder.encode(query, "UTF-8")
    val params = buildString {
        append("?q=$encQuery")
        append("&region=$r")
        // Normalize legacy source names to ICM API values
        val normalizedSource = when (source) {
            "apple", "primary" -> "primary"
            "vk", "secondary" -> "secondary"
            "all" -> "all"
            else -> null
        }
        if (normalizedSource != null) append("&source=$normalizedSource")
        if (limit != null && limit > 0) append("&limit=$limit")
    }
    return execute("/search$params")
}

    /**
     * Get signed playback URL for a track.
     * @param trackId Track ID from search/album
     * @param region Region
     * @param quality Quality: "256K", "128K" or null
     * @return IcmTrackResponse with url field for streaming
     */
    suspend fun getTrack(
        trackId: String,
        region: String? = null,
        quality: String? = streamQuality
    ): Result<IcmTrackResponse> {
        val body = json.encodeToString(
            IcmTrackRequest(
                trackId = trackId,
                region = region ?: defaultRegion,
                quality = quality
            )
        )
        return execute("/track", method = "POST", body = body)
    }

    /**
     * Album info + track list.
     */
    suspend fun getAlbum(
        albumId: String,
        region: String? = null
    ): Result<IcmAlbumResponse> {
        val r = region ?: defaultRegion
        return execute("/album/$albumId?region=$r")
    }

    /**
     * Artist info: top tracks, albums, similar artists.
     */
    suspend fun getArtist(
        artistId: String,
        region: String? = null
    ): Result<IcmArtistResponse> {
        val r = region ?: defaultRegion
        return execute("/artist/$artistId?region=$r")
    }

    /**
     * Track metadata (without playback URL).
     */
    suspend fun getTrackMeta(trackId: String): Result<IcmTrackMeta> =
        execute("/track/$trackId/meta")

    /**
     * Editorial Apple Music playlist (Today Hits, etc).
     * ICM API uses the same /album/{id} endpoint for playlists (id starts with pl.).
     */
    suspend fun getPlaylist(
        playlistId: String,
        region: String? = null
    ): Result<IcmAlbumResponse> {
        val r = region ?: defaultRegion
        return execute("/album/$playlistId?region=$r")
    }

    /**
     * Sign cover URL (for custom covers).
     * Apple Music covers don't need signing — use URL directly.
     */
    suspend fun signCover(fileId: String): Result<IcmCoverSignResponse> {
        val encFileId = java.net.URLEncoder.encode(fileId, "UTF-8")
        return execute("/cover-sign?file_id=$encFileId")
    }

    /**
     * Song lyrics.
     * Primary: GET /track/{id}/lyrics?region={region}
     * Mirror:  GET /lyrics?track_id={trackId}
     */
    suspend fun getLyrics(trackId: String, region: String? = null): Result<IcmLyricsResponse> {
        val r = region ?: defaultRegion
        return execute("/track/$trackId/lyrics?region=$r")
    }

    // ═══════════════════════════════════════════════════════════
    //  Batch & Async
    // ═══════════════════════════════════════════════════════════

    /**
     * Batch track metadata — up to 50 per request.
     */
    suspend fun getBatchTrackMeta(trackIds: List<String>): Result<IcmBatchTrackMetaResponse> {
        if (trackIds.isEmpty()) return Result.failure(IllegalArgumentException("trackIds must not be empty"))
        if (trackIds.size > 50) return Result.failure(IllegalArgumentException("trackIds max 50, got ${trackIds.size}"))
        val body = json.encodeToString(IcmBatchTrackMetaRequest(trackIds = trackIds))
        return execute("/tracks/meta", method = "POST", body = body)
    }

    /**
     * Get track in async mode.
     * If track is cold — returns 202 with job_id for polling.
     */
    suspend fun getTrackAsync(
        trackId: String,
        region: String? = null,
        quality: String? = streamQuality
    ): Result<IcmTrackResponse> {
        val body = json.encodeToString(
            IcmTrackRequest(
                trackId = trackId,
                region = region ?: defaultRegion,
                quality = quality
            )
        )
        return execute("/track", method = "POST", body = body, async = true)
    }

    /**
     * Check async job status.
     */
    suspend fun pollAsyncJob(jobId: String): Result<IcmAsyncTrackReady> {
        return execute("/track/job/$jobId")
    }

    // ═══════════════════════════════════════════════════════════
    //  Account Linking
    // ═══════════════════════════════════════════════════════════

    /**
     * Generate URL for linking user account to ICM via Telegram.
     * @param partnerUserId User ID in your system
     * @param redirectUri Callback URI after authorization
     * @param state Random string for CSRF protection
     */
    fun buildAccountLinkUrl(
        partnerId: String,
        partnerUserId: String,
        redirectUri: String,
        state: String
    ): String {
        val encRedirect = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        val encState = java.net.URLEncoder.encode(state, "UTF-8")
        val encUserId = java.net.URLEncoder.encode(partnerUserId, "UTF-8")
        return "https://byicloud.online/partner/$partnerId/link?partner_user_id=$encUserId&redirect_uri=$encRedirect&state=$encState"
    }

    /**
     * Parse callback from ICM after linking.
     */
    fun parseAccountLinkCallback(
        state: String,
        linked: Boolean,
        icmUserId: String? = null,
        error: String? = null
    ): IcmAccountLinkCallback {
        return IcmAccountLinkCallback(
            state = state,
            linked = linked,
            icmUserId = icmUserId,
            error = error
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  Email Account Linking (S2S only, requires X-Partner-Key)
    // ═══════════════════════════════════════════════════════════

    /**
     * Request email OTP for account linking.
     * Auto-registers new ICM account if email doesn't exist.
     * S2S only — requires X-Partner-Key.
     */
    suspend fun requestEmailLink(
        partnerUserId: String,
        email: String,
        state: String? = null
    ): Result<IcmEmailLinkResponse> {
        val body = json.encodeToString(IcmEmailLinkRequest(
            partnerUserId = partnerUserId,
            email = email,
            state = state
        ))
        return execute("/link/email/request", method = "POST", body = body)
    }

    /**
     * Verify email OTP and link account.
     */
    suspend fun verifyEmailLink(
        nonce: String,
        otp: String
    ): Result<IcmEmailVerifyResponse> {
        val body = json.encodeToString(IcmEmailVerifyRequest(nonce = nonce, otp = otp))
        return execute("/link/email/verify", method = "POST", body = body)
    }

    /**
     * Change password for linked user.
     * S2S only. User must be linked to YOUR partner_id.
     */
    suspend fun changePassword(
        partnerUserId: String,
        currentPassword: String,
        newPassword: String
    ): Result<IcmPasswordChangeResponse> {
        val body = json.encodeToString(IcmPasswordChangeRequest(
            partnerUserId = partnerUserId,
            currentPassword = currentPassword,
            newPassword = newPassword
        ))
        return execute("/link/email/password/change", method = "POST", body = body)
    }

    /**
     * Reset password for linked user.
     * S2S only. New password sent to user's email.
     */
    suspend fun resetPassword(
        partnerUserId: String
    ): Result<IcmPasswordResetResponse> {
        val body = json.encodeToString(IcmPasswordResetRequest(partnerUserId = partnerUserId))
        return execute("/link/email/password/reset", method = "POST", body = body)
    }

    // ═══════════════════════════════════════════════════════════
    //  Library (likes, subscriptions)
    //  Requires X-Partner-User-Id header and linked user
    // ═══════════════════════════════════════════════════════════

    /**
     * Get user's liked tracks.
     * Requires partnerUserId to be set.
     */
    suspend fun getLibraryLikes(
        source: String? = null,
        limit: Int? = null,
        offset: Int? = null
    ): Result<IcmLibraryLikesResponse> {
        val params = buildString {
            val query = mutableListOf<String>()
            if (!source.isNullOrBlank()) query.add("source=$source")
            if (limit != null) query.add("limit=$limit")
            if (offset != null) query.add("offset=$offset")
            if (query.isNotEmpty()) append("?${query.joinToString("&")}")
        }
        return execute("/library/likes$params")
    }

    /**
     * Get user's artist subscriptions.
     * Requires partnerUserId to be set.
     */
    suspend fun getLibrarySubscriptions(
        source: String? = null,
        limit: Int? = null,
        offset: Int? = null
    ): Result<IcmLibrarySubscriptionsResponse> {
        val params = buildString {
            val query = mutableListOf<String>()
            if (!source.isNullOrBlank()) query.add("source=$source")
            if (limit != null) query.add("limit=$limit")
            if (offset != null) query.add("offset=$offset")
            if (query.isNotEmpty()) append("?${query.joinToString("&")}")
        }
        return execute("/library/subscriptions$params")
    }

    /**
     * Get next track from user's personal wave (radio).
     * Requires partnerUserId to be set and user to be linked.
     * Call repeatedly to get continuous stream of personalized tracks.
     *
     * @param seedTrackId Optional track ID to create a "station based on track"
     * @param exclude Comma-separated track IDs to exclude (current queue)
     * @param recentSkips Number of consecutive skipped tracks (skip-streak fallback)
     * @param region Region override
     */
    suspend fun getWaveNext(
        seedTrackId: String? = null,
        exclude: List<String>? = null,
        recentSkips: Int? = null,
        region: String? = null,
        source: String? = null
    ): Result<IcmWaveResponse> {
        if (partnerUserId.isNullOrBlank()) {
            return Result.failure(
                IcmApiException(401, "partner_user_id is required for /library/wave/next")
            )
        }
        val params = buildString {
            append("/library/wave/next")
            val query = mutableListOf<String>()
            if (!seedTrackId.isNullOrBlank()) {
                query.add("seed_track_id=${java.net.URLEncoder.encode(seedTrackId, "UTF-8")}")
            }
            if (!exclude.isNullOrEmpty()) {
                val joined = exclude.joinToString(",")
                query.add("exclude=${java.net.URLEncoder.encode(joined, "UTF-8")}")
            }
            if (recentSkips != null) query.add("recent_skips=$recentSkips")
            if (region != null) query.add("region=$region")
            if (source != null) query.add("source=$source")
            if (query.isNotEmpty()) append("?${query.joinToString("&")}")
        }
        return execute(params)
    }

    /**
     * Send feedback about wave track.
     * feedback_type: less_track / less_artist / less_genre / more_track / more_artist / more_genre
     * value: track ID, artist ID, or genre name
     */
    suspend fun sendWaveFeedback(
        feedbackType: String,
        value: String
    ): Result<IcmWaveFeedbackResponse> {
        val body = json.encodeToString(IcmWaveFeedbackRequest(feedbackType = feedbackType, value = value))
        return execute("/library/wave/feedback", method = "POST", body = body)
    }

    /**
     * Reset wave history, seed artists, and preferences.
     * Likes are preserved.
     */
    suspend fun resetWave(): Result<IcmWaveFeedbackResponse> {
        return execute("/library/wave/reset", method = "POST")
    }

    /**
     * Get popular artists for wave onboarding.
     * Does NOT require partnerUserId — can be called before linking.
     * Cached 24h on server.
     */
    suspend fun getWavePopularArtists(): Result<List<IcmWaveOnboardingArtist>> {
        return execute("/library/wave/popular-artists")
    }

    /**
     * Check wave onboarding status.
     */
    suspend fun getWaveOnboarding(): Result<IcmWaveOnboardingResponse> {
        return execute("/library/wave/onboarding")
    }

    /**
     * Save user's artist selection for wave onboarding.
     * Minimum 1 artist, recommended 3-5.
     */
    suspend fun saveWaveOnboarding(
        artists: List<IcmWaveOnboardingArtistSave>
    ): Result<IcmWaveOnboardingSaveResponse> {
        val body = json.encodeToString(IcmWaveOnboardingSaveRequest(artists = artists))
        return execute("/library/wave/onboarding", method = "POST", body = body)
    }

    /**
     * Log playback event for wave ranking improvement.
     * Called when user finishes/skips/switches a wave track.
     */
    suspend fun logWavePlayback(
        trackId: String,
        playedSeconds: Double,
        totalSeconds: Double? = null,
        completed: Boolean? = null,
        skipped: Boolean? = null
    ): Result<IcmWavePlaybackResponse> {
        val body = json.encodeToString(
            IcmWavePlaybackRequest(
                trackId = trackId,
                playedSeconds = playedSeconds,
                totalSeconds = totalSeconds,
                completed = completed,
                skipped = skipped
            )
        )
        return execute("/library/wave/playback", method = "POST", body = body)
    }

    // ═══════════════════════════════════════════════════════════
    //  Personal Cabinet (/me/*) — requires linked user + subscription
    // ═══════════════════════════════════════════════════════════

    /**
     * Get user's preferred stream quality (legacy endpoint).
     * Prefer [getUserPreferences] going forward.
     */
    suspend fun getUserQuality(): Result<IcmUserQualityResponse> {
        return execute("/me/quality")
    }

    /**
     * Set user's preferred stream quality (legacy endpoint).
     * Prefer [updateUserPreferences].
     */
    suspend fun setUserQuality(quality: String): Result<IcmUserQualityResponse> {
        val body = json.encodeToString(IcmUserQualityRequest(quality = quality))
        return execute("/me/quality", method = "POST", body = body)
    }

    /**
     * Get current user preferences (quality, region, hide_explicit, source).
     * Requires linked user with active subscription.
     */
    suspend fun getUserPreferences(): Result<IcmUserPreferences> {
        return execute("/me/preferences")
    }

    /**
     * Update user preferences. Only non-null fields in [prefs] are sent.
     */
    suspend fun updateUserPreferences(prefs: IcmUserPreferences): Result<IcmUserPreferences> {
        val body = json.encodeToString(prefs)
        return execute("/me/preferences", method = "PUT", body = body)
    }

    /**
     * Get user profile (icm_user_id, email, subscription).
     */
    suspend fun getUserProfile(): Result<IcmUserProfile> {
        return execute("/me/profile")
    }
}

/**
 * API exception with HTTP code.
 */
class IcmApiException(
    val code: Int,
    override val message: String,
    val errorCode: String? = null,
    val requiredRegion: String? = null,
    val retryAfter: Int? = null,
    val source: String? = null
) : Exception("HTTP $code: $message")

/**
 * Async pending exception — track is still being prepared.
 */
class IcmAsyncPendingException(
    val pending: IcmAsyncTrackPending
) : Exception("Track pending: job ${pending.jobId}, poll after ${pending.pollAfterSeconds}s")
