package com.liquidmusicglass.api.icm.wave

import com.liquidmusicglass.api.icm.IcmWaveTrack
import com.liquidmusicglass.api.icm.TolerantListSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IcmWaveSessionStartRequest(
    @SerialName("source") val source: String? = null,
    @SerialName("region") val region: String? = null,
    @SerialName("diversity") val diversity: Double? = null
)

@Serializable
data class IcmWaveSessionStartResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("expires_in") val expiresIn: Int? = null,
    @SerialName("region") val region: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("diversity") val diversity: Double? = null
)

@Serializable
data class IcmWaveBatchRequest(
    @SerialName("limit") val limit: Int? = null,
    @SerialName("diversity") val diversity: Double? = null,
    @SerialName("exclude_track_ids") val excludeTrackIds: List<String> = emptyList(),
    @SerialName("exclude_artist_ids") val excludeArtistIds: List<String> = emptyList(),
    @SerialName("played_track_ids") val playedTrackIds: List<String> = emptyList(),
    @SerialName("source") val source: String? = null,
    @SerialName("region") val region: String? = null
)

@Serializable
data class IcmWaveBatchResponse(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("mood") val mood: String? = null,
    @SerialName("count") val count: Int? = null,
    @SerialName("status") val status: String = "",
    @SerialName("region") val region: String? = null,
    @SerialName("source") val source: String? = null,
    @Serializable(with = TolerantListSerializer::class)
    @SerialName("tracks") val tracks: List<IcmWaveTrack> = emptyList()
)

@Serializable
data class IcmWaveAliasFeedbackRequest(
    @SerialName("value") val value: String? = null,
    @SerialName("track_id") val trackId: String? = null,
    @SerialName("artist_id") val artistId: String? = null,
    @SerialName("total_seconds") val totalSeconds: Double? = null
)

@Serializable
data class IcmWaveAliasFeedbackResponse(
    @SerialName("status") val status: String? = null,
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("logged") val logged: Boolean = false
)
