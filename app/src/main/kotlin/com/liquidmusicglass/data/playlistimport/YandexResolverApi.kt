package com.liquidmusicglass.data.playlistimport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for the private Yandex Playlist Resolver (FastAPI).
 *
 * Base URL: http://your-server:8000 (configured in DI)
 *
 * Endpoints:
 *   GET /resolve?url={yandex_playlist_url} → [{"title":"...","artist":"..."}]
 *   GET /health → {"status":"ok"}
 */
interface YandexResolverApi {

    /**
     * Resolve a Yandex Music playlist URL into a list of track metadata.
     *
     * @param url Full Yandex Music playlist URL (e.g. https://music.yandex.ru/playlists/lk.xxxxx)
     * @return List of raw track entries with title and artist
     */
    @GET("/resolve")
    suspend fun resolvePlaylist(
        @Query("url") url: String
    ): List<YandexResolverTrackDto>

    /**
     * Health check for the resolver service.
     */
    @GET("/health")
    suspend fun healthCheck(): YandexResolverHealthDto
}

/**
 * DTO for a single track from the Yandex resolver.
 */
@Serializable
data class YandexResolverTrackDto(
    @SerialName("title")
    val title: String,

    @SerialName("artist")
    val artist: String
)

/**
 * DTO for health check response.
 */
@Serializable
data class YandexResolverHealthDto(
    @SerialName("status")
    val status: String
)
