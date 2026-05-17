package com.liquidmusicglass.api.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository для внутреннего API byicloud.online.
 * Обёртка над InternalApiClient с кэшированием и обработкой ошибок.
 */
object InternalRepository {

    private val api = InternalApiClient

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    // ═══════════════════════════════════════════════════════════
    //  Auth
    // ═══════════════════════════════════════════════════════════

    /**
     * Авторизация через email и пароль.
     */
    suspend fun login(email: String, password: String): Boolean {
        val result = api.login(email, password)
        result.onSuccess {
            _isAuthenticated.value = true
            _lastError.value = null
        }.onFailure {
            _isAuthenticated.value = false
            _lastError.value = it.message
        }
        return result.isSuccess
    }

    /**
     * Проверить, аутентифицирован ли пользователь.
     */
    fun isLoggedIn(): Boolean = api.bearerToken != null

    /**
     * Выйти — очистить токен.
     */
    fun logout() {
        api.bearerToken = null
        _isAuthenticated.value = false
    }

    // ═══════════════════════════════════════════════════════════
    //  HomeScreen Data
    // ═══════════════════════════════════════════════════════════

    /**
     * Featured playlists — главный экран.
     */
    suspend fun getFeaturedPlaylists(): List<InternalFeaturedPlaylist> {
        val result = api.getFeaturedPlaylists()
        result.onFailure { _lastError.value = it.message }
        return result.getOrNull() ?: emptyList()
    }

    /**
     * История прослушиваний.
     */
    suspend fun getUserHistory(): List<InternalHistoryTrack> {
        val result = api.getUserHistory()
        result.onFailure { _lastError.value = it.message }
        return result.getOrNull() ?: emptyList()
    }

    /**
     * Промо-блоки.
     */
    suspend fun getPromoBlocks(): List<InternalPromoBlock> {
        val result = api.getPromoBlocks()
        result.onFailure { _lastError.value = it.message }
        return result.getOrNull() ?: emptyList()
    }

    // ═══════════════════════════════════════════════════════════
    //  Reviews
    // ═══════════════════════════════════════════════════════════

    /**
     * Релизы с рецензиями.
     */
    suspend fun getReleases(page: Int = 1, type: String = "all"): InternalReleasesResponse? {
        val result = api.getReleases(page, type)
        result.onFailure { _lastError.value = it.message }
        return result.getOrNull()
    }

    /**
     * Топ рецензий.
     */
    suspend fun getReviewsTop(): InternalReviewsTopResponse? {
        val result = api.getReviewsTop()
        result.onFailure { _lastError.value = it.message }
        return result.getOrNull()
    }

    // ═══════════════════════════════════════════════════════════
    //  User Library
    // ═══════════════════════════════════════════════════════════

    /**
     * Медиатека пользователя.
     */
    suspend fun getUserLibrary(likedLimit: Int = 6): InternalUserLibraryResponse? {
        val result = api.getUserLibrary(likedLimit)
        result.onFailure { _lastError.value = it.message }
        return result.getOrNull()
    }

    // ═══════════════════════════════════════════════════════════
    //  Streaming
    // ═══════════════════════════════════════════════════════════

    /**
     * Получить стриминговый URL через внутренний endpoint.
     * Требует Partner Key.
     */
    suspend fun getStreamUrl(trackId: String, partnerKey: String): String? {
        val result = api.getStreamUrl(trackId, partnerKey)
        result.onFailure { _lastError.value = it.message }
        return result.getOrNull()
    }
}