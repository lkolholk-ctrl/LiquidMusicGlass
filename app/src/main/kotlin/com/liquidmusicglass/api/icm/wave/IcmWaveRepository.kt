package com.liquidmusicglass.api.icm.wave

import com.liquidmusicglass.api.icm.IcmWaveResponse
import com.liquidmusicglass.engine.Track

object IcmWaveRepository {

    private val api = IcmWaveApi()

    fun isAppleSeedTrackId(trackId: String): Boolean =
        trackId.isNotBlank() && trackId.all { it.isDigit() }

    suspend fun nextTrackStation(
        seedTrackId: String,
        exclude: List<String> = emptyList(),
        region: String? = null
    ): Result<IcmWaveResponse> {
        if (!isAppleSeedTrackId(seedTrackId)) {
            return Result.failure(
                IllegalArgumentException("Track station requires numeric Apple Music track id")
            )
        }
        return api.nextTrackStation(seedTrackId, exclude, region)
    }

    suspend fun startSession(
        source: String? = null,
        region: String? = null,
        diversity: Double? = null
    ): Result<IcmWaveSessionStartResponse> {
        return api.startSession(
            IcmWaveSessionStartRequest(
                source = source,
                region = region,
                diversity = diversity
            )
        )
    }

    suspend fun nextSessionBatch(
        sessionId: String,
        limit: Int = 10,
        diversity: Double? = null,
        excludeTrackIds: List<String> = emptyList(),
        excludeArtistIds: List<String> = emptyList(),
        playedTrackIds: List<String> = emptyList(),
        region: String? = null
    ): Result<IcmWaveBatchResponse> {
        return api.nextSessionBatch(
            sessionId = sessionId,
            request = IcmWaveBatchRequest(
                limit = limit,
                diversity = diversity,
                excludeTrackIds = excludeTrackIds,
                excludeArtistIds = excludeArtistIds,
                playedTrackIds = playedTrackIds,
                region = region
            )
        )
    }

    suspend fun sendSessionFeedback(
        sessionId: String,
        feedbackType: String,
        value: String
    ) = api.sendSessionFeedback(sessionId, feedbackType, value)

    suspend fun nextBatch(
        limit: Int = 10,
        diversity: Double? = null,
        excludeTrackIds: List<String> = emptyList(),
        excludeArtistIds: List<String> = emptyList(),
        playedTrackIds: List<String> = emptyList(),
        region: String? = null
    ): Result<IcmWaveBatchResponse> {
        return api.nextBatch(
            IcmWaveBatchRequest(
                limit = limit,
                diversity = diversity,
                excludeTrackIds = excludeTrackIds,
                excludeArtistIds = excludeArtistIds,
                playedTrackIds = playedTrackIds,
                region = region
            )
        )
    }

    suspend fun genreBatch(
        genre: String,
        limit: Int = 10,
        diversity: Double? = 0.6,
        source: String? = "apple",
        region: String? = null,
        excludeTrackIds: List<String> = emptyList(),
        excludeArtistIds: List<String> = emptyList(),
        playedTrackIds: List<String> = emptyList()
    ): Result<IcmWaveBatchResponse> {
        return api.genre(
            genre = genre,
            limit = limit,
            diversity = diversity,
            source = source,
            region = region,
            excludeTrackIds = excludeTrackIds,
            excludeArtistIds = excludeArtistIds,
            playedTrackIds = playedTrackIds
        )
    }

    suspend fun genreTracks(
        genre: String,
        limit: Int = 10,
        diversity: Double? = 0.6,
        source: String? = "apple",
        region: String? = null,
        excludeTrackIds: List<String> = emptyList(),
        excludeArtistIds: List<String> = emptyList(),
        playedTrackIds: List<String> = emptyList()
    ): Result<List<Track>> {
        return genreBatch(
            genre = genre,
            limit = limit,
            diversity = diversity,
            source = source,
            region = region,
            excludeTrackIds = excludeTrackIds,
            excludeArtistIds = excludeArtistIds,
            playedTrackIds = playedTrackIds
        ).mapCatching { response ->
            response.tracks.map { it.toTrack() }
        }
    }

    suspend fun moodBatch(
        mood: String,
        limit: Int = 10,
        diversity: Double? = 0.6,
        source: String? = "apple",
        region: String? = null,
        excludeTrackIds: List<String> = emptyList(),
        excludeArtistIds: List<String> = emptyList(),
        playedTrackIds: List<String> = emptyList()
    ): Result<IcmWaveBatchResponse> {
        return api.mood(
            mood = mood,
            limit = limit,
            diversity = diversity,
            source = source,
            region = region,
            excludeTrackIds = excludeTrackIds,
            excludeArtistIds = excludeArtistIds,
            playedTrackIds = playedTrackIds
        )
    }

    suspend fun moodTracks(
        mood: String,
        limit: Int = 10,
        diversity: Double? = 0.6,
        source: String? = "apple",
        region: String? = null,
        excludeTrackIds: List<String> = emptyList(),
        excludeArtistIds: List<String> = emptyList(),
        playedTrackIds: List<String> = emptyList()
    ): Result<List<Track>> {
        return moodBatch(
            mood = mood,
            limit = limit,
            diversity = diversity,
            source = source,
            region = region,
            excludeTrackIds = excludeTrackIds,
            excludeArtistIds = excludeArtistIds,
            playedTrackIds = playedTrackIds
        ).mapCatching { response ->
            response.tracks.map { it.toTrack() }
        }
    }

    suspend fun sendFeedbackAlias(
        alias: IcmWaveFeedbackAlias,
        value: String,
        totalSeconds: Double? = null
    ): Result<IcmWaveAliasFeedbackResponse> {
        return api.sendFeedbackAlias(
            alias,
            IcmWaveAliasFeedbackRequest(
                value = value,
                totalSeconds = totalSeconds
            )
        )
    }
}
