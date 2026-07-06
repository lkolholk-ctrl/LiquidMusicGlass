package com.liquidmusicglass.data.playlistimport

/**
 * Domain model for a track imported from an external platform.
 * Clean, platform-agnostic representation.
 */
data class ImportedTrack(
    val title: String,
    val artist: String,
    val query: String = "${artist} ${title}".trim()
)

/**
 * Result of matching an imported track against ICM catalog.
 */
sealed class TrackMatchResult {
    /**
     * Successfully matched — contains playable ICM track ID.
     */
    data class Matched(
        val importedTrack: ImportedTrack,
        val icmTrackId: String,
        val icmTitle: String,
        val icmArtist: String,
        val icmAlbum: String?,
        val icmDurationMs: Int?,
        val icmCover: String?
    ) : TrackMatchResult()

    /**
     * No match found in ICM catalog.
     */
    data class NotFound(
        val importedTrack: ImportedTrack,
        val searchQuery: String
    ) : TrackMatchResult()

    /**
     * Network or API error during search.
     */
    data class Error(
        val importedTrack: ImportedTrack,
        val exception: Throwable
    ) : TrackMatchResult()
}

/**
 * Final result of playlist import operation.
 */
data class PlaylistImportResult(
    val sourceUrl: String,
    val sourceType: PlaylistSourceType,
    val totalTracks: Int,
    val matchedTracks: List<TrackMatchResult.Matched>,
    val failedTracks: List<TrackMatchResult.NotFound>,
    val errorTracks: List<TrackMatchResult.Error>,
    val icmPlaylistId: String? = null  // null for local-only playlists
)

enum class PlaylistSourceType {
    YANDEX,
    APPLE,
    SPOTIFY,
    UNKNOWN
}

// ── Background Import State ──

/**
 * Sealed interface representing the background import lifecycle.
 * Used by ViewModel StateFlow to communicate status to UI.
 */
sealed interface ImportState {
    data object Idle : ImportState
    data class Loading(
        val processedTracks: Int,
        val totalTracks: Int,
        val phase: LoadingPhase
    ) : ImportState
    data class Success(
        val insertedCount: Int,
        val playlistId: String,
        val playlistName: String
    ) : ImportState
    data class Error(val message: String) : ImportState
}

enum class LoadingPhase {
    RESOLVING,      // Fetching from Yandex/Apple
    MATCHING,       // Matching against ICM catalog
    SAVING          // Writing to local database
}
