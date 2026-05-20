package com.liquidmusicglass.api.icm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Health ───

@Serializable
data class IcmHealthResponse(
    @SerialName("partner_id") val partnerId: String,
    val status: String,
    val scopes: List<String> = emptyList(),
    @SerialName("rate_limits") val rateLimits: IcmRateLimits? = null,
    val stream: IcmStreamConfig? = null,
    val search: IcmSearchConfig? = null,
    @SerialName("server_time") val serverTime: Long? = null
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

@Serializable
data class IcmTrackRequest(
    @SerialName("trackId") val trackId: String,
    val region: String = "us",
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
    /** VK returns duration in seconds, Apple in milliseconds. Normalized to ms. */
    val durationMs: Long
        get() {
            val d = duration ?: return 0L
            val isVk = id.startsWith("vk_") || source == "secondary" || source == "vk"
            return if (isVk) d * 1000L else d
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
)

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
    val error: String,
    val message: String? = null,
    @SerialName("required_region") val requiredRegion: String? = null,
    @SerialName("retry_after") val retryAfter: Int? = null,
    @SerialName("source") val source: String? = null
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
        isCustom = isCustom
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
        isCustom = isCustom
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
        isCustom = isCustom
    )
}

fun IcmPlaylistTrack.toTrack(): com.liquidmusicglass.engine.Track {
    return com.liquidmusicglass.engine.Track(
        id = id,
        title = title,
        artist = artist,
        albumName = "",
        uri = android.net.Uri.parse("https://byicloud.online/track/$id"),
        // VK/secondary return seconds, Apple ms — normalize uniformly.
        durationMs = if (id.startsWith("vk_")) duration * 1000L else duration,
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
    val quality: String
)

@Serializable
data class IcmUserQualityResponse(
    val quality: String,
    @SerialName("max_allowed") val maxAllowed: String? = null,
    val source: String? = null
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
    /** VK returns duration in seconds, Apple in milliseconds. Normalized to ms. */
    val durationMs: Long
        get() {
            val d = duration ?: return 0L
            val isVk = id.startsWith("vk_") || source == "secondary" || source == "vk"
            return if (isVk) d * 1000L else d
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
            isCustom = isCustom
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
    /** VK returns duration in seconds, Apple in milliseconds. Normalized to ms. */
    val durationMs: Long
        get() {
            val d = duration ?: return 0L
            val isVk = id.startsWith("vk_") || source == "secondary" || source == "vk"
            return if (isVk) d * 1000L else d
        }
}

@Serializable
data class IcmLibraryArtist(
    val id: String,
    val name: String? = null,
    val cover: String? = null,
    val image: String? = null,
    @SerialName("isCustom") val isCustom: Boolean = false,
    @SerialName("isPremium") val isPremium: Boolean = false,
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
    val value: String
)

@Serializable
data class IcmWaveFeedbackResponse(
    val ok: Boolean = false
)

@Serializable
data class IcmWaveOnboardingResponse(
    val artists: List<IcmWaveOnboardingArtist> = emptyList(),
    val completed: Boolean = false
)

@Serializable
data class IcmWaveOnboardingArtist(
    val id: String,
    val name: String,
    val image: String? = null
)

@Serializable
data class IcmWaveOnboardingSaveRequest(
    val artists: List<IcmWaveOnboardingArtistSave>
)

@Serializable
data class IcmWaveOnboardingArtistSave(
    val id: String,
    val name: String
)

@Serializable
data class IcmWaveOnboardingSaveResponse(
    val ok: Boolean = false,
    val saved: Int? = null
)

// ─── Wave Playback Logging ───

@Serializable
data class IcmWavePlaybackRequest(
    @SerialName("track_id") val trackId: String,
    @SerialName("played_seconds") val playedSeconds: Double,
    @SerialName("total_seconds") val totalSeconds: Double? = null,
    val completed: Boolean? = null,
    val skipped: Boolean? = null
)

@Serializable
data class IcmWavePlaybackResponse(
    val status: String,
    val logged: Boolean = false
)

// ─── Email Account Linking ───

@Serializable
data class IcmEmailLinkRequest(
    @SerialName("partner_user_id") val partnerUserId: String,
    val email: String,
    val state: String? = null
)

@Serializable
data class IcmEmailLinkResponse(
    val sent: Boolean = false,
    val nonce: String,
    @SerialName("expires_in") val expiresIn: Int
)

@Serializable
data class IcmEmailVerifyRequest(
    val nonce: String,
    val otp: String
)

@Serializable
data class IcmEmailVerifyResponse(
    val linked: Boolean = false,
    @SerialName("icm_user_id") val icmUserId: Long? = null,
    val state: String? = null,
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
    val changed: Boolean = false
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
