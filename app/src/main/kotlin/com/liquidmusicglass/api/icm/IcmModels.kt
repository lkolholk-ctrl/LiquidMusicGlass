package com.liquidmusicglass.api.icm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Модели данных для ICM Music Partner API (byicloud.online)
 * Полная документация: https://byicloud.online/partners/api-docs
 */

// ─── Health ───

@Serializable
data class IcmHealthResponse(
    val partnerId: String,
    val status: String,
    val scopes: List<String>,
    val rateLimits: IcmRateLimits? = null,
    val stream: IcmStreamConfig? = null,
    val search: IcmSearchConfig? = null,
    val serverTime: Long? = null
)

@Serializable
data class IcmRateLimits(
    val search: IcmRateLimit? = null,
    val stream: IcmRateLimit? = null,
    val sessionIssue: IcmRateLimit? = null
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
    val items: List<IcmSearchItem>
)

@Serializable
data class IcmSearchItem(
    val id: String,
    val title: String,
    val artist: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("artistId") val artistId: String? = null,
    val cover: String? = null,
    val preview: String? = null,
    @SerialName("collectionId") val collectionId: String? = null,
    val album: String? = null,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
    val region: String? = null,
    @SerialName("isArtist") val isArtist: Boolean = false,
    @SerialName("isAlbum") val isAlbum: Boolean = false,
    @SerialName("isCustom") val isCustom: Boolean = false,
    val duration: Long? = null
) {
    val displayArtist: String
        get() = artist ?: artistName ?: "Unknown"

    val isTrack: Boolean
        get() = !isArtist && !isAlbum
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
    @SerialName("file_id") val fileId: String,
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
    @SerialName("artistId") val artistId: String,
    val cover: String,
    val type: String,
    val year: Int? = null,
    @SerialName("trackCount") val trackCount: Int
)

@Serializable
data class IcmAlbumTrack(
    val id: String,
    val title: String,
    val artist: String,
    @SerialName("artistId") val artistId: String,
    val cover: String,
    @SerialName("collectionId") val collectionId: String,
    val duration: Long,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
    @SerialName("trackNumber") val trackNumber: Int,
    val preview: String? = null
)

// ─── Artist ───

@Serializable
data class IcmArtistResponse(
    val id: String,
    val name: String,
    val cover: String? = null,
    val description: String? = null,
    @SerialName("topTracks") val topTracks: List<IcmArtistTrack> = emptyList(),
    val albums: List<IcmArtistAlbum> = emptyList(),
    @SerialName("similarArtists") val similarArtists: List<IcmSimilarArtist> = emptyList()
)

@Serializable
data class IcmArtistTrack(
    val id: String,
    val title: String,
    val artist: String,
    @SerialName("artistId") val artistId: String,
    val cover: String,
    @SerialName("collectionId") val collectionId: String,
    val duration: Long,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
    val preview: String? = null
)

@Serializable
data class IcmArtistAlbum(
    val id: String,
    val title: String,
    val artist: String,
    @SerialName("artistId") val artistId: String,
    val cover: String,
    val type: String,
    val year: Int? = null,
    @SerialName("trackCount") val trackCount: Int
)

@Serializable
data class IcmSimilarArtist(
    val id: String,
    val name: String,
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
    @SerialName("is_explicit") val isExplicit: Boolean = false
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
    val trackId: String,
    val lyrics: String? = null,
    val synced: Boolean = false
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
    val count: Int,
    val items: List<IcmBatchTrackMetaItem>
)

/**
 * Batch item — может быть либо успешным результатом, либо ошибкой.
 * API не использует дискриминатор; отличай по наличию поля `error`.
 */
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
}

// ─── Async Track ───

@Serializable
data class IcmAsyncTrackPending(
    @SerialName("job_id") val jobId: String,
    val status: String = "pending",
    @SerialName("poll_url") val pollUrl: String,
    @SerialName("poll_after") val pollAfterSeconds: Int
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
        durationMs = duration ?: 0L,
        albumId = collectionId?.hashCode()?.toLong() ?: id.hashCode().toLong(),
        coverUrl = cover?.replace("1000x1000", "600x600") ?: cover
    )
}

fun IcmAlbumTrack.toTrack(): com.liquidmusicglass.engine.Track {
    return com.liquidmusicglass.engine.Track(
        id = id,
        title = title,
        artist = artist,
        albumName = "",
        uri = android.net.Uri.parse("https://byicloud.online/track/$id"),
        durationMs = duration,
        albumId = collectionId.hashCode().toLong(),
        coverUrl = cover.replace("1000x1000", "600x600")
    )
}

fun IcmArtistTrack.toTrack(): com.liquidmusicglass.engine.Track {
    return com.liquidmusicglass.engine.Track(
        id = id,
        title = title,
        artist = artist,
        albumName = "",
        uri = android.net.Uri.parse("https://byicloud.online/track/$id"),
        durationMs = duration,
        albumId = collectionId.hashCode().toLong(),
        coverUrl = cover.replace("1000x1000", "600x600")
    )
}

fun IcmPlaylistTrack.toTrack(): com.liquidmusicglass.engine.Track {
    return com.liquidmusicglass.engine.Track(
        id = id,
        title = title,
        artist = artist,
        albumName = "",
        uri = android.net.Uri.parse("https://byicloud.online/track/$id"),
        durationMs = duration,
        albumId = collectionId.hashCode().toLong(),
        coverUrl = cover.replace("1000x1000", "600x600")
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
}

// ─── Stream Quality ───

object IcmStreamQuality {
    const val K128 = "128K"
    const val K256 = "256K"
    const val K320 = "320K"
    const val ALAC = "ALAC"
}
