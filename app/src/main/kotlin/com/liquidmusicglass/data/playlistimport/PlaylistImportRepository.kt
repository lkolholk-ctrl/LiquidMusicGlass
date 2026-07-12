package com.liquidmusicglass.data.playlistimport

import com.liquidmusicglass.api.icm.IcmRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Repository for importing playlists from external platforms (Yandex, Apple)
 * into the local player via ICM catalog matching.
 *
 * Architecture:
 *   - Yandex URLs: разбираются НА УСТРОЙСТВЕ ([YandexPlaylistFetcher] —
 *     запрос к Яндексу идёт с жилого IP юзера; ICM Яндексом заблокирован,
 *     личный сервер-резолвер умер вместе с хостингом), потом матчинг
 *     по каталогу ICM трек-за-треком.
 *   - Apple URLs: Delegated to native ICM import API (server-side matching).
 *
 * All operations run on Dispatchers.IO. No UI blocking.
 */
class PlaylistImportRepository(
    private val icmSearch: IcmSearchApi
) {

    companion object {
        /** Max concurrent ICM search requests. Поднято 3→6: матчинг 50 треков
         *  шёл ~15с (полевой фидбек). 6 параллельных поисков — всё ещё
         *  безопасно для search-эндпоинта, но вдвое быстрее. */
        private const val DEFAULT_CONCURRENCY = 6

        /** Batch size for progress reporting. */
        private const val BATCH_SIZE = 25

        /** Delay between individual requests in ms. Снижено 300→150: семафор уже
         *  ограничивает число одновременных запросов, лишняя задержка только
         *  копила латентность. */
        private const val REQUEST_DELAY_MS = 150L

        /** ICM search region for track matching. */
        private const val SEARCH_REGION = "us"

        /** ICM search source — primary + secondary catalogs. */
        private const val SEARCH_SOURCE = "all"
    }

    /**
     * Main entry point: resolve and import a playlist from a URL.
     *
     * @param url Playlist URL (Yandex or Apple)
     * @param onState Optional callback for state updates (Loading/Success/Error)
     * @param logger Optional file logger for debugging
     * @return Import result with matched/failed/error tracks
     */
    suspend fun importPlaylist(
        url: String,
        onState: ((ImportState) -> Unit)? = null,
        logger: ImportFileLogger? = null
    ): PlaylistImportResult = withContext(Dispatchers.IO) {

        val sourceType = detectSourceType(url)

        when (sourceType) {
            PlaylistSourceType.YANDEX -> importFromYandex(url, onState, logger)
            PlaylistSourceType.SPOTIFY -> importFromSpotify(url, onState, logger)
            PlaylistSourceType.APPLE -> importFromApple(url, onState, logger)
            PlaylistSourceType.UNKNOWN -> throw IllegalArgumentException(
                "Unsupported playlist URL: $url"
            )
        }
    }

    /**
     * Yandex import flow:
     *   1. Resolve URL on-device ([YandexPlaylistFetcher]) → [{title, artist}]
     *   2. Match each track against ICM catalog (concurrent, limited)
     *   3. Collect results and save to local playlist
     */
    private suspend fun importFromYandex(
        url: String,
        onState: ((ImportState) -> Unit)?,
        logger: ImportFileLogger?
    ): PlaylistImportResult = coroutineScope {

        logger?.log("I", "ImportRepo", "=== Starting Yandex import ===")
        logger?.log("I", "ImportRepo", "URL: $url")

        onState?.invoke(ImportState.Loading(0, 0, LoadingPhase.RESOLVING))

        // Step 1: Resolve Yandex URL to raw tracks — прямо с устройства.
        // withTimeout — страховка поверх OkHttp callTimeout (45с): фаза резолва
        // НИКОГДА не висит бесконечно (полевой баг: импорт «висел 2 часа» —
        // старая сборка стучалась на умерший сервер и не отваливалась).
        val rawTracks = try {
            logger?.log("I", "ImportRepo", "Fetching playlist from Yandex (on-device)...")
            withTimeout(60_000L) { YandexPlaylistFetcher.resolve(url) }
        } catch (e: Exception) {
            logger?.log("E", "ImportRepo", "Resolver failed: ${e.message}")
            val errorMsg = when (e) {
                is TimeoutCancellationException ->
                    "Yandex didn't respond in time. If you're on VPN, try disabling it and retry."
                is YandexResolveException -> e.message ?: "Failed to resolve Yandex playlist."
                is SocketTimeoutException -> "Network timeout while fetching playlist. Please check your connection."
                is UnknownHostException -> "Cannot reach Yandex Music. Check your connection."
                else -> "Failed to resolve Yandex playlist: ${e.message}"
            }
            onState?.invoke(ImportState.Error(errorMsg))
            throw PlaylistImportException(errorMsg, e)
        }

        logger?.log("I", "ImportRepo", "Resolved ${rawTracks.size} tracks from Yandex")

        if (rawTracks.isEmpty()) {
            logger?.log("W", "ImportRepo", "No tracks found in playlist")
            onState?.invoke(ImportState.Success(0, "", ""))
            return@coroutineScope PlaylistImportResult(
                sourceUrl = url,
                sourceType = PlaylistSourceType.YANDEX,
                totalTracks = 0,
                matchedTracks = emptyList(),
                failedTracks = emptyList(),
                errorTracks = emptyList()
            )
        }

        val importedTracks = rawTracks.map { dto ->
            ImportedTrack(title = dto.title, artist = dto.artist)
        }

        logger?.log("I", "ImportRepo", "Starting ICM matching for ${importedTracks.size} tracks")

        // Step 2: Match tracks against ICM catalog with limited concurrency
        onState?.invoke(ImportState.Loading(0, importedTracks.size, LoadingPhase.MATCHING))

        val semaphore = Semaphore(DEFAULT_CONCURRENCY)
        val results = mutableListOf<TrackMatchResult>()
        var matchedCount = 0
        var failedCount = 0

        // Process in batches for progress reporting
        importedTracks.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            logger?.log("I", "ImportRepo", "Batch ${batchIndex + 1}: ${batch.size} tracks")

            val batchResults = batch.map { track ->
                async {
                    semaphore.withPermit {
                        searchIcmForTrack(track, logger)
                    }
                }
            }.awaitAll()

            results.addAll(batchResults)

            // Update counters and report progress
            batchResults.forEach { result ->
                when (result) {
                    is TrackMatchResult.Matched -> matchedCount++
                    is TrackMatchResult.NotFound -> failedCount++
                    is TrackMatchResult.Error -> failedCount++
                }
            }

            val processed = matchedCount + failedCount
            logger?.log("I", "ImportRepo", "Progress: $matchedCount matched, $failedCount failed / ${importedTracks.size} total")
            onState?.invoke(ImportState.Loading(processed, importedTracks.size, LoadingPhase.MATCHING))
        }

        logger?.log("I", "ImportRepo", "=== Import complete: $matchedCount matched, $failedCount failed, ${importedTracks.size} total ===")

        PlaylistImportResult(
            sourceUrl = url,
            sourceType = PlaylistSourceType.YANDEX,
            totalTracks = importedTracks.size,
            matchedTracks = results.filterIsInstance<TrackMatchResult.Matched>(),
            failedTracks = results.filterIsInstance<TrackMatchResult.NotFound>(),
            errorTracks = results.filterIsInstance<TrackMatchResult.Error>()
        )
    }

    /**
     * Spotify import flow (batch 13): scrape embed-страницы БЕЗ токена.
     * open.spotify.com/embed/playlist/<id> → __NEXT_DATA__ → trackList →
     * матчинг по каталогу ICM (тот же конвейер, что у Яндекса).
     */
    private suspend fun importFromSpotify(
        url: String,
        onState: ((ImportState) -> Unit)?,
        logger: ImportFileLogger?
    ): PlaylistImportResult = coroutineScope {
        onState?.invoke(ImportState.Loading(0, 0, LoadingPhase.RESOLVING))
        val rawTracks = try {
            logger?.log("I", "ImportRepo", "Fetching playlist from Spotify (on-device)...")
            withTimeout(60_000L) { SpotifyPlaylistFetcher.resolve(url) }
        } catch (e: Exception) {
            val errorMsg = when (e) {
                is TimeoutCancellationException -> "Spotify didn't respond in time. Check your connection."
                is YandexResolveException -> e.message ?: "Failed to load Spotify playlist."
                else -> "Failed to load Spotify playlist: ${e.message}"
            }
            onState?.invoke(ImportState.Error(errorMsg))
            throw PlaylistImportException(errorMsg, e)
        }
        logger?.log("I", "ImportRepo", "Resolved ${rawTracks.size} tracks from Spotify")

        if (rawTracks.isEmpty()) {
            onState?.invoke(ImportState.Success(0, "", ""))
            return@coroutineScope PlaylistImportResult(
                sourceUrl = url, sourceType = PlaylistSourceType.SPOTIFY,
                totalTracks = 0, matchedTracks = emptyList(),
                failedTracks = emptyList(), errorTracks = emptyList()
            )
        }

        val importedTracks = rawTracks.map { ImportedTrack(title = it.title, artist = it.artist) }
        onState?.invoke(ImportState.Loading(0, importedTracks.size, LoadingPhase.MATCHING))

        val semaphore = Semaphore(DEFAULT_CONCURRENCY)
        val results = mutableListOf<TrackMatchResult>()
        var matchedCount = 0
        var failedCount = 0
        importedTracks.chunked(BATCH_SIZE).forEach { batch ->
            val batchResults = batch.map { track ->
                async { semaphore.withPermit { searchIcmForTrack(track, logger) } }
            }.awaitAll()
            results.addAll(batchResults)
            batchResults.forEach { r -> if (r is TrackMatchResult.Matched) matchedCount++ else failedCount++ }
            onState?.invoke(ImportState.Loading(matchedCount + failedCount, importedTracks.size, LoadingPhase.MATCHING))
        }
        logger?.log("I", "ImportRepo", "=== Spotify import: $matchedCount matched, $failedCount failed / ${importedTracks.size} ===")

        PlaylistImportResult(
            sourceUrl = url, sourceType = PlaylistSourceType.SPOTIFY,
            totalTracks = importedTracks.size,
            matchedTracks = results.filterIsInstance<TrackMatchResult.Matched>(),
            failedTracks = results.filterIsInstance<TrackMatchResult.NotFound>(),
            errorTracks = results.filterIsInstance<TrackMatchResult.Error>()
        )
    }

    /**
     * Apple import flow: delegate to native ICM import API.
     */
    private suspend fun importFromApple(
        url: String,
        onState: ((ImportState) -> Unit)?,
        logger: ImportFileLogger?
    ): PlaylistImportResult {
        onState?.invoke(ImportState.Loading(0, 0, LoadingPhase.RESOLVING))

        val response = try {
            IcmRepository.importPlaylist(
                source = "apple",
                url = url,
                name = null
            )
        } catch (e: Exception) {
            val errorMsg = when (e) {
                is SocketTimeoutException -> "Network timeout while importing from Apple Music."
                is UnknownHostException -> "Cannot reach ICM server. Check your connection."
                else -> "Apple import failed: ${e.message}"
            }
            onState?.invoke(ImportState.Error(errorMsg))
            throw PlaylistImportException(errorMsg, e)
        }

        if (response == null) {
            val errorCode = IcmRepository.getLastErrorCode()
            val errorMsg = IcmRepository.lastError.value
                ?: "Apple import failed${errorCode?.let { " (code: $it)" } ?: ""}: Unknown error"
            onState?.invoke(ImportState.Error(errorMsg))
            throw PlaylistImportException(errorMsg)
        }

        val playlistId = response.playlistId
            ?: throw PlaylistImportException("Apple import succeeded but no playlist_id returned")

        val matchedTracks = response.tracks?.mapNotNull { track ->
            val tid = track.trackId ?: return@mapNotNull null
            TrackMatchResult.Matched(
                importedTrack = ImportedTrack(
                    title = track.title ?: "",
                    artist = track.artist ?: ""
                ),
                icmTrackId = tid,
                icmTitle = track.title ?: "",
                icmArtist = track.artist ?: "",
                icmAlbum = null,
                icmDurationMs = track.duration?.toInt(),
                icmCover = track.cover
            )
        } ?: emptyList()

        val failedTracks = response.failedTracks?.map { failed ->
            TrackMatchResult.NotFound(
                importedTrack = ImportedTrack(
                    title = failed.yandexTitle ?: "",
                    artist = failed.yandexArtists.firstOrNull() ?: ""
                ),
                searchQuery = failed.reason ?: ""
            )
        } ?: emptyList()

        return PlaylistImportResult(
            sourceUrl = url,
            sourceType = PlaylistSourceType.APPLE,
            totalTracks = response.total ?: matchedTracks.size,
            matchedTracks = matchedTracks,
            failedTracks = failedTracks,
            errorTracks = emptyList(),
            icmPlaylistId = playlistId
        )
    }

    /**
     * Search ICM catalog for a single imported track.
     */
    private suspend fun searchIcmForTrack(track: ImportedTrack, logger: ImportFileLogger?): TrackMatchResult {
        return try {
            delay(REQUEST_DELAY_MS)

            val response = icmSearch.search(
                q = track.query,
                region = SEARCH_REGION,
                source = SEARCH_SOURCE,
                limit = 5,
                logger = logger
            )

            val firstTrack = response.items.firstOrNull { item ->
                !item.isArtist && !item.isAlbum
            }

            if (firstTrack != null) {
                TrackMatchResult.Matched(
                    importedTrack = track,
                    icmTrackId = firstTrack.id,
                    icmTitle = firstTrack.title,
                    icmArtist = firstTrack.artist ?: track.artist,
                    icmAlbum = firstTrack.album,
                    icmDurationMs = normalizeDuration(firstTrack.duration),
                    icmCover = firstTrack.cover
                )
            } else {
                TrackMatchResult.NotFound(
                    importedTrack = track,
                    searchQuery = track.query
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Отмена импорта обязана останавливать матчинг сразу, а не
            // превращаться в TrackMatchResult.Error (P1, аудит).
            throw e
        } catch (e: Exception) {
            TrackMatchResult.Error(track, e)
        }
    }

    private fun normalizeDuration(duration: Int?): Int? {
        return duration?.let {
            if (it < 1000) it * 1000 else it
        }
    }

    fun matchedTracksToPlayableTracks(result: PlaylistImportResult): List<com.liquidmusicglass.engine.Track> {
        return result.matchedTracks.map { matched ->
            com.liquidmusicglass.engine.Track(
                id = matched.icmTrackId,
                title = matched.icmTitle,
                artist = matched.icmArtist,
                albumName = matched.icmAlbum ?: "",
                uri = android.net.Uri.parse("https://byicloud.online/track/${matched.icmTrackId}"),
                durationMs = matched.icmDurationMs?.toLong() ?: 0L,
                albumId = -1L,
                coverUrl = matched.icmCover
            )
        }
    }

    /**
     * Save import result to local PlaylistManager with full metadata.
     */
    fun saveToLocalPlaylist(
        result: PlaylistImportResult,
        originalName: String? = null
    ): String {
        val tracks = result.matchedTracks.map { matched ->
            com.liquidmusicglass.engine.PlaylistManager.PlaylistTrack(
                id = matched.icmTrackId,
                title = matched.icmTitle,
                artist = matched.icmArtist,
                coverUrl = matched.icmCover,
                durationMs = matched.icmDurationMs?.toLong() ?: 0L
            )
        }
        val sourceName = when (result.sourceType) {
            PlaylistSourceType.YANDEX -> "Yandex Music"
            PlaylistSourceType.APPLE -> "Apple Music"
            PlaylistSourceType.SPOTIFY -> "Spotify"
            else -> "Imported"
        }
        val playlistName = originalName ?: "$sourceName Playlist"

        val playlist = com.liquidmusicglass.engine.PlaylistManager.createFromImport(
            name = playlistName,
            tracks = tracks,
            sourceType = sourceName
        )

        return playlist.id
    }

    fun getTrackIds(result: PlaylistImportResult): List<String> {
        return result.matchedTracks.map { it.icmTrackId }
    }

    fun detectSourceType(url: String): PlaylistSourceType {
        val lower = url.lowercase()
        return when {
            lower.contains("music.yandex") -> PlaylistSourceType.YANDEX
            lower.contains("spotify.com") || lower.startsWith("spotify:") -> PlaylistSourceType.SPOTIFY
            lower.contains("apple.com") || lower.contains("music.apple") -> PlaylistSourceType.APPLE
            else -> PlaylistSourceType.UNKNOWN
        }
    }
}

class PlaylistImportException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
