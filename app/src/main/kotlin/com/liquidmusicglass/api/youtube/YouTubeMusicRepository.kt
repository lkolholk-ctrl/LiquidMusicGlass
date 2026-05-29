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
import com.liquidmusicglass.api.youtube.models.response.YtItemSectionRenderer
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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.headers
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

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    private val httpClient = createClient()

    private var locale = YouTubeLocale(
    gl = "US",
    hl = "en"
    )
    private var visitorData: String = "CgtsZG1ySnZiQWtSbyiMjuGSBg=="

    // ─── Public API ───

    /**
     * Search YouTube Music for tracks, albums, artists, playlists.
     *
     * @param query Search query string
     * @param filter Optional filter: SONGS, VIDEOS, ALBUMS, ARTISTS, PLAYLISTS
     * @return Search results with mixed item types
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
        )

        parseSearchResults(parseResponse<YtSearchResponse>(response))
    }

    /**
     * Full search returning all item types (tracks, albums, artists, playlists).
     */
    suspend fun searchAll(
        query: String,
        filter: YtSearchFilter? = null
    ): Result<YtSearchResult> = runCatching {
        Log.d(TAG, "Full search YT Music: '$query' filter=$filter")

        val params = filter?.let { getSearchParams(it) }
        val response = innerTubeSearch(
            client = YouTubeClient.WEB_REMIX,
            query = query,
            params = params
        )

        parseSearchResultsFull(parseResponse<YtSearchResponse>(response))
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

        // 1. Пробуем ANDROID_MUSIC (вдруг проскочит на каких-то треках)
        var response = innerTubePlayer(
            client = YouTubeClient.ANDROID_MUSIC,
            videoId = videoId
        )
        var playerResponse = parseResponse<YtPlayerResponse>(response)

        // 2. ВСТАВЛЯЕМ НАШ СВЯТОЙ WEB_REMIX, ЕСЛИ МОБИЛКА ОТВАЛИЛАСЬ
        if (!playerResponse.isPlayable) {
            Log.w(TAG, "ANDROID_MUSIC failed, trying WEB_REMIX")
            val webResponse = innerTubePlayer(
                client = YouTubeClient.WEB_REMIX,
                videoId = videoId
            )
            playerResponse = parseResponse<YtPlayerResponse>(webResponse)
        }

        // 3. Старые мобильные фолбеки (пусть висят для подстраховки)
        if (!playerResponse.isPlayable) {
            Log.w(TAG, "WEB_REMIX failed, trying IOS")
            val iosResponse = innerTubePlayer(
                client = YouTubeClient.IOS,
                videoId = videoId
            )
            playerResponse = parseResponse<YtPlayerResponse>(iosResponse)
        }

        if (!playerResponse.isPlayable) {
            Log.w(TAG, "IOS failed, trying TVHTML5")
            val tvResponse = innerTubePlayer(
                client = YouTubeClient.TVHTML5,
                videoId = videoId
            )
            playerResponse = parseResponse<YtPlayerResponse>(tvResponse)
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
        )
        val nextResponse = parseResponse<YtNextResponse>(response)

        val panel = nextResponse.playlistPanel
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
        )
        val nextResponse = parseResponse<YtNextResponse>(response)

        val panel = nextResponse.playlistPanel
            ?: throw YtMusicException("No continuation panel")

        panel.contents?.mapNotNull { content ->
            content.playlistPanelVideoRenderer?.let { parsePlaylistPanelVideo(it) }
        } ?: emptyList()
    }

    // ─── Browse API ───

    /**
     * Get the YouTube Music Explore page (home).
     * Returns new release albums and mood/genre categories.
     */
    suspend fun explore(): Result<YtExplorePage> = runCatching {
        Log.d(TAG, "Fetching YouTube Music explore page")
        val response = innerTubeBrowse(
            client = YouTubeClient.WEB_REMIX,
            browseId = "FEmusic_explore"
        )
        val browseResponse = parseResponse<com.liquidmusicglass.api.youtube.models.response.YtBrowseResponse>(response)
        parseExplorePage(browseResponse)
    }

    /**
     * Get mood and genres categories.
     */
    suspend fun moodAndGenres(): Result<List<YtMoodItem>> = runCatching {
        Log.d(TAG, "Fetching mood and genres")
        val response = innerTubeBrowse(
            client = YouTubeClient.WEB_REMIX,
            browseId = "FEmusic_moods_and_genres"
        )
        val browseResponse = parseResponse<com.liquidmusicglass.api.youtube.models.response.YtBrowseResponse>(response)
        parseMoodAndGenres(browseResponse)
    }

    /**
     * Browse a specific category (mood, genre, or any browseId).
     * Returns a generic BrowseResult with sections of items.
     */
    suspend fun browse(
        browseId: String,
        params: String? = null
    ): Result<YtBrowseResult> = runCatching {
        Log.d(TAG, "Browsing: $browseId params=$params")
        val response = innerTubeBrowse(
            client = YouTubeClient.WEB_REMIX,
            browseId = browseId,
            params = params
        )
        val browseResponse = parseResponse<com.liquidmusicglass.api.youtube.models.response.YtBrowseResponse>(response)
        parseBrowseResult(browseResponse)
    }

    /**
     * Get album detail with tracklist.
     */
    suspend fun album(browseId: String): Result<YtAlbumPage> = runCatching {
        Log.d(TAG, "Fetching album: $browseId")
        val response = innerTubeBrowse(
            client = YouTubeClient.WEB_REMIX,
            browseId = browseId
        )
        val browseResponse = parseResponse<com.liquidmusicglass.api.youtube.models.response.YtBrowseResponse>(response)
        parseAlbumPage(browseResponse)
    }

    /**
     * Get artist detail with sections (songs, albums, related).
     */
    suspend fun artist(browseId: String): Result<YtArtistPage> = runCatching {
        Log.d(TAG, "Fetching artist: $browseId")
        val response = innerTubeBrowse(
            client = YouTubeClient.WEB_REMIX,
            browseId = browseId
        )
        val browseResponse = parseResponse<com.liquidmusicglass.api.youtube.models.response.YtBrowseResponse>(response)
        parseArtistPage(browseResponse)
    }

    /**
     * Get playlist songs.
     */
    suspend fun playlist(playlistId: String): Result<YtAlbumPage> = runCatching {
        Log.d(TAG, "Fetching playlist: $playlistId")
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        val response = innerTubeBrowse(
            client = YouTubeClient.WEB_REMIX,
            browseId = browseId
        )
        val browseResponse = parseResponse<com.liquidmusicglass.api.youtube.models.response.YtBrowseResponse>(response)
        parseAlbumPage(browseResponse)
    }

    // ─── InnerTube HTTP layer ───

    @OptIn(ExperimentalSerializationApi::class)
    private fun createClient(): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(this@YouTubeMusicRepository.json)
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

    private suspend inline fun <reified T> parseResponse(httpResponse: HttpResponse): T {
        val rawJson = httpResponse.body<String>()
        Log.d("YTM_NET", "Raw JSON Response for ${T::class.java.simpleName} (length=${rawJson.length}):\n${rawJson.take(3000)}")
        try {
            return json.decodeFromString<T>(rawJson)
        } catch (e: Exception) {
            Log.e("YTM_PARSE", "Serialization error parsing ${T::class.java.simpleName}", e)
            throw e
        }
    }

        private suspend fun innerTubeSearch(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null
    ) = httpClient.post("https://music.youtube.com/youtubei/v1/search") {
        ytClientHeaders(client)
        
        val bodyObj = YtSearchBody(
            context = client.toContext(locale, visitorData),
            query = query,
            params = params
        )
        
        // Встраиваем перехватчик тела запроса для вывода в логкат
        try {
            val reqJson = json.encodeToString(YtSearchBody.serializer(), bodyObj)
            Log.d("YTM_REQ", "Raw Request JSON Payload:\n$reqJson")
        } catch (e: Exception) {
            Log.e("YTM_REQ", "Failed to serialize request body", e)
        }

        setBody(bodyObj)
        
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

    private suspend fun innerTubeBrowse(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null
    ) = httpClient.post("https://music.youtube.com/youtubei/v1/browse") {
        ytClientHeaders(client, setLogin = true)
        setBody(
            com.liquidmusicglass.api.youtube.models.body.YtBrowseBody(
                context = client.toContext(locale, visitorData),
                browseId = browseId,
                params = params,
                continuation = continuation
            )
        )
        continuation?.let {
            parameter("ctoken", it)
            parameter("continuation", it)
            parameter("type", "next")
        }
        parameter("prettyPrint", "false")
    }

    // Строка 373
private fun io.ktor.client.request.HttpRequestBuilder.ytClientHeaders(
    client: YouTubeClient,
    setLogin: Boolean = false
) {
    contentType(ContentType.Application.Json)
    headers {
        append("X-Goog-Api-Format-Version", "1")
        
        // Мапим строковое имя клиента в числовой ID для InnerTube (Строка 380)
        val clientId = when (client.clientName) { 
            "WEB_REMIX" -> "67"
            "ANDROID_MUSIC" -> "16"
            "IOS" -> "26"
            "TVHTML5" -> "7"
            else -> "67"
        }
        append("X-YouTube-Client-Name", clientId)
        
        append("X-YouTube-Client-Version", client.clientVersion)
        append("Origin", "https://music.youtube.com") // Исправлено x-origin -> Origin (Строка 382)
        if (client.referer != null) {
            append("Referer", client.referer)
        }
        append("User-Agent", client.userAgent)
    }
    parameter("key", client.apiKey)
} // Строка 389

    // ─── Parsing ───

    private fun parseSearchResults(response: YtSearchResponse): List<YtTrack> {
        val tracks = mutableListOf<YtTrack>()

        // 1. Parse from main contents
        response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents?.forEach { section ->

                // From musicShelfRenderer
                section.musicShelfRenderer?.contents?.forEach { item ->
                    item.musicResponsiveListItemRenderer?.let { renderer ->
                        parseMusicResponsiveListItem(renderer)?.let { tracks.add(it) }
                    }
                }

                // From itemSectionRenderer
                section.itemSectionRenderer?.contents?.forEach { item ->
                    item.musicResponsiveListItemRenderer?.let { renderer ->
                        parseMusicResponsiveListItem(renderer)?.let { tracks.add(it) }
                    }
                }

                // From musicCardShelfRenderer
                section.musicCardShelfRenderer?.contents?.forEach { item ->
                    item.musicResponsiveListItemRenderer?.let { renderer ->
                        parseMusicResponsiveListItem(renderer)?.let { tracks.add(it) }
                    }
                }
            }

        // 2. Parse from continuation contents
        response.continuationContents?.musicShelfContinuation?.contents?.forEach { item ->
            item.musicResponsiveListItemRenderer?.let { renderer ->
                parseMusicResponsiveListItem(renderer)?.let { tracks.add(it) }
            }
        }

        return tracks
    }

    private fun parseSearchResultsFull(response: YtSearchResponse): YtSearchResult {
        val items = mutableListOf<YtSearchItem>()
        val sectionTitle = mutableSetOf<String>()

        // Parse from main contents
        response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents?.forEach { section ->

                val title = section.musicShelfRenderer?.title?.text
                if (title != null) sectionTitle.add(title)

                section.musicShelfRenderer?.contents?.forEach { item ->
                    item.musicResponsiveListItemRenderer?.let { renderer ->
                        parseSearchItem(renderer)?.let { items.add(it) }
                    }
                }

                section.itemSectionRenderer?.contents?.forEach { item ->
                    item.musicResponsiveListItemRenderer?.let { renderer ->
                        parseSearchItem(renderer)?.let { items.add(it) }
                    }
                }

                section.musicCardShelfRenderer?.contents?.forEach { item ->
                    item.musicResponsiveListItemRenderer?.let { renderer ->
                        parseSearchItem(renderer)?.let { items.add(it) }
                    }
                }

                // Carousel items (two-row)
                section.musicCarouselShelfRenderer?.contents?.forEach { item ->
                    item.musicTwoRowItemRenderer?.let { renderer ->
                        parseTwoRowSearchItem(renderer)?.let { items.add(it) }
                    }
                }
            }

        // Continuation
        response.continuationContents?.musicShelfContinuation?.contents?.forEach { item ->
            item.musicResponsiveListItemRenderer?.let { renderer ->
                parseSearchItem(renderer)?.let { items.add(it) }
            }
        }

        return YtSearchResult(items = items)
    }

    private fun parseSearchItem(renderer: com.liquidmusicglass.api.youtube.models.response.YtMusicResponsiveListItemRenderer): YtSearchItem? {
        return when {
            renderer.isSong -> parseMusicResponsiveListItem(renderer)?.let { YtSearchItem.TrackItem(it) }
            renderer.isAlbum -> parseSearchAlbumItem(renderer)
            renderer.isArtist -> parseSearchArtistItem(renderer)
            renderer.isPlaylist -> parseSearchPlaylistItem(renderer)
            else -> null
        }
    }

    private fun parseSearchAlbumItem(renderer: com.liquidmusicglass.api.youtube.models.response.YtMusicResponsiveListItemRenderer): YtSearchItem? {
        val browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null
        val title = renderer.flexColumns?.getOrNull(0)?.musicResponsiveListItemFlexColumnRenderer?.text?.text ?: return null
        val subtitle = renderer.flexColumns?.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.splitBySeparator()
        val artist = subtitle?.getOrNull(0)?.oddElements()?.joinToString(", ") { it.text ?: "" }
        val year = subtitle?.getOrNull(1)?.firstOrNull()?.text?.toIntOrNull()
        val playlistId = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content
            ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchPlaylistEndpoint?.playlistId ?: ""
        val thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url ?: ""
        val isExplicit = renderer.badges?.any { it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE" } ?: false

        return YtSearchItem.AlbumItem(YtAlbumItem(
            browseId = browseId,
            playlistId = playlistId,
            title = title,
            artist = artist,
            year = year,
            thumbnail = thumbnail
        ))
    }

    private fun parseSearchArtistItem(renderer: com.liquidmusicglass.api.youtube.models.response.YtMusicResponsiveListItemRenderer): YtSearchItem? {
        val browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null
        val title = renderer.flexColumns?.getOrNull(0)?.musicResponsiveListItemFlexColumnRenderer?.text?.text ?: return null
        val thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url ?: ""

        return YtSearchItem.ArtistItem(YtArtistItem(
            browseId = browseId,
            title = title,
            thumbnail = thumbnail
        ))
    }

    private fun parseSearchPlaylistItem(renderer: com.liquidmusicglass.api.youtube.models.response.YtMusicResponsiveListItemRenderer): YtSearchItem? {
        val rawId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null
        val id = rawId.removePrefix("VL")
        val title = renderer.flexColumns?.getOrNull(0)?.musicResponsiveListItemFlexColumnRenderer?.text?.text ?: return null
        val subtitle = renderer.flexColumns?.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.splitBySeparator()
        val author = subtitle?.getOrNull(0)?.oddElements()?.joinToString(", ") { it.text ?: "" }
        val songCount = subtitle?.lastOrNull()?.firstOrNull()?.text
        val thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url ?: ""

        return YtSearchItem.PlaylistItem(YtPlaylistItem(
            id = id,
            title = title,
            author = author,
            songCountText = songCount,
            thumbnail = thumbnail
        ))
    }

    private fun parseTwoRowSearchItem(renderer: com.liquidmusicglass.api.youtube.models.response.YtMusicTwoRowItemRenderer): YtSearchItem? {
        val thumbnail = renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url ?: ""

        return when {
            renderer.isSong -> {
                val videoId = renderer.navigationEndpoint?.watchEndpoint?.videoId ?: return null
                val title = renderer.title?.text ?: return null
                val subtitleRuns = renderer.subtitle?.runs?.oddElements()
                val artist = subtitleRuns?.firstOrNull()?.text ?: "Unknown Artist"
                val isExplicit = renderer.subtitleBadges?.any {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } ?: false
                YtSearchItem.TrackItem(YtTrack(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    album = null,
                    durationSeconds = null,
                    thumbnail = thumbnail,
                    isExplicit = isExplicit
                ))
            }
            renderer.isAlbum || renderer.isPlaylist -> {
                parseTwoRowItemToAlbum(renderer)?.let { YtSearchItem.AlbumItem(it) }
            }
            renderer.isArtist -> {
                val browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null
                val title = renderer.title?.runs?.lastOrNull()?.text ?: return null
                YtSearchItem.ArtistItem(YtArtistItem(browseId = browseId, title = title, thumbnail = thumbnail))
            }
            else -> null
        }
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
    YtSearchFilter.SONGS -> "EgWKAQIIAWoKEAMQBBAJEAoQBQ=="
    YtSearchFilter.VIDEOS -> "EgWKAQIQAWoKEAMQBBAJEAoQBQ=="
    YtSearchFilter.ALBUMS -> "EgWKAQIYAWoKEAMQBBAJEAoQBQ=="
    YtSearchFilter.ARTISTS -> "EgWKAQIgAWoKEAMQBBAJEAoQBQ=="
    YtSearchFilter.PLAYLISTS -> "EgWKAQIoAWoKEAMQBBAJEAoQBQ=="
    }

    // ─── Browse Parsing ───

    private fun parseExplorePage(response: com.liquidmusicglass.api.youtube.models.response.YtBrowseResponse): YtExplorePage {
        val sections = response.contents?.singleColumnBrowseResultsRenderer?.tabs
            ?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents
            ?: emptyList()

        val newReleases = mutableListOf<YtAlbumItem>()
        val moods = mutableListOf<YtMoodItem>()

        for (section in sections) {
            section.musicCarouselShelfRenderer?.let { carousel ->
                val moreBrowseId = carousel.header?.musicCarouselShelfBasicHeaderRenderer
                    ?.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint?.browseId

                when (moreBrowseId) {
                    "FEmusic_new_releases_albums" -> {
                        carousel.contents.forEach { item ->
                            item.musicTwoRowItemRenderer?.let { renderer ->
                                parseTwoRowItemToAlbum(renderer)?.let { newReleases.add(it) }
                            }
                        }
                    }
                    "FEmusic_moods_and_genres" -> {
                        carousel.contents.forEach { item ->
                            item.musicTwoRowItemRenderer?.let { renderer ->
                                // Mood/genre items are rendered as two-row items with a browseEndpoint
                                val title = renderer.title?.text ?: return@let
                                val browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return@let
                                val thumbnail = renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url ?: ""
                                val stripeColor = renderer.subtitleBadges?.firstOrNull()
                                    ?.musicInlineBadgeRenderer?.icon?.iconType?.hashCode()?.toLong() ?: 0L
                                moods.add(YtMoodItem(
                                    title = title,
                                    stripeColor = stripeColor,
                                    browseId = browseId,
                                    params = renderer.navigationEndpoint?.browseEndpoint?.params
                                ))
                            }
                        }
                    }
                }
            }

            // Also check grid renderers (alternative layout)
            section.gridRenderer?.let { grid ->
                grid.items?.forEach { item ->
                    item.musicNavigationButtonRenderer?.let { renderer ->
                        parseMoodItem(renderer)?.let { moods.add(it) }
                    }
                }
            }
        }

        // Fallback: if moods not found in explore, try moodAndGenres endpoint separately
        Log.d(TAG, "Explore parsed: ${newReleases.size} albums, ${moods.size} moods")
        return YtExplorePage(newReleaseAlbums = newReleases, moodAndGenres = moods)
    }

    private fun parseMoodAndGenres(response: com.liquidmusicglass.api.youtube.models.response.YtBrowseResponse): List<YtMoodItem> {
        val moods = mutableListOf<YtMoodItem>()
        val sections = response.contents?.singleColumnBrowseResultsRenderer?.tabs
            ?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents
            ?: emptyList()

        for (section in sections) {
            section.gridRenderer?.items?.forEach { item ->
                item.musicNavigationButtonRenderer?.let { renderer ->
                    parseMoodItem(renderer)?.let { moods.add(it) }
                }
            }
        }

        return moods
    }

    private fun parseBrowseResult(response: com.liquidmusicglass.api.youtube.models.response.YtBrowseResponse): YtBrowseResult {
        val sections = mutableListOf<YtBrowseSection>()
        val browseSections = response.contents?.singleColumnBrowseResultsRenderer?.tabs
            ?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents
            ?: response.contents?.tabbedBrowseResultsRenderer?.tabs
                ?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents
            ?: emptyList()

        for (section in browseSections) {
            val title = section.musicCarouselShelfRenderer?.header?.musicCarouselShelfBasicHeaderRenderer?.title?.text
                ?: section.musicShelfRenderer?.title?.text
                ?: section.gridRenderer?.header?.gridHeaderRenderer?.title?.text

            val items = mutableListOf<YtBrowseItem>()

            section.musicCarouselShelfRenderer?.contents?.forEach { item ->
                item.musicTwoRowItemRenderer?.let { renderer ->
                    parseTwoRowItem(renderer)?.let { items.add(it) }
                }
            }

            section.musicShelfRenderer?.contents?.forEach { item ->
                item.musicResponsiveListItemRenderer?.let { renderer ->
                    parseMusicResponsiveListItem(renderer)?.let { track ->
                        items.add(YtBrowseItem.TrackItem(track))
                    }
                }
            }

            section.gridRenderer?.items?.forEach { item ->
                item.musicTwoRowItemRenderer?.let { renderer ->
                    parseTwoRowItem(renderer)?.let { items.add(it) }
                }
                item.musicNavigationButtonRenderer?.let { renderer ->
                    parseMoodItem(renderer)?.let { mood ->
                        items.add(YtBrowseItem.MoodItem(mood))
                    }
                }
            }

            if (items.isNotEmpty()) {
                sections.add(YtBrowseSection(title = title, items = items))
            }
        }

        return YtBrowseResult(sections = sections)
    }

    private fun parseAlbumPage(response: com.liquidmusicglass.api.youtube.models.response.YtBrowseResponse): YtAlbumPage {
        val sections = response.contents?.singleColumnBrowseResultsRenderer?.tabs
            ?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents
            ?: emptyList()

        var albumItem: YtAlbumItem? = null
        val songs = mutableListOf<YtTrack>()

        for (section in sections) {
            // Album/playlist header from MusicCardShelfRenderer or first carousel
            section.musicCardShelfRenderer?.let { card ->
                val title = card.header?.musicCardShelfHeaderBasicRenderer?.title?.text ?: return@let
                albumItem = YtAlbumItem(
                    browseId = "",
                    playlistId = "",
                    title = title,
                    artist = null,
                    year = null,
                    thumbnail = ""
                )
            }

            // Song list from MusicShelfRenderer
            section.musicShelfRenderer?.contents?.forEach { item ->
                item.musicResponsiveListItemRenderer?.let { renderer ->
                    parseMusicResponsiveListItem(renderer)?.let { songs.add(it) }
                }
            }
        }

        return YtAlbumPage(
            album = albumItem ?: YtAlbumItem(
                browseId = "",
                playlistId = "",
                title = "Unknown",
                artist = null,
                year = null,
                thumbnail = ""
            ),
            songs = songs
        )
    }

    private fun parseArtistPage(response: com.liquidmusicglass.api.youtube.models.response.YtBrowseResponse): YtArtistPage {
        val sections = response.contents?.singleColumnBrowseResultsRenderer?.tabs
            ?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents
            ?: emptyList()

        var artistItem: YtArtistItem? = null
        val artistSections = mutableListOf<YtArtistSection>()
        var description: String? = null

        for (section in sections) {
            // Artist header from MusicCardShelfRenderer
            section.musicCardShelfRenderer?.let { card ->
                val title = card.header?.musicCardShelfHeaderBasicRenderer?.title?.text ?: return@let
                val thumb = card.contents?.firstOrNull()?.musicResponsiveListItemRenderer
                    ?.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url
                artistItem = YtArtistItem(
                    browseId = "",
                    title = title,
                    thumbnail = thumb ?: ""
                )
            }

            // Description
            section.musicDescriptionShelfRenderer?.let { desc ->
                description = desc.description?.text
            }

            // Sections with songs/albums
            val sectionTitle = section.musicCarouselShelfRenderer?.header?.musicCarouselShelfBasicHeaderRenderer?.title?.text
                ?: section.musicShelfRenderer?.title?.text

            val sectionItems = mutableListOf<YtBrowseItem>()

            section.musicCarouselShelfRenderer?.contents?.forEach { item ->
                item.musicTwoRowItemRenderer?.let { renderer ->
                    parseTwoRowItem(renderer)?.let { sectionItems.add(it) }
                }
            }

            section.musicShelfRenderer?.contents?.forEach { item ->
                item.musicResponsiveListItemRenderer?.let { renderer ->
                    parseMusicResponsiveListItem(renderer)?.let { track ->
                        sectionItems.add(YtBrowseItem.TrackItem(track))
                    }
                }
            }

            if (sectionItems.isNotEmpty() && sectionTitle != null) {
                artistSections.add(YtArtistSection(title = sectionTitle, items = sectionItems))
            }
        }

        return YtArtistPage(
            artist = artistItem ?: YtArtistItem(browseId = "", title = "Unknown", thumbnail = ""),
            sections = artistSections,
            description = description
        )
    }

    private fun parseMoodItem(renderer: com.liquidmusicglass.api.youtube.models.response.YtMusicNavigationButtonRenderer): YtMoodItem? {
        val title = renderer.buttonText?.text ?: return null
        val stripeColor = renderer.solid?.leftStripeColor ?: 0L
        val browseId = renderer.clickCommand?.browseEndpoint?.browseId ?: return null
        val params = renderer.clickCommand?.browseEndpoint?.params

        return YtMoodItem(
            title = title,
            stripeColor = stripeColor,
            browseId = browseId,
            params = params
        )
    }

    private fun parseTwoRowItemToAlbum(renderer: com.liquidmusicglass.api.youtube.models.response.YtMusicTwoRowItemRenderer): YtAlbumItem? {
        if (!renderer.isAlbum && !renderer.isPlaylist) return null
        val title = renderer.title?.text ?: return null
        val browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null
        val playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer
            ?.content?.musicPlayButtonRenderer?.playNavigationEndpoint
            ?.watchPlaylistEndpoint?.playlistId ?: ""
        val subtitleRuns = renderer.subtitle?.runs?.oddElements()
        val artist = subtitleRuns?.firstOrNull()?.text
        val year = subtitleRuns?.lastOrNull()?.text?.toIntOrNull()
        val thumbnail = renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url ?: ""

        return YtAlbumItem(
            browseId = browseId,
            playlistId = playlistId,
            title = title,
            artist = artist,
            year = year,
            thumbnail = thumbnail
        )
    }

    private fun parseTwoRowItem(renderer: com.liquidmusicglass.api.youtube.models.response.YtMusicTwoRowItemRenderer): YtBrowseItem? {
        val thumbnail = renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url ?: ""

        return when {
            renderer.isSong -> {
                val videoId = renderer.navigationEndpoint?.watchEndpoint?.videoId ?: return null
                val title = renderer.title?.text ?: return null
                val subtitleRuns = renderer.subtitle?.runs?.oddElements()
                val artist = subtitleRuns?.firstOrNull()?.text ?: "Unknown Artist"
                val isExplicit = renderer.subtitleBadges?.any {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } ?: false

                YtBrowseItem.TrackItem(YtTrack(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    album = null,
                    durationSeconds = null,
                    thumbnail = thumbnail,
                    isExplicit = isExplicit
                ))
            }
            renderer.isAlbum || renderer.isPlaylist -> {
                parseTwoRowItemToAlbum(renderer)?.let { YtBrowseItem.AlbumItem(it) }
            }
            renderer.isArtist -> {
                val browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null
                val title = renderer.title?.runs?.lastOrNull()?.text ?: return null
                YtBrowseItem.ArtistItem(YtArtistItem(
                    browseId = browseId,
                    title = title,
                    thumbnail = thumbnail
                ))
            }
            else -> null
        }
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
     * URI = videoId — ResolvingDataSource in AudioService resolves it to
     * the real audio stream URL on demand (same as InnerTune).
     */
    fun toEngineTrack(): Track = Track(
        id = videoId,
        title = title,
        artist = artist,
        albumName = album ?: "Single",
        uri = android.net.Uri.parse(videoId),
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

// ─── Browse data classes ───

data class YtExplorePage(
    val newReleaseAlbums: List<YtAlbumItem>,
    val moodAndGenres: List<YtMoodItem>
)

data class YtAlbumItem(
    val browseId: String,
    val playlistId: String,
    val title: String,
    val artist: String?,
    val year: Int?,
    val thumbnail: String
)

data class YtArtistItem(
    val browseId: String,
    val title: String,
    val thumbnail: String
)

data class YtMoodItem(
    val title: String,
    val stripeColor: Long,
    val browseId: String,
    val params: String? = null
)

data class YtAlbumPage(
    val album: YtAlbumItem,
    val songs: List<YtTrack>
)

data class YtArtistPage(
    val artist: YtArtistItem,
    val sections: List<YtArtistSection>,
    val description: String? = null
)

data class YtArtistSection(
    val title: String,
    val items: List<YtBrowseItem>
)

data class YtBrowseResult(
    val sections: List<YtBrowseSection>
)

data class YtBrowseSection(
    val title: String?,
    val items: List<YtBrowseItem>
)

sealed class YtBrowseItem {
    data class TrackItem(val track: YtTrack) : YtBrowseItem()
    data class AlbumItem(val album: YtAlbumItem) : YtBrowseItem()
    data class ArtistItem(val artist: YtArtistItem) : YtBrowseItem()
    data class MoodItem(val mood: YtMoodItem) : YtBrowseItem()
}

// ─── Search result types ───

data class YtSearchResult(
    val items: List<YtSearchItem>
) {
    val tracks: List<YtTrack> get() = items.mapNotNull { (it as? YtSearchItem.TrackItem)?.track }
    val albums: List<YtAlbumItem> get() = items.mapNotNull { (it as? YtSearchItem.AlbumItem)?.album }
    val artists: List<YtArtistItem> get() = items.mapNotNull { (it as? YtSearchItem.ArtistItem)?.artist }
    val playlists: List<YtPlaylistItem> get() = items.mapNotNull { (it as? YtSearchItem.PlaylistItem)?.playlist }
}

sealed class YtSearchItem {
    data class TrackItem(val track: YtTrack) : YtSearchItem()
    data class AlbumItem(val album: YtAlbumItem) : YtSearchItem()
    data class ArtistItem(val artist: YtArtistItem) : YtSearchItem()
    data class PlaylistItem(val playlist: YtPlaylistItem) : YtSearchItem()
}

data class YtPlaylistItem(
    val id: String,
    val title: String,
    val author: String?,
    val songCountText: String?,
    val thumbnail: String
)
