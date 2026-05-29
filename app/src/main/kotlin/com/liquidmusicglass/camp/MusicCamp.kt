package com.liquidmusicglass.camp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed class representing the two music camps (providers) in LiquidMusicGlass.
 *
 * ICM     — Paid subscription-based camp (Apple Music / VK / Custom sources via partner API).
 *           Requires active subscription for downloads and HD streaming.
 *
 * YOUTUBE — Free camp via YouTube Music InnerTube API.
 *           Always allows downloads (cache) and streams at 128K Opus.
 */
@Serializable
sealed class MusicCamp(
    val id: String,
    val displayName: String,
    val description: String
) {

    /**
     * ICM Camp — Premium subscription required for full feature set.
     */
    @Serializable
    @SerialName("icm")
    data object Icm : MusicCamp(
        id = "icm",
        displayName = "ICM Music",
        description = "Premium catalog with Hi-Res streaming"
    )

    /**
     * YouTube Music Camp — Free, ad-free via InnerTube API.
     */
    @Serializable
    @SerialName("youtube")
    data object Youtube : MusicCamp(
        id = "youtube",
        displayName = "YouTube Music",
        description = "Free unlimited music via YouTube"
    )

    companion object {
        /**
         * All available camps in order of display.
         */
        val ALL: List<MusicCamp> = listOf(Icm)

        /**
         * Default camp on first launch.
         */
        val DEFAULT: MusicCamp = Icm

        /**
         * Resolve camp from string ID.
         */
        fun fromId(id: String): MusicCamp = when (id.lowercase()) {
            "icm" -> Icm
            else -> DEFAULT
        }
    }
}

/**
 * Per-camp capability flags. Each camp defines its own hard constraints
 * independent of any external subscription state.
 */
@Serializable
data class CampCapabilities(
    /** Can the user download tracks for offline playback? */
    val canDownload: Boolean,

    /** Max audio bitrate this camp can deliver (display string, e.g. "256K", "128K"). */
    val maxBitrate: String,

    /** Actual codec / quality label for the stream. */
    val streamQualityLabel: String,

    /** Can the user create and manage personal playlists? */
    val canCreatePlaylists: Boolean,

    /** Does this camp support the "My Wave" / radio feature? */
    val hasRadio: Boolean,

    /** Does this camp support lyrics? */
    val hasLyrics: Boolean,

    /** Does this camp support crossfade/gapless? */
    val hasCrossfade: Boolean,

    /** Is this camp free (no subscription check ever)? */
    val isFree: Boolean
) {
    companion object {
        /**
         * ICM capabilities — subscription-gated downloads, up to 256K.
         * Download flag is OVERRIDDEN at runtime by FeatureAccessManager
         * based on IcmSubscriptionResponse.
         */
        val ICM_DEFAULT = CampCapabilities(
            canDownload = false,      // overridden by subscription status
            maxBitrate = "256K",
            streamQualityLabel = "AAC 256K",
            canCreatePlaylists = true,
            hasRadio = true,
            hasLyrics = true,
            hasCrossfade = true,
            isFree = false
        )

        /**
         * YouTube Music capabilities — always free, 128K Opus.
         */
        val YOUTUBE_DEFAULT = CampCapabilities(
            canDownload = true,       // always true — free cache
            maxBitrate = "128K",
            streamQualityLabel = "Opus 128K",
            canCreatePlaylists = true,
            hasRadio = true,
            hasLyrics = true,
            hasCrossfade = false,     // YT streams have variable gaps
            isFree = true
        )
    }
}

/**
 * Extension to get capabilities for any camp.
 */
fun MusicCamp.capabilities(): CampCapabilities = when (this) {
    is MusicCamp.Icm -> CampCapabilities.ICM_DEFAULT
    is MusicCamp.Youtube -> CampCapabilities.YOUTUBE_DEFAULT
}
