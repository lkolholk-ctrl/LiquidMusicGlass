package com.liquidmusicglass.data.playlistimport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * ICM catalog search client with dual-mode parsing:
 *   1. JSON mode: sends "Accept: application/json", parses via kotlinx.serialization
 *   2. HTML fallback: parses HTML response with Jsoup
 *
 * The ICM /search endpoint returns HTML by default (Content-Type: text/html).
 * We first try to request JSON, and fall back to HTML parsing if needed.
 */
class IcmSearchApi(
    private val baseUrl: String = "https://byicloud.online/api/partner",
    private val authProvider: () -> Pair<String?, String?>  // (partnerKey, sessionToken)
) {

    /** Дока ICM: source ∈ apple/vk/all. Старые алиасы primary/secondary мапим. */
    private fun normalizeSource(src: String): String = when (src.lowercase()) {
        "primary" -> "apple"
        "secondary" -> "vk"
        "apple", "vk", "all" -> src.lowercase()
        else -> "all"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Search ICM catalog for a track.
     *
     * Strategy:
     *   1. Try with "Accept: application/json" header
     *   2. If response is JSON → parse as IcmSearchResponseDto
     *   3. If response is HTML → parse with Jsoup
     *
     * @param q Search query (artist + title)
     * @param region us/ru/nz
     * @param source apple/vk/all (по доке ICM)
     * @param limit Max results
     * @param logger Optional file logger for debugging
     */
    suspend fun search(
        q: String,
        region: String = "us",
        source: String = "all",
        limit: Int = 5,
        logger: ImportFileLogger? = null
    ): IcmSearchResponseDto {
        val encodedQuery = java.net.URLEncoder.encode(q, "UTF-8")
        val url = "$baseUrl/search?q=$encodedQuery&region=$region&source=${normalizeSource(source)}&limit=$limit"

        logger?.log("D", "IcmSearch", "Searching: $q")

        // Build request with auth headers
        val (partnerKey, sessionToken) = authProvider()
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")

        partnerKey?.let { requestBuilder.header("X-Partner-Key", it) }
        sessionToken?.let { requestBuilder.header("Authorization", "Bearer $it") }

        val request = requestBuilder.build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val contentType = response.header("Content-Type", "") ?: ""

                logger?.log("D", "IcmSearch", "Response code: ${response.code}, content-type: $contentType, body-length: ${body.length}")

                when {
                    // Success with JSON
                    response.isSuccessful && (contentType.contains("json") || body.trimStart().startsWith("{")) -> {
                        logger?.log("D", "IcmSearch", "Parsing as JSON")
                        parseJsonResponse(body, logger)
                    }

                    // HTML response — use Jsoup fallback
                    response.isSuccessful && (contentType.contains("html") || body.contains("<a class=\"row\"")) -> {
                        logger?.log("D", "IcmSearch", "Parsing as HTML with Jsoup")
                        parseHtmlResponse(body, logger)
                    }

                    // Error response
                    else -> {
                        logger?.log("E", "IcmSearch", "HTTP ${response.code}: $body")
                        throw Exception("HTTP ${response.code}: $body")
                    }
                }
            }
        } catch (e: Exception) {
            logger?.log("E", "IcmSearch", "Search failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /**
     * Parse JSON response from ICM search.
     */
    private fun parseJsonResponse(body: String, logger: ImportFileLogger?): IcmSearchResponseDto {
        return try {
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            }
            json.decodeFromString(IcmSearchResponseDto.serializer(), body)
        } catch (e: Exception) {
            logger?.log("E", "IcmSearch", "JSON parse failed: ${e.message}")
            throw Exception("Failed to parse JSON response: ${e.message}")
        }
    }

    /**
     * Parse HTML response from ICM search using Jsoup.
     *
     * Expected HTML structure:
     *   <li><a class="row" href="/track/828259377">
     *     <div class="ttl">Midnight City</div>
     *     <div class="sub">M83</div>
     *   </a></li>
     */
    private fun parseHtmlResponse(body: String, logger: ImportFileLogger?): IcmSearchResponseDto {
        return try {
            val doc = Jsoup.parse(body)
            val rows = doc.select("a.row")

            logger?.log("D", "IcmSearch", "Jsoup found ${rows.size} rows")

            val items = rows.mapNotNull { row ->
                val href = row.attr("href") ?: return@mapNotNull null
                val trackId = extractTrackId(href) ?: return@mapNotNull null
                val title = row.selectFirst(".ttl")?.text()?.trim() ?: return@mapNotNull null
                val artist = row.selectFirst(".sub")?.text()?.trim() ?: "Unknown Artist"
                // Try multiple selectors for cover image
                val coverUrl = row.selectFirst("img")?.attr("src")
                    ?: row.selectFirst(".cov img")?.attr("src")
                    ?: row.selectFirst("[class*=cover] img")?.attr("src")
                    ?: row.selectFirst("[class*=art] img")?.attr("src")

                IcmSearchItemDto(
                    id = trackId,
                    title = title,
                    artist = artist,
                    cover = coverUrl
                )
            }

            logger?.log("D", "IcmSearch", "Jsoup parsed ${items.size} tracks")

            IcmSearchResponseDto(items = items)
        } catch (e: Exception) {
            logger?.log("E", "IcmSearch", "HTML parse failed: ${e.message}")
            throw Exception("Failed to parse HTML response: ${e.message}")
        }
    }

    /**
     * Extract track ID from href like "/track/828259377".
     */
    private fun extractTrackId(href: String): String? {
        return when {
            href.startsWith("/track/") -> href.removePrefix("/track/").trim()
            href.startsWith("/api/partner/track/") -> href.removePrefix("/api/partner/track/").trim()
            else -> null
        }
    }
}

/**
 * ICM search response wrapper.
 */
@Serializable
data class IcmSearchResponseDto(
    @SerialName("items")
    val items: List<IcmSearchItemDto> = emptyList()
)

/**
 * Single item from ICM search results.
 */
@Serializable
data class IcmSearchItemDto(
    @SerialName("id")
    val id: String,

    @SerialName("title")
    val title: String,

    @SerialName("artist")
    val artist: String? = null,

    @SerialName("artistId")
    val artistId: String? = null,

    @SerialName("cover")
    val cover: String? = null,

    @SerialName("collectionId")
    val collectionId: String? = null,

    @SerialName("album")
    val album: String? = null,

    @SerialName("duration")
    val duration: Int? = null,

    @SerialName("is_explicit")
    val isExplicit: Boolean = false,

    @SerialName("isArtist")
    val isArtist: Boolean = false,

    @SerialName("isAlbum")
    val isAlbum: Boolean = false,

    @SerialName("region")
    val region: String? = null
)
