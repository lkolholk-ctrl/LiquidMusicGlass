package com.liquidmusicglass.api.youtube.models

import kotlinx.serialization.Serializable

/**
 * YouTube client configuration for InnerTube API requests.
 * Based on InnerTune's approach — multiple client profiles for different capabilities.
 */
@Serializable
data class YouTubeClient(
    val clientName: String,
    val clientVersion: String,
    val apiKey: String,
    val userAgent: String,
    val osVersion: String? = null,
    val referer: String? = null,
    val deviceModel: String? = null
) {
    fun toContext(locale: YouTubeLocale, visitorData: String?): YtContext = YtContext(
        client = YtContext.Client(
            clientName = clientName,
            clientVersion = clientVersion,
            osVersion = osVersion,
            gl = locale.gl,
            hl = locale.hl,
            visitorData = visitorData,
            deviceModel = deviceModel
        )
    )

    companion object {
        private const val REFERER_YT_MUSIC = "https://music.youtube.com/"

        private const val UA_ANDROID = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val UA_IOS = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)"

        /**
         * ANDROID_MUSIC — primary client for streaming.
         * Returns audio streams with adaptiveFormats (m4a/webm).
         */
        val ANDROID_MUSIC = YouTubeClient(
            clientName = "ANDROID_MUSIC",
            clientVersion = "6.48.52",
            apiKey = "AIzaSyAOghZGza2MQSZkY_zfZ370N-PUdXEo8AI",
            userAgent = UA_ANDROID,
            deviceModel = "SM-S918B"
        )

        /**
         * WEB_REMIX — for search, browse, next (radio) endpoints.
         * Has the richest UI metadata.
         */
        val WEB_REMIX = YouTubeClient(
            clientName = "WEB_REMIX",
            clientVersion = "1.20241204.01.00",
            apiKey = "AIzaSyC9XL3ZjWEdXiaejLYdd3kjX8WlMMMozmE",
            userAgent = UA_WEB,
            referer = REFERER_YT_MUSIC
        )

        /**
         * IOS — fallback for age-restricted content.
         * Sometimes works when ANDROID_MUSIC fails.
         */
        val IOS = YouTubeClient(
            clientName = "IOS",
            clientVersion = "19.29.1",
            apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
            userAgent = UA_IOS,
            osVersion = "17.5.1.21F90"
        )

        /**
         * TVHTML5 — last-resort fallback for restricted videos.
         * Uses embedded player context.
         */
        val TVHTML5 = YouTubeClient(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            apiKey = "AIzaSyDCU8hByM-4DrUqRUYnGn-3llEO78bcxq8",
            userAgent = "Mozilla/5.0 (PlayStation 4 5.55) AppleWebKit/601.2 (KHTML, like Gecko)"
        )
    }
}

@Serializable
data class YouTubeLocale(
    val gl: String = "US",
    val hl: String = "en"
)
