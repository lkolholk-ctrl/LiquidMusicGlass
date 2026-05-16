package com.liquidmusicglass.api.icm

import com.liquidmusicglass.engine.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository для ICM Music Partner API.
 * Абстрагирует вызовы API, кеширование и конвертацию моделей.
 */
object IcmRepository {

    private val api = IcmApi.getInstance()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private var _lastException: Exception? = null

    /** Регион по умолчанию */
    var region: String
        get() = api.defaultRegion
        set(value) { api.defaultRegion = value }

    /** Качество стрима */
    var streamQuality: String?
        get() = api.streamQuality
        set(value) { api.streamQuality = value }

    /**
     * Инициализация с API-ключом.
     * Получить ключ: https://byicloud.online/partners
     */
    fun init(apiKey: String) {
        api.apiKey = apiKey
        api.sessionToken = null
        _isInitialized.value = true
        _lastError.value = null
    }

    /**
     * Инициализация с session token (для клиентских запросов).
     */
    fun initWithToken(sessionToken: String) {
        api.apiKey = null
        api.sessionToken = sessionToken
        _isInitialized.value = true
        _lastError.value = null
    }

    /**
     * Сброс инициализации.
     */
    fun reset() {
        api.apiKey = null
        api.sessionToken = null
        _isInitialized.value = false
        _lastError.value = null
        _lastException = null
    }

    /**
     * Проверить здоровье API.
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
     * Поиск треков, альбомов, артистов.
     * @return Список треков (только isTrack=true) или все результаты
     */
    suspend fun searchTracks(query: String, region: String? = null): List<Track> {
        if (query.isBlank()) return emptyList()
        val result = api.search(query, region)
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
     * Поиск — все результаты (треки + альбомы + артисты).
     */
    suspend fun searchAll(query: String, region: String? = null): IcmSearchResponse? {
        val result = api.search(query, region)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Получить подписанный URL для стрима.
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
     * Получить полный TrackResponse (включая expires_at).
     */
    suspend fun getTrackInfo(trackId: String, region: String? = null): IcmTrackResponse? {
        val result = api.getTrack(trackId, region)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Треки альбома как Track.
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
     * Информация об альбоме.
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
     * Топ-треки артиста.
     */
    suspend fun getArtistTopTracks(artistId: String, region: String? = null): List<Track> {
        val result = api.getArtist(artistId, region)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()?.topTracks?.map { it.toTrack() } ?: emptyList()
    }

    /**
     * Информация об артисте.
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
     * Метаданные трека.
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
     * Плейлист редакционный.
     */
    suspend fun getPlaylist(playlistId: String, region: String? = null): IcmPlaylist? {
        val result = api.getPlaylist(playlistId, region)
        result.exceptionOrNull()?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }
        return result.getOrNull()
    }

    /**
     * Текст песни.
     */
    suspend fun getLyrics(trackId: String): IcmLyricsResponse? {
        val result = api.getLyrics(trackId)
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
     * Batch метаданные треков — до 50 за запрос.
     * Экономит rate-limit и убирает round-trip.
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
     * Получить URL для стрима с async fallback.
     * Если трек холодный — автоматически poll'ит job до готовности.
     * @param maxPollAttempts Максимум попыток polling (по умолчанию 30 = ~60 сек)
     * @param pollIntervalMs Интервал между попытками (по умолчанию 2000мс)
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

        // Если пришёл async pending — poll'им
        if (exception is IcmAsyncPendingException) {
            return pollAsyncJob(exception.pending, maxPollAttempts, pollIntervalMs)
        }

        // Обычная ошибка
        exception?.let {
            _lastException = it as? Exception
            _lastError.value = it.message
        }

        // Успешный ответ (трек тёплый)
        return result.getOrNull()?.url
    }

    /**
     * Полный TrackResponse с async fallback.
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
     * Сгенерировать URL для привязки аккаунта пользователя к ICM.
     * Требует partnerId — твой ID в системе ICM (из /health ответа partnerId).
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
     * Парсить callback от ICM после линковки.
     * Проверяй state на совпадение с тем, что отправлял.
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
    //  Error Handling Helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Проверить, является ли последняя ошибка — region unavailable (451).
     * Если да — requiredRegion содержит нужный регион.
     */
    fun isRegionUnavailable(): Boolean {
        val error = _lastError.value ?: return false
        return error.contains(IcmErrorCodes.REGION_UNAVAILABLE)
    }

    /**
     * Проверить, является ли последняя ошибка — rate limited (429).
     */
    fun isRateLimited(): Boolean {
        val error = _lastError.value ?: return false
        return error.contains(IcmErrorCodes.RATE_LIMITED)
    }

    /**
     * Получить recommended region из последней ошибки (451 region_unavailable).
     */
    fun getRequiredRegion(): String? {
        val ex = _lastException as? IcmApiException ?: return null
        return ex.requiredRegion
    }

    /**
     * Получить retry-after из последней ошибки 429 (rate_limited).
     */
    fun getRetryAfter(): Int? {
        val ex = _lastException as? IcmApiException ?: return null
        return ex.retryAfter
    }

    /**
     * Получить error code из последней ошибки.
     */
    fun getLastErrorCode(): String? {
        val ex = _lastException as? IcmApiException ?: return null
        return ex.errorCode
    }

    /**
     * Получить source из последней ошибки (403 source_not_allowed).
     */
    fun getLastErrorSource(): String? {
        val ex = _lastException as? IcmApiException ?: return null
        return ex.source
    }

    /**
     * Получить полный HTTP код последней ошибки.
     */
    fun getLastHttpCode(): Int? {
        val ex = _lastException as? IcmApiException ?: return null
        return ex.code
    }

    /**
     * Очистить последнюю ошибку.
     */
    fun clearError() {
        _lastException = null
        _lastError.value = null
    }
}
