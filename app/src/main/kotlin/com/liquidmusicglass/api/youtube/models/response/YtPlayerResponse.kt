package com.liquidmusicglass.api.youtube.models.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from /player endpoint — contains streaming URLs.
 */
@Serializable
data class YtPlayerResponse(
    val responseContext: YtResponseContext? = null,
    val playabilityStatus: YtPlayabilityStatus? = null,
    val playerConfig: YtPlayerConfig? = null,
    val streamingData: YtStreamingData? = null,
    val videoDetails: YtVideoDetails? = null
) {
    val isPlayable: Boolean
        get() = playabilityStatus?.status == "OK"

    /**
     * Best audio-only format for streaming.
     * Only considers formats with direct URLs (not signature cipher).
     * Prefers Opus (webm) over AAC (m4a) at same tier, then by bitrate.
     */
    val bestAudioFormat: YtFormat?
        get() = streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.url != null }
            ?.maxByOrNull { it.bitrate + (if (it.isOpus) 10240 else 0) }

    @Serializable
    data class YtPlayabilityStatus(
        val status: String,
        val reason: String? = null,
        val errorScreen: YtErrorScreen? = null
    )

    @Serializable
    data class YtErrorScreen(
        val playerErrorMessageRenderer: YtPlayerErrorMessageRenderer? = null
    )

    @Serializable
    data class YtPlayerErrorMessageRenderer(
        val reason: YtRuns? = null
    )

    @Serializable
    data class YtPlayerConfig(
        val audioConfig: YtAudioConfig? = null
    ) {
        @Serializable
        data class YtAudioConfig(
            val loudnessDb: Double? = null,
            val perceptualLoudnessDb: Double? = null
        )
    }

    @Serializable
    data class YtStreamingData(
        val expiresInSeconds: Int? = null,
        val formats: List<YtFormat>? = null,
        @SerialName("adaptiveFormats")
        val adaptiveFormats: List<YtFormat> = emptyList()
    )

    @Serializable
    data class YtFormat(
        val itag: Int,
        val url: String? = null,
        @SerialName("mimeType")
        val mimeType: String,
        val bitrate: Int,
        val width: Int? = null,
        val height: Int? = null,
        val contentLength: Long? = null,
        val quality: String? = null,
        val fps: Int? = null,
        val qualityLabel: String? = null,
        val averageBitrate: Int? = null,
        @SerialName("audioQuality")
        val audioQuality: String? = null,
        val approxDurationMs: String? = null,
        val audioSampleRate: Int? = null,
        val audioChannels: Int? = null,
        val loudnessDb: Double? = null,
        val signatureCipher: String? = null,
        val lastModified: Long? = null
    ) {
        val isAudio: Boolean
            get() = width == null && height == null

        val isOpus: Boolean
            get() = mimeType.contains("opus", ignoreCase = true)

        val isAac: Boolean
            get() = mimeType.contains("mp4a", ignoreCase = true)

        val codec: String
            get() = when {
                isOpus -> "opus"
                isAac -> "aac"
                mimeType.contains("vorbis") -> "vorbis"
                else -> "unknown"
            }

        val bitrateKbps: Int
            get() = bitrate / 1000
    }

    @Serializable
    data class YtVideoDetails(
        val videoId: String,
        val title: String,
        val author: String,
        val channelId: String? = null,
        val lengthSeconds: String? = null,
        @SerialName("musicVideoType")
        val musicVideoType: String? = null,
        val viewCount: String? = null,
        val thumbnail: YtThumbnails? = null
    )
}
