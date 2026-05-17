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
 * ICM Music Partner API клиент.
 * Документация: https://byicloud.online/partners/api-docs
 *
 * Использует API-ключ (X-Partner-Key) или session token (Authorization: Bearer).
 *
 * Для получения ключа: https://byicloud.online/partners
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    /** API-ключ партнера (pk_<id>_<random>) */
    var apiKey: String? = null

    /** Session token (JWT) — альтернатива apiKey для клиентских запросов */
    var sessionToken: String? = null

    /** Регион по умолчанию для поиска */
    var defaultRegion: String = "us"

    /** Качество стрима: "128K", "256K", "320K", "ALAC" или null для дефолта */
    var streamQuality: String? = IcmStreamQuality.K256

    /** Partner user id для аналитики и per-user настроек (X-Partner-User-Id) */
    var partnerUserId: String? = null

    /** Callback для X-Request-Id tracing */
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

        // Авторизация: приоритет у session token
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
     * Проверка здоровья API и конфигурации ключа.
     */
    suspend fun health(): Result<IcmHealthResponse> = execute("/health")

    /**
     * Выпуск session token для клиентских запросов.
     * Требует apiKey.
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
     * Поиск треков, альбомов и артистов.
     * @param query Строка поиска (до 200 символов)
     * @param region Регион (us/ru/nz), null — используется defaultRegion
     */
    suspend fun search(
        query: String,
        region: String? = null
    ): Result<IcmSearchResponse> {
        val r = region ?: defaultRegion
        val encQuery = java.net.URLEncoder.encode(query, "UTF-8")
        return execute("/search?q=$encQuery&region=$r")
    }

    /**
     * Получить подписанный URL для проигрывания трека.
     * @param trackId ID трека из поиска/альбома
     * @param region Регион
     * @param quality Качество: "256K", "128K" или null
     * @return IcmTrackResponse с полем url для стрима
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
     * Информация об альбоме + список треков.
     */
    suspend fun getAlbum(
        albumId: String,
        region: String? = null
    ): Result<IcmAlbumResponse> {
        val r = region ?: defaultRegion
        return execute("/album/$albumId?region=$r")
    }

    /**
     * Информация об артисте: топ-треки, альбомы, похожие.
     */
    suspend fun getArtist(
        artistId: String,
        region: String? = null
    ): Result<IcmArtistResponse> {
        val r = region ?: defaultRegion
        return execute("/artist/$artistId?region=$r")
    }

    /**
     * Метаданные трека (без получения URL).
     */
    suspend fun getTrackMeta(trackId: String): Result<IcmTrackMeta> =
        execute("/track/$trackId/meta")

    /**
     * Плейлист редакционный Apple Music (Today Hits, etc).
     * ICM API использует тот же эндпоинт /album/{id} для плейлистов (id начинается с pl.).
     */
    suspend fun getPlaylist(
        playlistId: String,
        region: String? = null
    ): Result<IcmAlbumResponse> {
        val r = region ?: defaultRegion
        return execute("/album/$playlistId?region=$r")
    }

    /**
     * Подписать URL обложки (для custom-обложек).
     * Для Apple Music обложек подпись не нужна — cover URL можно использовать напрямую.
     */
    suspend fun signCover(fileId: String): Result<IcmCoverSignResponse> {
        val encFileId = java.net.URLEncoder.encode(fileId, "UTF-8")
        return execute("/cover-sign?file_id=$encFileId")
    }

    /**
     * Текст песни.
     */
    suspend fun getLyrics(trackId: String): Result<IcmLyricsResponse> =
        execute("/lyrics?trackId=$trackId")

    // ═══════════════════════════════════════════════════════════
    //  Batch & Async
    // ═══════════════════════════════════════════════════════════

    /**
     * Batch метаданные треков — до 50 за запрос.
     */
    suspend fun getBatchTrackMeta(trackIds: List<String>): Result<IcmBatchTrackMetaResponse> {
        if (trackIds.isEmpty()) return Result.failure(IllegalArgumentException("trackIds must not be empty"))
        if (trackIds.size > 50) return Result.failure(IllegalArgumentException("trackIds max 50, got ${trackIds.size}"))
        val body = json.encodeToString(IcmBatchTrackMetaRequest(trackIds = trackIds))
        return execute("/tracks/meta", method = "POST", body = body)
    }

    /**
     * Получить трек в async-режиме.
     * Если трек холодный — вернёт 202 с job_id для polling.
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
     * Проверить статус async job.
     */
    suspend fun pollAsyncJob(jobId: String): Result<IcmAsyncTrackReady> {
        return execute("/track/job/$jobId")
    }

    // ═══════════════════════════════════════════════════════════
    //  Account Linking
    // ═══════════════════════════════════════════════════════════

    /**
     * Сгенерировать URL для привязки аккаунта пользователя к ICM.
     * @param partnerUserId ID пользователя в твоей системе
     * @param redirectUri URI для callback после авторизации
     * @param state Случайная строка для защиты от CSRF
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
     * Парсить callback от ICM после линковки.
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
}

/**
 * Исключение API с HTTP-кодом.
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
 * Async pending exception — трек ещё готовится.
 */
class IcmAsyncPendingException(
    val pending: IcmAsyncTrackPending
) : Exception("Track pending: job ${pending.jobId}, poll after ${pending.pollAfterSeconds}s")
