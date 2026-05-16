package com.liquidmusicglass.api.icm

import com.liquidmusicglass.engine.Track
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
    }

    /**
     * Проверить здоровье API.
     */
    suspend fun health(): Result<IcmHealthResponse> {
        return api.health().also { result ->
            result.exceptionOrNull()?.let { _lastError.value = it.message }
        }
    }

    /**
     * Поиск треков, альбомов, артистов.
     * @return Список треков (только isTrack=true) или все результаты
     */
    suspend fun searchTracks(query: String, region: String? = null): List<Track> {
        if (query.isBlank()) return emptyList()
        val result = api.search(query, region)
        result.exceptionOrNull()?.let { _lastError.value = it.message }
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
        result.exceptionOrNull()?.let { _lastError.value = it.message }
        return result.getOrNull()
    }

    /**
     * Получить подписанный URL для стрима.
     */
    suspend fun getStreamUrl(trackId: String, region: String? = null): String? {
        val result = api.getTrack(trackId, region)
        result.exceptionOrNull()?.let { _lastError.value = it.message }
        return result.getOrNull()?.url
    }

    /**
     * Получить полный TrackResponse (включая expires_at).
     */
    suspend fun getTrackInfo(trackId: String, region: String? = null): IcmTrackResponse? {
        val result = api.getTrack(trackId, region)
        result.exceptionOrNull()?.let { _lastError.value = it.message }
        return result.getOrNull()
    }

    /**
     * Треки альбома как Track.
     */
    suspend fun getAlbumTracks(albumId: String, region: String? = null): List<Track> {
        val result = api.getAlbum(albumId, region)
        result.exceptionOrNull()?.let { _lastError.value = it.message }
        return result.getOrNull()?.tracks?.map { it.toTrack() } ?: emptyList()
    }

    /**
     * Информация об альбоме.
     */
    suspend fun getAlbum(albumId: String, region: String? = null): IcmAlbumResponse? {
        val result = api.getAlbum(albumId, region)
        result.exceptionOrNull()?.let { _lastError.value = it.message }
        return result.getOrNull()
    }

    /**
     * Топ-треки артиста.
     */
    suspend fun getArtistTopTracks(artistId: String, region: String? = null): List<Track> {
        val result = api.getArtist(artistId, region)
        result.exceptionOrNull()?.let { _lastError.value = it.message }
        return result.getOrNull()?.topTracks?.map { it.toTrack() } ?: emptyList()
    }

    /**
     * Информация об артисте.
     */
    suspend fun getArtist(artistId: String, region: String? = null): IcmArtistResponse? {
        val result = api.getArtist(artistId, region)
        result.exceptionOrNull()?.let { _lastError.value = it.message }
        return result.getOrNull()
    }

    /**
     * Метаданные трека.
     */
    suspend fun getTrackMeta(trackId: String): IcmTrackMeta? {
        val result = api.getTrackMeta(trackId)
        result.exceptionOrNull()?.let { _lastError.value = it.message }
        return result.getOrNull()
    }

    /**
     * Плейлист редакционный.
     */
    suspend fun getPlaylist(playlistId: String, region: String? = null): IcmPlaylist? {
        val result = api.getPlaylist(playlistId, region)
        result.exceptionOrNull()?.let { _lastError.value = it.message }
        return result.getOrNull()
    }

    /**
     * Текст песни.
     */
    suspend fun getLyrics(trackId: String): IcmLyricsResponse? {
        val result = api.getLyrics(trackId)
        result.exceptionOrNull()?.let { _lastError.value = it.message }
        return result.getOrNull()
    }
}
