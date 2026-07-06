package com.liquidmusicglass.data.wave

sealed interface WaveMode {
    val displayName: String?

    data class Personal(
        override val displayName: String? = "My Wave",
        val region: String? = null
    ) : WaveMode

    data class TrackStation(
        val seedTrackId: String,
        override val displayName: String? = null,
        val region: String? = null
    ) : WaveMode

    data class Genre(
        val genre: String,
        override val displayName: String? = genre,
        val source: String = "apple",
        val region: String? = null,
        val diversity: Double = 0.6
    ) : WaveMode

    data class Mood(
        val mood: String,
        override val displayName: String? = mood,
        val source: String = "apple",
        val region: String? = null,
        val diversity: Double = 0.6
    ) : WaveMode

    data class Session(
        val sessionId: String,
        override val displayName: String? = "My Wave",
        val source: String = "apple",
        val region: String? = null,
        val diversity: Double = 0.6,
        val expiresAtMs: Long? = null
    ) : WaveMode
}
