package com.liquidmusicglass.api.icm.wave

import com.liquidmusicglass.api.icm.IcmApi
import com.liquidmusicglass.api.icm.IcmWaveFeedbackRequest
import com.liquidmusicglass.api.icm.IcmWaveFeedbackResponse
import com.liquidmusicglass.api.icm.IcmWaveResponse
import java.net.URLEncoder
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class IcmWaveApi(
    private val api: IcmApi = IcmApi.getInstance()
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * Явный старт личной волны: GET /library/wave/start — алиас
     * /library/wave/next без exclude/seed. Один лёгкий запрос → первый трек,
     * серверная 4-часовая сессия показов учитывается.
     */
    suspend fun startWave(region: String? = null): Result<IcmWaveResponse> {
        val endpoint = buildString {
            append("/library/wave/start")
            appendQuery(queryParam("region", region))
        }
        return request(endpoint)
    }

    suspend fun nextTrackStation(
        seedTrackId: String,
        exclude: List<String> = emptyList(),
        region: String? = null
    ): Result<IcmWaveResponse> {
        val endpoint = buildString {
            append("/library/wave/next")
            appendQuery(
                queryParam("seed_track_id", seedTrackId),
                queryParam("exclude", exclude.toCsvOrNull()),
                queryParam("region", region)
            )
        }
        return request(endpoint)
    }

    suspend fun startSession(
        request: IcmWaveSessionStartRequest = IcmWaveSessionStartRequest()
    ): Result<IcmWaveSessionStartResponse> {
        return request(
            endpoint = "/wave/session/start",
            method = "POST",
            body = json.encodeToString(request)
        )
    }

    suspend fun nextSessionBatch(
        sessionId: String,
        request: IcmWaveBatchRequest = IcmWaveBatchRequest()
    ): Result<IcmWaveBatchResponse> {
        return request(
            endpoint = "/wave/session/${encodePath(sessionId)}/next",
            method = "POST",
            body = json.encodeToString(request.normalized())
        )
    }

    suspend fun sendSessionFeedback(
        sessionId: String,
        feedbackType: String,
        value: String
    ): Result<IcmWaveFeedbackResponse> {
        return request(
            endpoint = "/wave/session/${encodePath(sessionId)}/feedback",
            method = "POST",
            body = json.encodeToString(IcmWaveFeedbackRequest(feedbackType, value))
        )
    }

    suspend fun nextBatch(
        request: IcmWaveBatchRequest = IcmWaveBatchRequest()
    ): Result<IcmWaveBatchResponse> {
        return request(
            endpoint = "/wave/next",
            method = "POST",
            body = json.encodeToString(request.normalized())
        )
    }

    suspend fun genre(
        genre: String,
        limit: Int? = null,
        diversity: Double? = null,
        source: String? = null,
        region: String? = null,
        excludeTrackIds: List<String> = emptyList(),
        excludeArtistIds: List<String> = emptyList(),
        playedTrackIds: List<String> = emptyList()
    ): Result<IcmWaveBatchResponse> {
        val endpoint = buildString {
            append("/wave/genre/")
            append(encodePath(genre))
            appendQuery(
                queryParam("limit", limit?.coerceIn(1, 50)?.toString()),
                queryParam("diversity", diversity?.coerceIn(0.0, 1.0)?.toString()),
                queryParam("source", source),
                queryParam("region", region),
                queryParam("exclude_track_ids", excludeTrackIds.toCsvOrNull()),
                queryParam("exclude_artist_ids", excludeArtistIds.toCsvOrNull()),
                queryParam("played_track_ids", playedTrackIds.toCsvOrNull())
            )
        }
        return request(endpoint)
    }

    suspend fun mood(
        mood: String,
        limit: Int? = null,
        diversity: Double? = null,
        source: String? = null,
        region: String? = null,
        excludeTrackIds: List<String> = emptyList(),
        excludeArtistIds: List<String> = emptyList(),
        playedTrackIds: List<String> = emptyList()
    ): Result<IcmWaveBatchResponse> {
        val endpoint = buildString {
            append("/wave/mood/")
            append(encodePath(mood))
            appendQuery(
                queryParam("limit", limit?.coerceIn(1, 50)?.toString()),
                queryParam("diversity", diversity?.coerceIn(0.0, 1.0)?.toString()),
                queryParam("source", source),
                queryParam("region", region),
                queryParam("exclude_track_ids", excludeTrackIds.toCsvOrNull()),
                queryParam("exclude_artist_ids", excludeArtistIds.toCsvOrNull()),
                queryParam("played_track_ids", playedTrackIds.toCsvOrNull())
            )
        }
        return request(endpoint)
    }

    suspend fun sendFeedbackAlias(
        alias: IcmWaveFeedbackAlias,
        request: IcmWaveAliasFeedbackRequest
    ): Result<IcmWaveAliasFeedbackResponse> {
        return request(
            endpoint = "/wave/feedback/${alias.path}",
            method = "POST",
            body = json.encodeToString(request)
        )
    }

    private suspend inline fun <reified T> request(
        endpoint: String,
        method: String = "GET",
        body: String? = null
    ): Result<T> {
        return api.requestJson(endpoint, method, body).mapCatching { bodyText ->
            // DIAG (wave-diag build): сырой ответ волны в «Copy ICM logs», обрезан.
            // TolerantListSerializer молча выкидывает треки, не совпавшие с
            // IcmWaveTrack (→ tracks=[] без ошибки). Нужен реальный JSON, чтобы
            // увидеть форму треков и почему сервер 200, а клиент — ноль.
            if (endpoint.contains("/wave/")) {
                com.liquidmusicglass.api.icm.IcmApiFileLogger.log(
                    "D", "WaveBody", "$endpoint => ${bodyText.take(700)}"
                )
            }
            json.decodeFromString<T>(bodyText)
        }
    }

    private fun IcmWaveBatchRequest.normalized(): IcmWaveBatchRequest {
        return copy(
            limit = limit?.coerceIn(1, 50),
            diversity = diversity?.coerceIn(0.0, 1.0)
        )
    }

    private fun StringBuilder.appendQuery(vararg params: String?) {
        val query = params.filterNotNull()
        if (query.isNotEmpty()) {
            append("?")
            append(query.joinToString("&"))
        }
    }

    private fun queryParam(name: String, value: String?): String? {
        val clean = value?.takeIf { it.isNotBlank() } ?: return null
        return "$name=${encodeQuery(clean)}"
    }

    private fun List<String>.toCsvOrNull(): String? {
        return map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
    }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}

enum class IcmWaveFeedbackAlias(val path: String) {
    MoreLikeThis("more-like-this"),
    LikeTrack("like-track"),
    LikeArtist("like-artist"),
    LessLikeThis("less-like-this"),
    DislikeTrack("dislike-track"),
    DislikeArtist("dislike-artist"),
    Skip("skip")
}
