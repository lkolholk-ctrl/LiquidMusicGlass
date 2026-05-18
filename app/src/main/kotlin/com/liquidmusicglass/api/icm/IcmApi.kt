package com.liquidmusicglass.api.icm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
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
        } ?: apiKey?.let {
            builder.header("X-Partner-Key", it)
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
                        Result.failure(IcmApiException(
                            response.code,
                            errorText,
                            error?.error,
                            error?.requiredRegion,
                            error?.retryAfter,
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
     * Check session status and refresh token if needed.
     */
    suspend fun refreshSessionIfNeeded(
        partnerUserId: String,
        hideExplicit: Boolean = false
    ): Result<IcmSessionResponse> {
        val currentToken = sessionToken
        if (currentToken != null) {
            return Result.failure(IcmApiException(401, "Token refresh needed"))
        }
        return issueSession(partnerUserId, hideExplicit)
    }

    /**
     * Search tracks, albums, and artists.
     * @param query Search string (up to 200 chars, min 2 alphanumeric)
     * @param region Region (us/ru/nz), null uses defaultRegion
     * @param source Music source: "apple" (default), "vk", "all". See VK Music docs.
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
            if (!source.isNullOrBlank()) append("&source=$source")
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
     * GET /lyrics?track_id={trackId}
     */
    suspend fun getLyrics(trackId: String): Result<IcmLyricsResponse> {
        return execute("/lyrics?track_id=$trackId")
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
     * Generate URL for linking user account to ICM.
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
    //  Library (likes, subscriptions)
    //  Requires X-Partner-User-Id header and linked user
    // ═══════════════════════════════════════════════════════════

    /**
     * Get user's liked tracks.
     * Requires partnerUserId to be set.
     */
    suspend fun getLibraryLikes(): Result<IcmLibraryLikesResponse> {
        return execute("/library/likes")
    }

    /**
     * Get user's artist subscriptions.
     * Requires partnerUserId to be set.
     */
    suspend fun getLibrarySubscriptions(): Result<IcmLibrarySubscriptionsResponse> {
        return execute("/library/subscriptions")
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
