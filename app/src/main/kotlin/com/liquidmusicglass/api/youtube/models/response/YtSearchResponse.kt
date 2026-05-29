package com.liquidmusicglass.api.youtube.models.response

import kotlinx.serialization.Serializable

/**
 * Response from /search endpoint.
 */
@Serializable
data class YtSearchResponse(
    val contents: YtSearchContents? = null,
    val continuationContents: YtSearchContinuationContents? = null
)

@Serializable
data class YtSearchContents(
    val tabbedSearchResultsRenderer: YtTabbedSearchResults? = null
)

@Serializable
data class YtTabbedSearchResults(
    val tabs: List<YtSearchTab>? = null
)

@Serializable
data class YtSearchTab(
    val tabRenderer: YtSearchTabRenderer? = null
)

@Serializable
data class YtSearchTabRenderer(
    val content: YtSearchTabContent? = null
)

@Serializable
data class YtSearchTabContent(
    val sectionListRenderer: YtSectionListRenderer? = null
)

@Serializable
data class YtSectionListRenderer(
    val contents: List<YtSectionContent>? = null,
    val continuations: List<YtContinuation>? = null
)

@Serializable
data class YtSectionContent(
    val musicShelfRenderer: YtMusicShelfRenderer? = null,
    val musicCardShelfRenderer: YtMusicCardShelfRenderer? = null,
    val itemSectionRenderer: YtItemSectionRenderer? = null,
    val gridRenderer: YtGridRenderer? = null,
    val musicCarouselShelfRenderer: YtMusicCarouselShelfRenderer? = null,
    val musicDescriptionShelfRenderer: YtMusicDescriptionShelfRenderer? = null
)

@Serializable
data class YtItemSectionRenderer(
    val contents: List<YtMusicShelfItem>? = null
)

@Serializable
data class YtMusicShelfRenderer(
    val title: YtRuns? = null,
    val contents: List<YtMusicShelfItem>? = null,
    val continuations: List<YtContinuation>? = null
)

@Serializable
data class YtMusicShelfItem(
    val musicResponsiveListItemRenderer: YtMusicResponsiveListItemRenderer? = null
)

@Serializable
data class YtMusicCardShelfRenderer(
    val header: YtMusicCardShelfHeader? = null,
    val contents: List<YtMusicShelfItem>? = null
)

@Serializable
data class YtMusicCardShelfHeader(
    val musicCardShelfHeaderBasicRenderer: YtMusicCardShelfHeaderBasic? = null
)

@Serializable
data class YtMusicCardShelfHeaderBasic(
    val title: YtRuns? = null
)

@Serializable
data class YtSearchContinuationContents(
    val musicShelfContinuation: YtMusicShelfRenderer? = null
)

@Serializable
data class YtMusicResponsiveListItemRenderer(
    val trackingParams: String? = null,
    val thumbnail: YtMusicThumbnail? = null,
    val overlay: YtMusicOverlay? = null,
    val flexColumns: List<YtFlexColumn>? = null,
    val menu: YtMenu? = null,
    val playlistItemData: YtPlaylistItemData? = null,
    val navigationEndpoint: YtNavigationEndpoint? = null,
    val badges: List<YtBadge>? = null,
    val fixedColumns: List<YtFlexColumn>? = null
) {
    val isSong: Boolean
        get() = playlistItemData?.videoId != null
            || navigationEndpoint?.watchEndpoint != null

    val isAlbum: Boolean
        get() = navigationEndpoint?.browseEndpoint?.browseId?.startsWith("MPR") == true
            || navigationEndpoint?.browseEndpoint?.browseId?.startsWith("MPA") == true

    val isArtist: Boolean
        get() = navigationEndpoint?.browseEndpoint?.browseId?.startsWith("UC") == true

    val isPlaylist: Boolean
        get() = navigationEndpoint?.browseEndpoint?.browseId?.startsWith("VL") == true
            || navigationEndpoint?.browseEndpoint?.browseId?.startsWith("PL") == true
}

@Serializable
data class YtMusicThumbnail(
    val musicThumbnailRenderer: YtMusicThumbnailRenderer? = null
)

@Serializable
data class YtMusicThumbnailRenderer(
    val thumbnail: YtThumbnails? = null,
    val thumbnailCrop: String? = null,
    val thumbnailScale: String? = null
)

@Serializable
data class YtMusicOverlay(
    val musicItemThumbnailOverlayRenderer: YtMusicItemThumbnailOverlayRenderer? = null
)

@Serializable
data class YtMusicItemThumbnailOverlayRenderer(
    val content: YtMusicItemThumbnailOverlayContent? = null,
    val contentPosition: String? = null,
    val displayStyle: String? = null
)

@Serializable
data class YtMusicItemThumbnailOverlayContent(
    val musicPlayButtonRenderer: YtMusicPlayButtonRenderer? = null
)

@Serializable
data class YtMusicPlayButtonRenderer(
    val playNavigationEndpoint: YtNavigationEndpoint? = null,
    val trackingParams: String? = null,
    val playIcon: YtIcon? = null,
    val pauseIcon: YtIcon? = null,
    val iconColor: Long? = null,
    val backgroundColor: Long? = null,
    val activeBackgroundColor: Long? = null,
    val loadingIndicatorColor: Long? = null,
    val playingIcon: YtIcon? = null,
    val iconLoadingColor: Long? = null,
    val activeScaleFactor: Double? = null,
    val buttonSize: String? = null,
    val rippleTarget: String? = null,
    val accessibilityPlayData: YtAccessibilityData? = null,
    val accessibilityPauseData: YtAccessibilityData? = null
)

@Serializable
data class YtAccessibilityData(
    val accessibilityData: YtAccessibilityLabel? = null
)

@Serializable
data class YtAccessibilityLabel(
    val label: String? = null
)

@Serializable
data class YtFlexColumn(
    val musicResponsiveListItemFlexColumnRenderer: YtFlexColumnRenderer? = null
)

@Serializable
data class YtFlexColumnRenderer(
    val text: YtRuns? = null,
    val displayPriority: String? = null
)

@Serializable
data class YtPlaylistItemData(
    val videoId: String? = null,
    val playlistId: String? = null
)
