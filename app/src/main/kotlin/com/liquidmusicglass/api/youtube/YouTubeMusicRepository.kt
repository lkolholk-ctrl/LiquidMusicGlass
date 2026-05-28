package com.liquidmusicglass.api.youtube

import android.util.Log
import com.liquidmusicglass.api.youtube.models.YouTubeClient
import com.liquidmusicglass.api.youtube.models.YouTubeLocale
import com.liquidmusicglass.api.youtube.models.YtContext
import com.liquidmusicglass.api.youtube.models.body.YtNextBody
import com.liquidmusicglass.api.youtube.models.body.YtPlayerBody
import com.liquidmusicglass.api.youtube.models.body.YtSearchBody
import com.liquidmusicglass.api.youtube.models.response.YtPlayerResponse.YtFormat
import com.liquidmusicglass.api.youtube.models.response.YtNextResponse
import com.liquidmusicglass.api.youtube.models.response.YtPlayerResponse
import com.liquidmusicglass.api.youtube.models.response.YtPlaylistPanelVideoRenderer
import com.liquidmusicglass.api.youtube.models.response.YtSearchResponse
import com.liquidmusicglass.api.youtube.models.response.YtThumbnail
import com.liquidmusicglass.api.youtube.models.response.YtThumbnails
import com.liquidmusicglass.api.youtube.models.response.YtWatchEndpoint
import com.liquidmusicglass.api.youtube.models.response.YtWatchPlaylistEndpoint
import com.liquidmusicglass.api.youtube.models.response.YtBrowseEndpoint
import com.liquidmusicglass.api.youtube.models.response.YtSearchEndpoint
import com.liquidmusicglass.api.youtube.models.response.YtContinuation
import com.liquidmusicglass.api.youtube.models.response.YtNextContinuationData
import com.liquidmusicglass.api.youtube.models.response.YtReloadContinuationData
import com.liquidmusicglass.api.youtube.models.response.YtBadge
import com.liquidmusicglass.api.youtube.models.response.YtMusicInlineBadgeRenderer
import com.liquidmusicglass.api.youtube.models.response.YtIcon
import com.liquidmusicglass.api.youtube.models.response.YtMenu
import com.liquidmusicglass.api.youtube.models.response.YtMenuRenderer
import com.liquidmusicglass.api.youtube.models.response.YtMenuItem
import com.liquidmusicglass.api.youtube.models.response.YtMenuNavigationItemRenderer
import com.liquidmusicglass.api.youtube.models.response.YtResponseContext
import com.liquidmusicglass.api.youtube.models.response.YtServiceTrackingParam
import com.liquidmusicglass.api.youtube.models.response.YtParam
import com.liquidmusicglass.api.youtube.models.response.YtRuns
import com.liquidmusicglass.api.youtube.models.response.YtRun
import com.liquidmusicglass.api.youtube.models.response.YtNavigationEndpoint
import com.liquidmusicglass.api.youtube.models.response.YtAccessibilityData
import com.liquidmusicglass.api.youtube.models.response.YtAccessibilityLabel
import com.liquidmusicglass.api.youtube.models.response.YtMusicThumbnail
import com.liquidmusicglass.api.youtube.models.response.YtMusicThumbnailRenderer
import com.liquidmusicglass.api.youtube.models.response.YtMusicOverlay
import com.liquidmusicglass.api.youtube.models.response.YtMusicItemThumbnailOverlayRenderer
import com.liquidmusicglass.api.youtube.models.response.YtMusicItemThumbnailOverlayContent
import com.liquidmusicglass.api.youtube.models.response.YtMusicPlayButtonRenderer
import com.liquidmusicglass.api.youtube.models.response.YtFlexColumn
import com.liquidmusicglass.api.youtube.models.response.YtFlexColumnRenderer
import com.liquidmusicglass.api.youtube.models.response.YtPlaylistItemData
import com.liquidmusicglass.api.youtube.models.response.YtMusicCardShelfRenderer
import com.liquidmusicglass.api.youtube.models.response.YtMusicCardShelfHeader
import com.liquidmusicglass.api.youtube.models.response.YtMusicCardShelfHeaderBasic
import com.liquidmusicglass.api.youtube.models.response.YtMusicShelfRenderer
import com.liquidmusicglass.api.youtube.models.response.YtMusicShelfItem
import com.liquidmusicglass.api.youtube.models.response.YtSectionContent
import com.liquidmusicglass.api.youtube.models.response.YtSectionListRenderer
import com.liquidmusicglass.api.youtube.models.response.YtSearchContents
import com.liquidmusicglass.api.youtube.models.response.YtSearchContinuationContents
import com.liquidmusicglass.api.youtube.models.response.YtSearchTab
import com.liquidmusicglass.api.youtube.models.response.YtSearchTabContent
import com.liquidmusicglass.api.youtube.models.response.YtSearchTabRenderer
import com.liquidmusicglass.api.youtube.models.response.YtTabbedSearchResults
import com.liquidmusicglass.api.youtube.models.response.YtNextContents
import com.liquidmusicglass.api.youtube.models.response.YtSingleColumnMusicWatchNextResultsRenderer
import com.liquidmusicglass.api.youtube.models.response.YtTabbedRenderer
import com.liquidmusicglass.api.youtube.models.response.YtWatchNextTabbedResultsRenderer
import com.liquidmusicglass.api.youtube.models.response.YtTab
import com.liquidmusicglass.api.youtube.models.response.YtTabRenderer
import com.liquidmusicglass.api.youtube.models.response.YtTabContent
import com.liquidmusicglass.api.youtube.models.response.YtMusicQueueRenderer
import com.liquidmusicglass.api.youtube.models.response.YtMusicQueueHeader
import com.liquidmusicglass.api.youtube.models.response.YtMusicQueueHeaderRenderer
import com.liquidmusicglass.api.youtube.models.response.YtMusicQueueContent
import com.liquidmusicglass.api.youtube.models.response.YtContinuationContents
import com.liquidmusicglass.api.youtube.models.response.YtPlaylistPanelRenderer
import com.liquidmusicglass.api.youtube.models.response.YtPlaylistPanelContent
import com.liquidmusicglass.api.youtube.models.response.YtAutomixPreviewVideoRenderer
import com.liquidmusicglass.api.youtube.models.response.YtAutomixContent
import com.liquidmusicglass.api.youtube.models.response.YtAutomixPlaylistVideoRenderer
import com.liquidmusicglass.api.youtube.models.response.splitBySeparator
import com.liquidmusicglass.api.youtube.models.response.oddElements
import com.liquidmusicglass.api.youtube.models.response.parseDurationSeconds
import com.liquidmusicglass.engine.Track
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * YouTube Music repository — InnerTube API client.
 *
 * Provides free access to YouTube Music via unofficial InnerTube API:
 * - Search tracks
 * - Get audio streams (m4a / webm Opus)
 * - Build radio queues (next endpoint)
 *
 * Based on InnerTune's innertube module architecture.
 * All streams are free — no subscription required.
 */
class YouTubeMusicRepository private constructor() {

    private val httpClient = createClient()

    private var locale = YouTubeLocale(
        gl = Locale.getDefault().country,
        hl = Locale.getDefault().toLanguageTag()
    )

    private var visitorData: String = "CgtsZG1ySnZiQWtSbyiMjuGSBg%3D%3D"

    // ─── Public API ───

    /**
     * Search YouTube Music for tracks.
     *
     * @param query Search query string
     * @param filter Optional filter: "songs", "videos", "albums", "artists", "playlists"
     * @return List of Track objects ready for playback
     */
    suspend fun search(
        query: String,
        filter: YtSearchFilter? = null
    ): Result<List<YtTrack>> = runCatching {
        Log.d(TAG, "Searching YT Music: '$query' filter=$filter")

        val params = filter?.let { getSearchParams(it) }
        val response = innerTubeSearch(
            client = YouTubeClient.WEB_REMIX,
            query = query,
            params = params
        ).body<YtSearchResponse>()

        parseSearchResults(response)
    }

    /**
     * Get direct audio stream URL for a video.
     *
     * Tries multiple clients in order:
     * 1. ANDROID_MUSIC — best audio quality, returns adaptiveFormats
     * 2. IOS — fallback for age-restricted
     * 3. TVHTML5 — last resort
     *
     * @param videoId YouTube video ID
     * @return Best audio format with direct URL
     */
    suspend fun getAudioStream(videoId: String): Result<YtAudioStream> = runCatching {
        Log.d(TAG, "Getting audio stream for $videoId")

        // Try ANDROID_MUSIC first
        var playerResponse = innerTubePlayer(
            client = YouTubeClient.ANDROID_MUSIC,
            videoId = videoId
        ).body<YtPlayerResponse>()

        if (!playerResponse.isPlayable) {
            Log.w(TAG, "ANDROID_MUSIC failed, trying IOS")
            playerResponse = innerTubePlayer(
                client = YouTubeClient.IOS,
                videoId = videoId
            ).body<YtPlayerResponse>()
        }

        if (!playerResponse.isPlayable) {
            Log.w(TAG, "IOS failed, trying TVHTML5")
            playerResponse = innerTubePlayer(
                client = YouTubeClient.TVHTML5,
                videoId = videoId
            ).body<YtPlayerResponse>()
        }

        if (!playerResponse.isPlayable) {
            val reason = playerResponse.playabilityStatus?.reason ?: "Unknown"
            throw YtMusicException("Video not playable: $reason")
        }

        val format = playerResponse.bestAudioFormat
            ?: throw YtMusicException("No audio format found")

        val url = format.url
            ?: throw YtMusicException("Audio URL is null (signature cipher not supported)")

        YtAudioStream(
            url = url,
            mimeType = format.mimeType,
            bitrate = format.bitrate,
            codec = format.codec,
            contentLength = format.contentLength,
            loudnessDb = format.loudnessDb,
            durationMs = format.approxDurationMs?.toLongOrNull() ?: 0L
        )
    }

    /**
     * Build a radio queue starting from a seed video.
     *
     * Uses the /next endpoint with the seed videoId to get
     * playlistPanelVideoRenderer array — the YT Music radio.
     *
     * @param videoId Seed video ID
     * @return List of tracks in the radio queue
     */
    suspend fun getRadioQueue(
        videoId: String
    ): Result<List<YtTrack>> = runCatching {
        Log.d(TAG, "Building radio queue from $videoId")

        val response = innerTubeNext(
            client = YouTubeClient.WEB_REMIX,
            videoId = videoId
        ).body<YtNextResponse>()

        val panel = response.playlistPanel
            ?: throw YtMusicException("No playlist panel in response")

        val tracks = panel.contents?.mapNotNull { content ->
            content.playlistPanelVideoRenderer?.let { parsePlaylistPanelVideo(it) }
        } ?: emptyList()

        Log.d(TAG, "Radio queue: ${tracks.size} tracks")
        tracks
    }

    /**
     * Get radio queue continuation (infinite scroll).
     */
    suspend fun getRadioContinuation(
        continuation: String
    ): Result<List<YtTrack>> = runCatching {
        val response = innerTubeNext(
            client = YouTubeClient.WEB_REMIX,
            continuation = continuation
        ).body<YtNextResponse>()

        val panel = response.playlistPanel
            ?: throw YtMusicException("No continuation panel")

        panel.contents?.mapNotNull { content ->
            content.playlistPanelVideoRenderer?.let { parsePlaylistPanelVideo(it) }
        } ?: emptyList()
    }

    // ─── InnerTube HTTP layer ───

    @OptIn(ExperimentalSerializationApi::class)
    private fun createClient(): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
                isLenient = true
            })
        }

        install(ContentEncoding) {
            gzip()
            deflate()
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 15000
        }

        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 2)
            exponentialDelay()
        }

        expectSuccess = false
    }

    private suspend fun innerTubeSearch(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null
    ) = httpClient.post("https://music.youtube.com/youtubei/v1/search") {
        ytClientHeaders(client)
        setBody(
            YtSearchBody(
                context = client.toContext(locale, visitorData),
                query = query,
                params = params
            )
        )
        continuation?.let {
            parameter("continuation", it)
            parameter("ctoken", it)
        }
    }

    private suspend fun innerTubePlayer(
        client: YouTubeClient,
        videoId: String,
        playlistId: String? = null
    ) = httpClient.post("https://music.youtube.com/youtubei/v1/player") {
        ytClientHeaders(client, setLogin = true)
        setBody(
            YtPlayerBody(
                context = client.toContext(locale, visitorData).let { ctx ->
                    if (client == YouTubeClient.TVHTML5) {
                        ctx.copy(
                            thirdParty = YtContext.ThirdParty(
                                embedUrl = "https://www.youtube.com/watch?v=$videoId"
                            )
                        )
                    } else ctx
                },
                videoId = videoId,
                playlistId = playlistId
            )
        )
    }

    private suspend fun innerTubeNext(
        client: YouTubeClient,
        videoId: String? = null,
        playlistId: String? = null,
        playlistSetVideoId: String? = null,
        index: Int? = null,
        params: String? = null,
        continuation: String? = null
    ) = httpClient.post("https://music.youtube.com/youtubei/v1/next") {
        ytClientHeaders(client, setLogin = true)
        setBody(
            YtNextBody(
                context = client.toContext(locale, visitorData),
                videoId = videoId,
                playlistId = playlistId,
                playlistSetVideoId = playlistSetVideoId,
                index = index,
                params = params,
                continuation = continuation
            )
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.ytClientHeaders(
        client: YouTubeClient,
        setLogin: Boolean = false
    ) {
        contentType(ContentType.Application.Json)
        header("X-Goog-Api-Format-Version", "1")
        header("X-YouTube-Client-Name", client.clientName)
        header("X-YouTube-Client-Version", client.clientVersion)
        header("x-origin", "https://music.youtube.com")
        client.referer?.let { header("Referer", it) }
        header("User-Agent", client.userAgent)
        parameter("key", client.apiKey)
        parameter("prettyPrint", "false")
    }

    // ─── Parsing ───

    private fun parseSearchResults(response: YtSearchResponse): List<YtTrack> {
        val shelf = response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents?.lastOrNull()
            ?.musicShelfRenderer

        return shelf?.contents?.mapNotNull { item ->
            item.musicResponsiveListItemRenderer?.let { parseMusicResponsiveListItem(it) }
        } ?: emptyList()
    }

    private fun parseMusicResponsiveListItem(
        renderer: com.liquidmusicglass.api.youtube.models.response.YtMusicResponsiveListItemRenderer
    ): YtTrack? {
        val videoId = renderer.playlistItemData?.videoId
            ?: renderer.navigationEndpoint?.videoId
            ?: return null

        val columns = renderer.flexColumns ?: return null
        val title = columns.getOrNull(0)?.musicResponsiveListItemFlexColumnRenderer?.text?.text
            ?: return null
        val subtitle = columns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.text
            ?: ""

        val thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url
            ?: ""

        val isExplicit = renderer.badges?.any {
            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
        } ?: false

        return YtTrack(
            videoId = videoId,
            title = title,
            artist = parseArtistFromSubtitle(subtitle),
            album = parseAlbumFromSubtitle(subtitle),
            durationSeconds = null,
            thumbnail = thumbnail,
            isExplicit = isExplicit
        )
    }

    private fun parsePlaylistPanelVideo(
        renderer: YtPlaylistPanelVideoRenderer
    ): YtTrack? {
        val videoId = renderer.videoId ?: return null
        val title = renderer.title?.text ?: return null

        val longByLine = renderer.longBylineText?.runs
        val artist = longByLine?.splitBySeparator()?.firstOrNull()?.oddElements()
            ?.joinToString(", ") { it.text ?: "" }
            ?: renderer.shortBylineText?.text
            ?: "Unknown Artist"

        val album = longByLine?.splitBySeparator()?.getOrNull(1)?.firstOrNull()?.text

        val duration = renderer.lengthText?.text?.parseDurationSeconds()

        val thumbnail = renderer.thumbnail?.thumbnails?.lastOrNull()?.url ?: ""

        val isExplicit = renderer.badges?.any {
            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
        } ?: false

        return YtTrack(
            videoId = videoId,
            title = title,
            artist = artist,
            album = album,
            durationSeconds = duration,
            thumbnail = thumbnail,
            isExplicit = isExplicit
        )
    }

    private fun parseArtistFromSubtitle(subtitle: String): String {
        return subtitle.split("•").firstOrNull()?.trim() ?: "Unknown Artist"
    }

    private fun parseAlbumFromSubtitle(subtitle: String): String? {
        val parts = subtitle.split("•")
        return parts.getOrNull(1)?.trim()
    }

    private fun getSearchParams(filter: YtSearchFilter): String = when (filter) {
        YtSearchFilter.SONGS -> "EgWKAQIIAWoKEAMQBBAJEAoQBQ%3D%3D"
        YtSearchFilter.VIDEOS -> "EgWKAQIQAWoKEAMQBBAJEAoQBQ%3D%3D"
        YtSearchFilter.ALBUMS -> "EgWKAQIYAWoKEAMQBBAJEAoQBQ%3D%3D"
        YtSearchFilter.ARTISTS -> "EgWKAQIgAWoKEAMQBBAJEAoQBQ%3D%3D"
        YtSearchFilter.PLAYLISTS -> "EgWKAQIoAWoKEAMQBBAJEAoQBQ%3D%3D"
    }

    // ─── Companion ───

    companion object {
        private const val TAG = "YouTubeMusicRepository"

        @Volatile
        private var instance: YouTubeMusicRepository? = null

        fun getInstance(): YouTubeMusicRepository {
            return instance ?: synchronized(this) {
                instance ?: YouTubeMusicRepository().also { instance = it }
            }
        }
    }
}

// ─── Public data classes ───

/**
 * Parsed YouTube Music track — domain model for UI/Player.
 */
data class YtTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int? = null,
    val thumbnail: String,
    val isExplicit: Boolean = false
) {
    val durationMs: Long
        get() = (durationSeconds ?: 0) * 1000L

    val shareUrl: String
        get() = "https://music.youtube.com/watch?v=$videoId"

    /**
     * Convert to LiquidMusicGlass Track for PlayerController.
     */
    fun toEngineTrack(streamUrl: String): Track = Track(
        id = videoId,
        title = title,
        artist = artist,
        albumName = album ?: "Single",
        uri = android.net.Uri.parse(streamUrl),
        durationMs = durationMs,
        albumId = videoId.hashCode().toLong(),
        coverUrl = thumbnail,
        artists = emptyList(),
        isExplicit = isExplicit,
        isCustom = false,
        source = "youtube"
    )
}

/**
 * Resolved audio stream metadata.
 */
data class YtAudioStream(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val codec: String,
    val contentLength: Long? = null,
    val loudnessDb: Double? = null,
    val durationMs: Long = 0L
) {
    val bitrateKbps: Int
        get() = bitrate / 1000
}

/**
 * Search filter categories.
 */
enum class YtSearchFilter {
    SONGS, VIDEOS, ALBUMS, ARTISTS, PLAYLISTS
}

/**
 * YouTube Music API exception.
 */
class YtMusicException(message: String) : Exception(message)
