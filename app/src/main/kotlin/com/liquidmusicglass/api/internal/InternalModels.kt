package com.liquidmusicglass.api.internal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ═══════════════════════════════════════════════════════════
//  Auth
// ═══════════════════════════════════════════════════════════

@Serializable
data class InternalAuthRequest(
    val email: String,
    val password: String
)

@Serializable
data class InternalAuthResponse(
    val token: String,
    @SerialName("refresh_token") val refreshToken: String? = null
)

@Serializable
data class InternalUserProfile(
    val id: String,
    val name: String,
    val email: String? = null,
    val avatar: String? = null
)

// ═══════════════════════════════════════════════════════════
//  Featured Playlists (HomeScreen)
// ═══════════════════════════════════════════════════════════

@Serializable
data class InternalFeaturedPlaylist(
    val id: String,
    val title: String,
    val cover: String,
    @SerialName("track_count") val trackCount: Int = 0
)

@Serializable
data class InternalFeaturedPlaylistsResponse(
    val items: List<InternalFeaturedPlaylist>
)

// ═══════════════════════════════════════════════════════════
//  User History (HomeScreen — "Вы недавно слушали")
// ═══════════════════════════════════════════════════════════

@Serializable
data class InternalHistoryTrack(
    val id: String,
    val title: String,
    val artist: String,
    val cover: String,
    val duration: Long
)

@Serializable
data class InternalHistoryResponse(
    val items: List<InternalHistoryTrack>
)

// ═══════════════════════════════════════════════════════════
//  Promo Blocks (HomeScreen — баннеры)
// ═══════════════════════════════════════════════════════════

@Serializable
data class InternalPromoBlock(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val image: String,
    @SerialName("action_url") val actionUrl: String? = null
)

// ═══════════════════════════════════════════════════════════
//  Reviews — Releases
// ═══════════════════════════════════════════════════════════

@Serializable
data class InternalRelease(
    @SerialName("release_id") val releaseId: String,
    @SerialName("release_type") val releaseType: String,
    val title: String,
    val artist: String,
    @SerialName("cover_url") val coverUrl: String,
    @SerialName("review_count") val reviewCount: Int = 0,
    @SerialName("avg_score_100") val avgScore100: Int = 0
)

@Serializable
data class InternalReleasesResponse(
    val items: List<InternalRelease>,
    val total: Int,
    val page: Int,
    val pages: Int
)

// ═══════════════════════════════════════════════════════════
//  Reviews — Top
// ═══════════════════════════════════════════════════════════

@Serializable
data class InternalReviewTopItem(
    val id: String,
    val title: String,
    val artist: String,
    val cover: String,
    val score: Int
)

@Serializable
data class InternalReviewsTopResponse(
    @SerialName("daily_top") val dailyTop: List<InternalReviewTopItem>,
    @SerialName("critics_top") val criticsTop: List<InternalReviewTopItem>,
    val voting: List<InternalReviewTopItem>
)

// ═══════════════════════════════════════════════════════════
//  User Library (Медиатека — любимые треки)
// ═══════════════════════════════════════════════════════════

@Serializable
data class InternalUserLibraryResponse(
    @SerialName("liked_tracks") val likedTracks: List<InternalHistoryTrack>,
    @SerialName("liked_albums") val likedAlbums: List<InternalFeaturedPlaylist>
)

// ═══════════════════════════════════════════════════════════
//  Stream (Partner API через внутренний endpoint)
// ═══════════════════════════════════════════════════════════

@Serializable
data class InternalStreamRequest(
    @SerialName("track_id") val trackId: String
)

@Serializable
data class InternalStreamResponse(
    val url: String,
    @SerialName("expires_at") val expiresAt: Long? = null
)
