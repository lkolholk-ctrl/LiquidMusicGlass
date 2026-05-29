package com.liquidmusicglass.api.icm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// ─── Health ───

@Serializable
data class IcmLinkedUser(
    @SerialName("icm_user_id") val icmUserId: Long,
    @SerialName("subscription_upgrade") val subscriptionUpgrade: Boolean,
    @SerialName("effective_stream") val effectiveStream: IcmStreamConfig
)

@Serializable
data class IcmHealthResponse(
    @SerialName("partner_id") val partnerId: String,
    val status: String,
    val scopes: List<String> = emptyList(),
    @SerialName("rate_limits") val rateLimits: IcmRateLimits? = null,
    val stream: IcmStreamConfig? = null,
    val search: IcmSearchConfig? = null,
    @SerialName("server_time") val serverTime: Long? = null,
    @SerialName("linked_user") val linkedUser: IcmLinkedUser? = null
)

@Serializable
data class IcmRateLimits(
    val search: IcmRateLimit? = null,
    val stream: IcmRateLimit? = null,
    @SerialName("session_issue") val sessionIssue: IcmRateLimit? = null,
    val default: IcmRateLimit? = null
)

@Serializable
data class IcmRateLimit(
    val rpm: Int,
    val burst: Int
)

@Serializable
data class IcmStreamConfig(
    @SerialName("max_quality") val maxQuality: String,
    @SerialName("allowed_sources") val allowedSources: List<String>,
    @SerialName("signed_url_ttl_seconds") val signedUrlTtlSeconds: Int
)

@Serializable
data class IcmSearchConfig(
    @SerialName("max_results") val maxResults: Int,
    @SerialName("regions_allowed") val regionsAllowed: List<String>
)

// ─── Session ───

@Serializable
data class IcmSessionRequest(
    @SerialName("partner_user_id") val partnerUserId: String,
    @SerialName("hide_explicit") val hideExplicit: Boolean = false
)

@Serializable
data class IcmSessionResponse(
    @SerialName("partner_session_token") val partnerSessionToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("partner_user_id") val partnerUserId: String,
    val scopes: List<String>
)

// ─── Search ───

@Serializable
data class IcmSearchResponse(
    val query: String,
    val region: String,
    val source: String? = null,
    val items: List<IcmSearchItem>
)

@Serializable
data class IcmSearchItem(
    val id: String,
    val title: String,
    val artist: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("artistId") val artistId: String? = null,
    val artists: List<IcmMiniArtist> = emptyList(),
    val cover: String? = null,
    val preview: String? = null,
    @SerialName("collectionId") val collectionId: String? = null,
    val album: String? = null,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
    val region: String? = null,
    @SerialName("isArtist") val isArtist: Boolean = false,
    @SerialName("isAlbum") val isAlbum: Boolean = false,
    @SerialName("isCustom") val isCustom: Boolean = false,
    val duration: Long? = null,
    val source: String? = null,
    @SerialName("trackId") val trackId: String? = null
) {
    val displayArtist: String
        get() = artist?.takeIf { it.isNotBlank() && it != "Исполнитель" }
            ?: artistName?.takeIf { it.isNotBlank() && it != "Исполнитель" }
            ?: title.takeIf { isArtist }
            ?: "Unknown Artist"

    /** VK returns duration in seconds, Apple in milliseconds. Normalized to ms. */
    val durationMs: Long
        get() {
            val d = duration ?: return 0L
            return if (d < 1000L) d * 1000L else d
        }

    val isTrack: Boolean
        get() = !isArtist && !isAlbum

    val isVk: Boolean
        get() = id.startsWith("vk_") || source == "vk"
}

// ─── Track (Playback URL) ───

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class IcmTrackRequest(
    @SerialName("trackId") val trackId: String,
    val region: String = "us",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val quality: String? = null
)

@Serializable
data class IcmTrackResponse(
    @SerialName("track_id") val trackId: String,
    @SerialName("file_id") val fileId: String? = null,
    val source: String,
    val quality: String,
    @SerialName("artist_id") val artistId: String? = null,
    val url: String,
    @SerialName("expires_at") val expiresAt: Long
)

// ─── Album ───

@Serializable
data class IcmAlbumResponse(
    val album: IcmAlbum,
    val tracks: List<IcmAlbumTrack>
)

@Serializable
data class IcmAlbum(
    val id: String,
    val title: String,
    val artist: String,
    @SerialName("artistId") val artistId: String? = null,
    val cover: String,
    @SerialName("motionCoverUrl") val motionCoverUrl: String? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    val year: String? = null,
    val type: String? = null,
    val description: String? = null,
    @SerialName("trackCount") val trackCount: Int? = null
)

@Serializable
data class IcmAlbumTrack(
    val id: String,
    val title: String,
    val artist: String,
    @SerialName("artistId") val artistId: String? = null,
    val cover: String,
    @SerialName("collectionId") val collectionId: String? = null,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
    @SerialName("isCustom") val isCustom: Boolean = false,
    val region: String? = null,
    @SerialName("trackNumber") val trackNumber: Int? = null,
    val duration: Long? = null,
    val source: String? = null
) {
    val durationMs: Long
        get() {
            val d = duration ?: return 0L
            return if (d < 1000L) d * 1000L else d
        }
}

// ─── Artist ───

@Serializable
data class IcmArtistResponse(
    val id: String,
    val name: String,
    val genre: String? = null,
    val url: String? = null,
    val image: String? = null,
    val cover: String? = null,
    val bio: String? = null,
    val followers: Long? = null,
    @SerialName("editorialVideoUrl") val editorialVideoUrl: String? = null,
    @SerialName("topSongs") val topSongs: List<IcmArtistSong> = emptyList(),
    @SerialName("latestRelease") val latestRelease: IcmArtistAlbum? = null,
    val albums: List<IcmArtistAlbum> = emptyList(),
    val singles: List<IcmArtistAlbum> = emptyList(),
    val featuring: List<IcmArtistAlbum> = emptyList(),
    @SerialName("similarArtists") val similarArtists: List<IcmSimilarArtist> = emptyList(),
    val playlists: List<IcmArtistPlaylist> = emptyList(),
    @SerialName("appearsOn") val appearsOn: List<IcmArtistAlbum> = emptyList(),
    @SerialName("source") val source: String? = null
) {
    val isVk: Boolean
        get() = id.startsWith("vk_") || source == "vk"
}

@Serializable
data class IcmArtistSong(
    val id: String,
    val title: String,
    val artist: String,
    @SerialName("artistId") val artistId: String? = null,
    val artists: List<IcmMiniArtist> = emptyList(),
    val cover: String,
    @SerialName("albumName") val albumName: String? = null,
    @SerialName("isAlbum") val isAlbum: Boolean = false,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
    @SerialName("isCustom") val isCustom: Boolean = false,
    val region: String? = null,
    val source: String? = null,
    val duration: Long? = null
) {
    val isVk: Boolean
        get() = id.startsWith("vk_") || source == "vk"

    /** VK returns duration in seconds, Apple in milliseconds. Normalized to ms. */
    val durationMs: Long
        get() {
            val d = duration ?: return 0L
            return if (d < 1000L) d * 1000L else d
        }
}

@Serializable
data class IcmMiniArtist(
    val id: String? = null,
    val name: String? = null
) {
    val displayName: String
        get() = name ?: "Unknown Artist"
}

@Serializable
data class IcmArtistAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val artists: List<IcmMiniArtist> = emptyList(),
    val year: String? = null,
    val date: String? = null,
    val cover: String,
    val type: String? = null,
    @SerialName("isAlbum") val isAlbum: Boolean = false
)

@Serializable
data class IcmSimilarArtist(
    val id: String,
    val name: String? = null,
    val url: String? = null,
    val cover: String? = null
) {
    val displayName: String
        get() = name ?: "Unknown Artist"
}

@Serializable
data class IcmArtistPlaylist(
    val id: String,
    val title: String,
    val cover: String? = null
)

// ─── Chart ───

@Serializable
data class IcmChart(
    val id: String,
    val name: String,
    val query: String,
    val cover: String? = null,
    val tracks: List<IcmSearchItem> = emptyList()
)

// ─── Track Meta ───

@Serializable
data class IcmTrackMeta(
    val id: String,
    @SerialName("collectionId") val collectionId: String? = null,
    val title: String,
    val artist: String,
    val cover: String,
    val duration: Long
)

// ─── Playlist ───

@Serializable
data class IcmPlaylist(
    val id: String,
    val name: String,
    val curator: String? = null,
    val description: String? = null,
    val cover: String? = null,
    val tracks: List<IcmPlaylistTrack> = emptyList()
)

@Serializable
data class IcmPlaylistPreviewRequest(
    @SerialName("source") val source: String,
    @SerialName("url") val url: String
)

@Serializable
data class IcmPlaylistTrack(
    val id: String,
    val title: String,
    val artist: String,
    @SerialName("artistId") val artistId: String,
    val cover: String,
    @SerialName("collectionId") val collectionId: String,
    val duration: Long,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
    @SerialName("isCustom") val isCustom: Boolean = false
) {
    val durationMs: Long
        get() = if (duration < 1000L) duration * 1000L else duration
}

// ─── Cover Sign ───

@Serializable
data class IcmCoverSignResponse(
    val url: String,
    @SerialName("expires_at") val expiresAt: Long
)

// ─── Lyrics ───

@Serializable
data class IcmLyricsResponse(
    @SerialName("track_id") val trackId: String,
    val lyrics: String? = null,
    val synced: Boolean = false,
    val source: String? = null,
    val format: String? = null
)

// ─── Errors ───

@Serializable
data class IcmError(
    @SerialName("error") val error: String,
    @SerialName("message") val message: String? = null,
    @SerialName("required_region") val requiredRegion: String? = null,
    @SerialName("retry_after") val retryAfter: Int? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("attempts_left") val attemptsLeft: Int? = null
)

@Serializable
data class IcmErrorWrapper(
    @SerialName("detail") val detail: IcmError
)


// ─── Batch Track Meta ───

@Serializable
data class IcmBatchTrackMetaRequest(
    @SerialName("track_ids") val trackIds: List<String>
)

@Serializable
data class IcmBatchTrackMetaResponse(
    val count: Int? = null,
    val items: List<IcmBatchTrackMetaItem> = emptyList()
)

@Serializable
data class IcmBatchTrackMetaItem(
    val id: String,
    val title: String? = null,
    val artist: String? = null,
    val cover: String? = null,
    val duration: Long? = null,
    @SerialName("collectionId") val collectionId: String? = null,
    @SerialName("track_id") val trackId: String? = null,
    val error: String? = null
) {
    val isSuccess: Boolean
        get() = error == null && title != null

    val isError: Boolean
        get() = error != null

    /** VK/secondary tracks return duration in seconds, Apple in ms. Normalized to ms. */
    val durationMs: Long
        get() {
            val d = duration ?: return 0L
            return if (d < 1000L) d * 1000L else d
        }
}

// ─── Async Track ───

@Serializable
data class IcmAsyncTrackPending(
    @SerialName("job_id") val jobId: String,
    val status: String = "pending",
    @SerialName("poll_url") val pollUrl: String? = null,
    @SerialName("poll_after_seconds") val pollAfterSeconds: Int = 3
)

@Serializable
data class IcmAsyncTrackReady(
    @SerialName("job_id") val jobId: String,
    val status: String = "ready",
    @SerialName("track_id") val trackId: String,
    @SerialName("file_id") val fileId: String,
    val source: String,
    val quality: String,
    @SerialName("artist_id") val artistId: String? = null,
    val url: String,
    @SerialName("expires_at") val expiresAt: Long
)

// ─── Account Linking ───

@Serializable
data class IcmAccountLinkUrl(
    val url: String,
    @SerialName("expires_at") val expiresAt: Long? = null
)

@Serializable
data class IcmAccountLinkCallback(
    val state: String,
    val linked: Boolean,
    @SerialName("icm_user_id") val icmUserId: String? = null,
    val error: String? = null
)

// ─── Domain Model Conversion ───

fun IcmSearchItem.toTrack(uri: String? = null): com.liquidmusicglass.engine.Track {
    return com.liquidmusicglass.engine.Track(
        id = id,
        title = title,
        artist = displayArtist,
        albumName = album ?: collectionId ?: "Single",
        uri = android.net.Uri.parse(uri ?: preview ?: "https://byicloud.online/track/$id"),
        // `secondary_*` / `vk_*` tracks come back with `duration` in seconds; Apple in ms.
        // Reuse the model's normalized accessor so the progress bar shows the right scale.
        durationMs = durationMs,
        albumId = collectionId?.hashCode()?.toLong() ?: id.hashCode().toLong(),
        coverUrl = cover?.replace("1000x1000", "600x600") ?: cover,
        artists = artists,
        isExplicit = isExplicit,
        isCustom = isCustom,
        source = source
    )
}

fun IcmAlbumTrack.toTrack(): com.liquidmusicglass.engine.Track {
    return com.liquidmusicglass.engine.Track(
        id = id,
        title = title,
        artist = artist,
        albumName = "",
        uri = android.net.Uri.parse("https://byicloud.online/track/$id"),
        durationMs = durationMs,
        albumId = collectionId?.hashCode()?.toLong() ?: id.hashCode().toLong(),
        coverUrl = cover.replace("1000x1000", "600x600"),
        artists = emptyList(),
        isExplicit = isExplicit,
        isCustom = isCustom,
        source = source
    )
}

fun IcmArtistSong.toTrack(): com.liquidmusicglass.engine.Track {
    return com.liquidmusicglass.engine.Track(
        id = id,
        title = title,
        artist = artists.firstOrNull()?.displayName ?: artist.takeIf { it.isNotBlank() } ?: "Unknown Artist",
        albumName = albumName ?: "",
        uri = android.net.Uri.parse("https://byicloud.online/track/$id"),
        durationMs = durationMs,
        albumId = 0L,
        coverUrl = cover.replace("300x300", "600x600"),
        artists = artists,
        isExplicit = isExplicit,
        isCustom = isCustom,
        source = source
    )
}

fun IcmPlaylistTrack.toTrack(): com.liquidmusicglass.engine.Track {
    return com.liquidmusicglass.engine.Track(
        id = id,
        title = title,
        artist = artist,
        albumName = "",
        uri = android.net.Uri.parse("https://byicloud.online/track/$id"),
        durationMs = durationMs,
        albumId = collectionId.hashCode().toLong(),
        coverUrl = cover.replace("1000x1000", "600x600"),
        artists = emptyList(),
        isExplicit = isExplicit,
        isCustom = isCustom
    )
}

// ─── Error Codes ───

object IcmErrorCodes {
    const val MISSING_API_KEY = "missing_api_key"
    const val INVALID_SESSION_TOKEN = "invalid_session_token"
    const val INVALID_API_KEY = "invalid_api_key"
    const val PARTNER_SUSPENDED = "partner_suspended"
    const val SCOPE_NOT_ALLOWED = "scope_not_allowed"
    const val SOURCE_NOT_ALLOWED = "source_not_allowed"
    const val INVALID_OR_EXPIRED_SIGNATURE = "invalid_or_expired_signature"
    const val TRACK_NOT_FOUND = "track_not_found"
    const val RATE_LIMITED = "rate_limited"
    const val REGION_UNAVAILABLE = "region_unavailable"
    const val NOT_FOUND = "not_found"
    const val QUERY_TOO_SHORT = "query_too_short"
    const val QUERY_SPAM_DETECTED = "query_spam_detected"
    const val EARLY_ACCESS = "early_access"
    const val SUBSCRIPTION_REQUIRED = "subscription_required"
    const val USER_NOT_LINKED = "user_not_linked"
}

// ─── Search Source ───

object IcmSearchSource {
    const val PRIMARY = "primary"
    const val SECONDARY = "secondary"
    const val ALL = "all"

    // Legacy aliases for backward compatibility
    const val APPLE = PRIMARY
    const val VK = SECONDARY
}

// ─── Stream Quality ───

object IcmStreamQuality {
    const val K128 = "128K"
    const val K256 = "256K"
    const val K320 = "320K"
    const val ALAC = "ALAC"
}

// ─── Personal Cabinet (/me/*) ───

@Serializable
data class IcmUserQualityRequest(
    @SerialName("quality") val quality: String
)

@Serializable
data class IcmUserQualityResponse(
    @SerialName("quality") val quality: String,
    @SerialName("max_allowed") val maxAllowed: String? = null,
    @SerialName("source") val source: String? = null
)

@Serializable
data class IcmUserPreferences(
    @SerialName("partner_user_id") val partnerUserId: String? = null,
    @SerialName("quality_preference") val qualityPreference: String? = null,
    @SerialName("max_quality") val maxQuality: String? = null,
    @SerialName("allowed_qualities") val allowedQualities: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long? = null
)

@Serializable
data class IcmUpdatePreferencesRequest(
    @SerialName("quality") val quality: String?
)

@Serializable
data class IcmUserProfile(
    @SerialName("partner_user_id") val partnerUserId: String? = null,
    val name: String? = null,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

// ─── Wave (Personal Radio) ───

@Serializable
data class IcmWaveResponse(
    val track: IcmWaveTrack? = null,
    val status: String,
    val region: String? = null
)

@Serializable
data class IcmWaveTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    @SerialName("artistId") val artistId: String? = null,
    val cover: String? = null,
    val duration: Long? = null,
    @SerialName("collectionId") val collectionId: String? = null,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
    @SerialName("isCustom") val isCustom: Boolean = false,
    val source: String? = null
) {
    val durationMs: Long
        get() {
            val d = duration ?: return 0L
            return if (d < 1000L) d * 1000L else d
        }

    fun toTrack(): com.liquidmusicglass.engine.Track {
        return com.liquidmusicglass.engine.Track(
            id = id,
            title = title,
            artist = artist ?: "Unknown Artist",
            albumName = "",
            uri = android.net.Uri.parse("https://byicloud.online/track/$id"),
            durationMs = durationMs,
            albumId = collectionId?.hashCode()?.toLong() ?: id.hashCode().toLong(),
            coverUrl = cover?.replace("1000x1000", "600x600"),
            isExplicit = isExplicit,
            isCustom = isCustom,
            source = source
        )
    }
}

// ─── Library (likes, subscriptions) ───

@Serializable
data class IcmLibraryLikesResponse(
    val items: List<IcmLibraryTrack> = emptyList(),
    val count: Int? = null,
    val total: Int? = null,
    val offset: Int? = null,
    val limit: Int? = null
)

@Serializable
data class IcmLikeRequest(
    @SerialName("track_id") val trackIdSnake: String,
    @SerialName("trackId") val trackIdCamel: String
)

@Serializable
data class IcmLikeResponse(
    @SerialName("status") val status: String? = null,
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("logged") val logged: Boolean = false
)

@Serializable
data class IcmLibrarySubscriptionsResponse(
    val items: List<IcmLibraryArtist> = emptyList(),
    val count: Int? = null,
    val total: Int? = null,
    val offset: Int? = null,
    val limit: Int? = null
)

@Serializable
data class IcmLibraryTrack(
    val id: String,
    @SerialName("trackId") val trackId: String? = null,
    val title: String,
    val artist: String? = null,
    @SerialName("artistId") val artistId: String? = null,
    val cover: String? = null,
    val duration: Long? = null,
    @SerialName("collectionId") val collectionId: String? = null,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
    @SerialName("isCustom") val isCustom: Boolean = false,
    val source: String? = null,
    @SerialName("liked_at") val likedAt: Long? = null
) {
    /** VK/secondary return duration in seconds, Apple in milliseconds. Normalized to ms. */
    val durationMs: Long
        get() {
            val d = duration ?: return 0L
            return if (d < 1000L) d * 1000L else d
        }
}

@Serializable
data class IcmLibraryArtist(
    val id: String,
    val name: String? = null,
    val cover: String? = null,
    val image: String? = null,
    @SerialName("isCustom") val isCustom: Boolean = false,
    @SerialName("isTidal") val isTidal: Boolean = false,
    val source: String? = null
) {
    val displayName: String
        get() = name ?: "Unknown Artist"

    /** Prefer Apple `image` field, fallback to legacy `cover`. */
    val displayImage: String?
        get() = image ?: cover
}

// ─── Wave Feedback & Onboarding ───

@Serializable
data class IcmWaveFeedbackRequest(
    @SerialName("feedback_type") val feedbackType: String,
    @SerialName("value") val value: String
)

@Serializable
data class IcmWaveFeedbackResponse(
    @SerialName("ok") val ok: Boolean = false
)

@Serializable
data class IcmWaveResetResponse(
    @SerialName("status") val status: String,
    @SerialName("removed") val removed: Int
) {
    val isSuccess: Boolean get() = status == "ok"
}

@Serializable
data class IcmWaveOnboardingResponse(
    @SerialName("artists") val artists: List<IcmWaveOnboardingArtist> = emptyList(),
    @SerialName("completed") val completed: Boolean = false
)

@Serializable
data class IcmWaveOnboardingArtist(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("image") val image: String? = null
)

@Serializable
data class IcmWaveOnboardingSaveRequest(
    @SerialName("artists") val artists: List<IcmWaveOnboardingArtistSave>
)

@Serializable
data class IcmWaveOnboardingArtistSave(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String
)

@Serializable
data class IcmWaveOnboardingSaveResponse(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("saved") val saved: Int? = null
)

// ─── Wave Playback Logging ───

@Serializable
data class IcmWavePlaybackRequest(
    @SerialName("track_id") val trackId: String,
    @SerialName("played_seconds") val playedSeconds: Double,
    @SerialName("total_seconds") val totalSeconds: Double? = null,
    @SerialName("completed") val completed: Boolean? = null,
    @SerialName("skipped") val skipped: Boolean? = null
)

@Serializable
data class IcmWavePlaybackResponse(
    @SerialName("status") val status: String,
    @SerialName("logged") val logged: Boolean = false
)

// ─── Email Account Linking ───

@Serializable
data class IcmEmailLinkRequest(
    @SerialName("partner_user_id") val partnerUserId: String,
    @SerialName("email") val email: String,
    @SerialName("state") val state: String? = null
)

@Serializable
data class IcmEmailLinkResponse(
    @SerialName("sent") val sent: Boolean = false,
    @SerialName("nonce") val nonce: String,
    @SerialName("expires_in") val expiresIn: Int
)

@Serializable
data class IcmEmailVerifyRequest(
    @SerialName("nonce") val nonce: String,
    @SerialName("otp") val otp: String
)

@Serializable
data class IcmEmailVerifyResponse(
    @SerialName("linked") val linked: Boolean = false,
    @SerialName("icm_user_id") val icmUserId: Long? = null,
    @SerialName("state") val state: String? = null,
    @SerialName("password_issued") val passwordIssued: Boolean = false
)

@Serializable
data class IcmPasswordChangeRequest(
    @SerialName("partner_user_id") val partnerUserId: String,
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String
)

@Serializable
data class IcmPasswordChangeResponse(
    @SerialName("changed") val changed: Boolean = false
)

@Serializable
data class IcmPasswordResetRequest(
    @SerialName("partner_user_id") val partnerUserId: String
)

@Serializable
data class IcmPasswordResetResponse(
    val reset: Boolean = false
)

// ═══════════════════════════════════════════════════════════
//  Home Screen Models (Banners, New Releases, Charts)
// ═══════════════════════════════════════════════════════════

/**
 * A generic content block returned by the backend for the home screen.
 * Each block has a title, type, and a list of items.
 */
@Serializable
data class IcmHomeBlock(
    val id: String,
    val title: String,
    val type: String, // "banner", "new_releases", "charts", "recommendations"
    val items: List<IcmHomeItem> = emptyList()
)

/**
 * A single item inside a home block.
 * Can represent a track, album, artist, or promotional card.
 */
@Serializable
data class IcmHomeItem(
    val id: String,
    val title: String,
    val artist: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("artistId") val artistId: String? = null,
    val cover: String? = null,
    @SerialName("collectionId") val collectionId: String? = null,
    val album: String? = null,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
    val region: String? = null,
    val duration: Long? = null,
    val source: String? = null,
    @SerialName("trackId") val trackId: String? = null,
    @SerialName("rank") val rank: Int? = null,
    @SerialName("subtitle") val subtitle: String? = null,
    @SerialName("genre") val genre: String? = null
) {
    val displayArtist: String
        get() = artist?.takeIf { it.isNotBlank() && it != "Исполнитель" }
            ?: artistName?.takeIf { it.isNotBlank() && it != "Исполнитель" }
            ?: "Unknown Artist"

    /** VK returns duration in seconds, Apple in milliseconds. Normalized to ms. */
    val durationMs: Long
        get() {
            val d = duration ?: return 0L
            return if (d < 1000) d * 1000L else d
        }
}

/**
 * Full home screen response — a list of content blocks.
 * This is what the backend returns for GET /api/partner/home (if available)
 * or what we construct from multiple API calls.
 */
@Serializable
data class IcmHomeResponse(
    val blocks: List<IcmHomeBlock> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long? = null
)

// ─── Subscription ───

@Serializable
data class IcmSubscriptionResponse(
    val active: Boolean,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("expires_at_iso") val expiresAtIso: String? = null,
    @SerialName("days_left") val daysLeft: Int = 0,
    @SerialName("plan_type") val planType: String? = null,
    @SerialName("is_family_owner") val isFamilyOwner: Boolean = false,
    @SerialName("is_family_member") val isFamilyMember: Boolean = false,
    val regions: List<IcmSubscriptionRegion> = emptyList()
) {
    /** Whether subscription is currently active and not expired. */
    val isActive: Boolean
        get() = active && (daysLeft > 0 || expiresAt == null)

    /** Whether subscription has expired. */
    val isExpired: Boolean
        get() = !active || (daysLeft <= 0 && expiresAt != null)

    /** Subscription tier name (plan_type or default). */
    val tier: String
        get() = planType ?: "standard"
}

@Serializable
data class IcmSubscriptionRegion(
    val code: String,
    val name: String,
    @SerialName("expires_at") val expiresAt: Long? = null
)

// ─── Region ───

@Serializable
data class IcmRegionResponse(
    val current: String,
    val available: List<IcmAvailableRegion> = emptyList(),
    @SerialName("allowed_by_partner") val allowedByPartner: List<String> = emptyList(),
    @SerialName("requires_subscription") val requiresSubscription: List<String> = emptyList()
)

@Serializable
data class IcmAvailableRegion(
    val code: String,
    val name: String,
    val free: Boolean = false,
    @SerialName("expires_at") val expiresAt: Long? = null
)

@Serializable
data class IcmUpdateRegionRequest(
    @SerialName("region") val region: String
)

@Serializable
data class IcmUpdateRegionResponse(
    @SerialName("region") val region: String
)

// ─── Playlist Import ───

@Serializable
data class IcmPlaylistImportRequest(
    @SerialName("source") val source: String,
    @SerialName("url") val url: String,
    @SerialName("name") val name: String? = null
)

@Serializable
data class IcmPlaylistImportTrack(
    @SerialName("trackId") val trackIdRaw: JsonElement? = null,
    @SerialName("id") val idRaw: JsonElement? = null,
    @SerialName("track_id") val trackIdUnderscore: JsonElement? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("artist") val artist: String? = null,
    @SerialName("cover") val cover: String? = null,
    @SerialName("collectionId") val collectionId: String? = null,
    @SerialName("duration") val duration: Long? = null,
    @SerialName("match_score") val matchScore: Double? = null
) {
    val trackId: String?
        get() = (trackIdRaw ?: idRaw ?: trackIdUnderscore)?.let {
            when {
                it is JsonPrimitive && it.isString -> it.content
                it is JsonPrimitive && !it.isString -> it.longOrNull?.toString()
                else -> null
            }
        }
}

@Serializable
data class IcmPlaylistImportResponse(
    // API returns Int for Apple sync import, String for async job — accept both via JsonElement
    @SerialName("playlist_id") val playlistIdRaw: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("total") val total: Int? = null,
    @SerialName("matched") val matched: Int? = null,
    @SerialName("failed") val failed: Int? = null,
    @SerialName("tracks") val tracks: List<IcmPlaylistImportTrack>? = null,
    @SerialName("failed_tracks") val failedTracks: List<IcmFailedTrack>? = null,

    // Async fields (Yandex)
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("poll_url") val pollUrl: String? = null,
    @SerialName("poll_after") val pollAfter: Int? = null
) {
    /** Normalized playlist id as String (handles both Int and String from API) */
    val playlistId: String?
        get() = playlistIdRaw?.let {
            when {
                it is JsonPrimitive && it.isString -> it.content
                it is JsonPrimitive && !it.isString -> it.longOrNull?.toString()
                else -> null
            }
        }
}

@Serializable
data class IcmFailedTrack(
    @SerialName("yandex_title") val yandexTitle: String? = null,
    @SerialName("yandex_artists") val yandexArtists: List<String> = emptyList(),
    @SerialName("reason") val reason: String? = null
)

// ─── Playlist Preview ───

@Serializable
data class IcmPlaylistPreviewResponse(
    @SerialName("source") val source: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("total") val total: Int? = null,
    @SerialName("tracks") val tracks: List<IcmPreviewTrack> = emptyList()
)

@Serializable
data class IcmPreviewTrack(
    @SerialName("id") val id: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("artist") val artist: String? = null,
    @SerialName("artists") val artists: List<String> = emptyList(),
    @SerialName("album") val album: String? = null,
    @SerialName("albumName") val albumName: String? = null,
    @SerialName("collectionId") val collectionId: String? = null,
    @SerialName("cover") val cover: String? = null,
    @SerialName("duration") val duration: Long? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("version") val version: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null
)

// ─── Playlist Job ───

@Serializable
data class IcmPlaylistImportJobResponse(
    // 202 async response
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("poll_url") val pollUrl: String? = null,
    @SerialName("poll_after") val pollAfter: Int? = null,

    // Poll response — pending
    @SerialName("status") val status: String? = null,
    @SerialName("progress") val progress: IcmImportJobProgress? = null,

    // When ready — API returns Int for playlist_id
    @SerialName("playlist_id") val playlistIdRaw: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("total") val total: Int? = null,
    @SerialName("matched") val matched: Int? = null,
    @SerialName("failed") val failed: Int? = null,
    @SerialName("tracks") val tracks: List<IcmPlaylistImportTrack>? = null,
    @SerialName("failed_tracks") val failedTracks: List<IcmFailedTrack>? = null,

    // When failed
    @SerialName("error") val error: String? = null,
    @SerialName("message") val message: String? = null
) {
    /** Normalized playlist id as String (handles both Int and String from API) */
    val playlistId: String?
        get() = playlistIdRaw?.let {
            when {
                it is JsonPrimitive && it.isString -> it.content
                it is JsonPrimitive && !it.isString -> it.longOrNull?.toString()
                else -> null
            }
        }
}

@Serializable
data class IcmImportJobProgress(
    @SerialName("total") val total: Int? = null,
    @SerialName("matched") val matched: Int? = null,
    @SerialName("failed") val failed: Int? = null
)

// ─── Playlist Management ───

@Serializable
data class IcmUserPlaylistsResponse(
    @SerialName("count") val count: Int? = null,
    @SerialName("total") val total: Int? = null,
    @SerialName("offset") val offset: Int? = null,
    @SerialName("limit") val limit: Int? = null,
    @SerialName("items") val items: List<IcmUserPlaylist> = emptyList()
)

@Serializable
data class IcmUserPlaylist(
    // API returns Int for playlist id — accept both Int and String
    @SerialName("id") val idRaw: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("track_count") val trackCount: Int? = null,
    @SerialName("cover") val cover: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null
) {
    /** Normalized playlist id as String (handles both Int and String from API) */
    val id: String?
        get() = idRaw?.let {
            when {
                it is JsonPrimitive && it.isString -> it.content
                it is JsonPrimitive && !it.isString -> it.longOrNull?.toString()
                else -> null
            }
        }
}

@Serializable
data class IcmUserPlaylistTracksResponse(
    @SerialName("playlist") val playlist: IcmUserPlaylistInfo? = null,
    @SerialName("tracks") val tracks: List<IcmUserPlaylistTrack> = emptyList()
)

@Serializable
data class IcmUserPlaylistInfo(
    // API returns Int for playlist id — accept both Int and String
    @SerialName("id") val idRaw: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("track_count") val trackCount: Int? = null
) {
    /** Normalized playlist id as String (handles both Int and String from API) */
    val id: String?
        get() = idRaw?.let {
            when {
                it is JsonPrimitive && it.isString -> it.content
                it is JsonPrimitive && !it.isString -> it.longOrNull?.toString()
                else -> null
            }
        }
}

@Serializable
data class IcmUserPlaylistTrack(
    @SerialName("trackId") val trackIdRaw: JsonElement? = null,
    @SerialName("id") val idRaw: JsonElement? = null,
    @SerialName("track_id") val trackIdUnderscore: JsonElement? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("artist") val artist: String? = null,
    @SerialName("cover") val cover: String? = null,
    @SerialName("duration") val duration: Long? = null,
    @SerialName("collectionId") val collectionId: String? = null,
    @SerialName("position") val position: Int? = null
) {
    val trackId: String?
        get() = (trackIdRaw ?: idRaw ?: trackIdUnderscore)?.let {
            when {
                it is JsonPrimitive && it.isString -> it.content
                it is JsonPrimitive && !it.isString -> it.longOrNull?.toString()
                else -> null
            }
        }
}

@Serializable
data class IcmDeletePlaylistResponse(
    val deleted: Boolean = true
)
