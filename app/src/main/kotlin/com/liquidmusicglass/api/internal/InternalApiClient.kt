package com.liquidmusicglass.api.internal

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

/**
 * Внутренний API клиент для byicloud.online.
 * Требует Bearer токен (JWT) для авторизации.
 * Базовый URL: https://m.byicloud.online
 */
object InternalApiClient {

    private const val BASE_URL = "https://m.byicloud.online"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        defaultRequest {
            url(BASE_URL)
            contentType(ContentType.Application.Json)
        }
    }

    /** Bearer токен для авторизации */
    var bearerToken: String? = null

    private fun authHeader() = bearerToken?.let { "Bearer $it" }
        ?: throw IllegalStateException("Not authenticated. Call login() first.")

    // ═══════════════════════════════════════════════════════════
    //  Auth
    // ═══════════════════════════════════════════════════════════

    /**
     * Авторизация через email и пароль.
     * @return JWT токен
     */
    suspend fun login(email: String, password: String): Result<String> {
        return try {
            val response = client.post("/api/auth/email/login") {
                setBody(InternalAuthRequest(email, password))
            }.body<InternalAuthResponse>()
            bearerToken = response.token
            Result.success(response.token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Получить профиль текущего пользователя.
     */
    suspend fun getProfile(): Result<InternalUserProfile> {
        return try {
            val profile = client.get("/api/auth/me") {
                header("Authorization", authHeader())
            }.body<InternalUserProfile>()
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  HomeScreen Data
    // ═══════════════════════════════════════════════════════════

    /**
     * Featured playlists — главный экран, блоки "Плейлисты" и "Под настроение".
     */
    suspend fun getFeaturedPlaylists(): Result<List<InternalFeaturedPlaylist>> {
        return try {
            val response = client.get("/api/featured-playlists") {
                header("Authorization", authHeader())
            }.body<InternalFeaturedPlaylistsResponse>()
            Result.success(response.items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * История прослушиваний — "Вы недавно слушали".
     */
    suspend fun getUserHistory(): Result<List<InternalHistoryTrack>> {
        return try {
            val response = client.get("/api/user/history") {
                header("Authorization", authHeader())
            }.body<InternalHistoryResponse>()
            Result.success(response.items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Промо-блоки — баннеры на главной.
     */
    suspend fun getPromoBlocks(): Result<List<InternalPromoBlock>> {
        return try {
            val response = client.get("/api/user/promo-blocks") {
                header("Authorization", authHeader())
            }.body<List<InternalPromoBlock>>()
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Reviews
    // ═══════════════════════════════════════════════════════════

    /**
     * Релизы с рецензиями — добавленные релизы.
     * @param page Номер страницы (пагинация)
     * @param type Фильтр: album, ep, single, all
     */
    suspend fun getReleases(
        page: Int = 1,
        type: String = "all"
    ): Result<InternalReleasesResponse> {
        return try {
            val response = client.get("/api/reviews/releases") {
                header("Authorization", authHeader())
                url {
                    parameters.append("page", page.toString())
                    parameters.append("type", type)
                }
            }.body<InternalReleasesResponse>()
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Топ рецензий — голосование, топ за сутки, топ критиков.
     */
    suspend fun getReviewsTop(): Result<InternalReviewsTopResponse> {
        return try {
            val response = client.get("/api/reviews/top") {
                header("Authorization", authHeader())
            }.body<InternalReviewsTopResponse>()
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  User Library
    // ═══════════════════════════════════════════════════════════

    /**
     * Медиатека пользователя — любимые треки и альбомы.
     * @param likedLimit Количество любимых треков (по умолчанию 6)
     */
    suspend fun getUserLibrary(
        likedLimit: Int = 6
    ): Result<InternalUserLibraryResponse> {
        return try {
            val response = client.get("/api/user/all") {
                header("Authorization", authHeader())
                url {
                    parameters.append("liked_limit", likedLimit.toString())
                }
            }.body<InternalUserLibraryResponse>()
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Streaming (Partner API через внутренний endpoint)
    // ═══════════════════════════════════════════════════════════

    /**
     * Получить стриминговый URL для трека.
     * Использует внутренний endpoint /api/partner/track с Partner Key.
     * @param trackId Apple Music ID трека
     * @param partnerKey Вечный партнёрский ключ
     */
    suspend fun getStreamUrl(
        trackId: String,
        partnerKey: String
    ): Result<String> {
        return try {
            val response = client.post("/api/partner/track") {
                header("Authorization", authHeader())
                header("X-Partner-Key", partnerKey)
                setBody(InternalStreamRequest(trackId))
            }.body<InternalStreamResponse>()
            Result.success(response.url)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
