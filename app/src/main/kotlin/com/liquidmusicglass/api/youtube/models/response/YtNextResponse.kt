package com.liquidmusicglass.api.youtube.models.response

import kotlinx.serialization.Serializable

/**
 * Response from /next endpoint — contains radio queue.
 */
@Serializable
data class YtNextResponse(
    val contents: YtNextContents? = null,
    val continuationContents: YtContinuationContents? = null,
    val currentVideoEndpoint: YtNavigationEndpoint? = null
) {
    /**
     * Extract playlist panel (radio queue) from response.
     */
    val playlistPanel: YtPlaylistPanelRenderer?
        get() = continuationContents?.playlistPanelContinuation
            ?: contents?.singleColumnMusicWatchNextResultsRenderer
                ?.tabbedRenderer?.watchNextTabbedResultsRenderer
                ?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.musicQueueRenderer
                ?.content?.playlistPanelRenderer
}

@Serializable
data class YtNextContents(
    val singleColumnMusicWatchNextResultsRenderer: YtSingleColumnMusicWatchNextResultsRenderer? = null
)

@Serializable
data class YtSingleColumnMusicWatchNextResultsRenderer(
    val tabbedRenderer: YtTabbedRenderer? = null
)

@Serializable
data class YtTabbedRenderer(
    val watchNextTabbedResultsRenderer: YtWatchNextTabbedResultsRenderer? = null
)

@Serializable
data class YtWatchNextTabbedResultsRenderer(
    val tabs: List<YtTab>? = null
)

@Serializable
data class YtTab(
    val tabRenderer: YtTabRenderer? = null
)

@Serializable
data class YtTabRenderer(
    val content: YtTabContent? = null,
    val endpoint: YtNavigationEndpoint? = null
)

@Serializable
data class YtTabContent(
    val musicQueueRenderer: YtMusicQueueRenderer? = null
)

@Serializable
data class YtMusicQueueRenderer(
    val header: YtMusicQueueHeader? = null,
    val content: YtMusicQueueContent? = null
)

@Serializable
data class YtMusicQueueHeader(
    val musicQueueHeaderRenderer: YtMusicQueueHeaderRenderer? = null
)

@Serializable
data class YtMusicQueueHeaderRenderer(
    val title: YtRuns? = null,
    val subtitle: YtRuns? = null
)

@Serializable
data class YtMusicQueueContent(
    val playlistPanelRenderer: YtPlaylistPanelRenderer? = null
)

@Serializable
data class YtContinuationContents(
    val playlistPanelContinuation: YtPlaylistPanelRenderer? = null
)

@Serializable
data class YtPlaylistPanelRenderer(
    val title: String? = null,
    val titleText: YtRuns? = null,
    val contents: List<YtPlaylistPanelContent>? = null,
    val isInfinite: Boolean = false,
    val numItemsToShow: Int? = null,
    val playlistId: String? = null,
    val continuations: List<YtContinuation>? = null
)

@Serializable
data class YtPlaylistPanelContent(
    val playlistPanelVideoRenderer: YtPlaylistPanelVideoRenderer? = null,
    val automixPreviewVideoRenderer: YtAutomixPreviewVideoRenderer? = null
)

@Serializable
data class YtPlaylistPanelVideoRenderer(
    val title: YtRuns? = null,
    val lengthText: YtRuns? = null,
    val longBylineText: YtRuns? = null,
    val shortBylineText: YtRuns? = null,
    val badges: List<YtBadge>? = null,
    val videoId: String? = null,
    val playlistSetVideoId: String? = null,
    val selected: Boolean = false,
    val thumbnail: YtThumbnails? = null,
    val unplayableText: YtRuns? = null,
    val menu: YtMenu? = null,
    val navigationEndpoint: YtNavigationEndpoint? = null
)

@Serializable
data class YtAutomixPreviewVideoRenderer(
    val content: YtAutomixContent? = null
)

@Serializable
data class YtAutomixContent(
    val automixPlaylistVideoRenderer: YtAutomixPlaylistVideoRenderer? = null
)

@Serializable
data class YtAutomixPlaylistVideoRenderer(
    val navigationEndpoint: YtNavigationEndpoint? = null
)
