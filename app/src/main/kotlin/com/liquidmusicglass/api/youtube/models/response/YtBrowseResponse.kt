package com.liquidmusicglass.api.youtube.models.response

import kotlinx.serialization.Serializable

/**
 * Response from /browse endpoint.
 * Shares SectionListRenderer with search responses — reuse existing models.
 */
@Serializable
data class YtBrowseResponse(
    val contents: YtBrowseContents? = null,
    val continuationContents: YtBrowseContinuationContents? = null
)

@Serializable
data class YtBrowseContents(
    val singleColumnBrowseResultsRenderer: YtSingleColumnBrowseResults? = null,
    val tabbedBrowseResultsRenderer: YtTabbedBrowseResults? = null
)

@Serializable
data class YtSingleColumnBrowseResults(
    val tabs: List<YtBrowseTab>? = null
)

@Serializable
data class YtTabbedBrowseResults(
    val tabs: List<YtBrowseTab>? = null
)

@Serializable
data class YtBrowseTab(
    val tabRenderer: YtBrowseTabRenderer? = null
)

@Serializable
data class YtBrowseTabRenderer(
    val content: YtBrowseTabContent? = null,
    val title: String? = null
)

@Serializable
data class YtBrowseTabContent(
    val sectionListRenderer: YtSectionListRenderer? = null
)

@Serializable
data class YtBrowseContinuationContents(
    val sectionListContinuation: YtSectionListRenderer? = null,
    val musicPlaylistShelfContinuation: YtMusicShelfRenderer? = null,
    val gridContinuation: YtGridRenderer? = null
)

// ─── Grid Renderer (used in explore, mood/genres) ───

@Serializable
data class YtGridRenderer(
    val items: List<YtGridItem>? = null,
    val header: YtGridHeader? = null,
    val continuations: List<YtContinuation>? = null
)

@Serializable
data class YtGridItem(
    val musicTwoRowItemRenderer: YtMusicTwoRowItemRenderer? = null,
    val musicNavigationButtonRenderer: YtMusicNavigationButtonRenderer? = null
)

@Serializable
data class YtGridHeader(
    val gridHeaderRenderer: YtGridHeaderRenderer? = null
)

@Serializable
data class YtGridHeaderRenderer(
    val title: YtRuns? = null
)

// ─── Music Two Row Item Renderer (albums, playlists, artists in browse) ───

@Serializable
data class YtMusicTwoRowItemRenderer(
    val title: YtRuns? = null,
    val subtitle: YtRuns? = null,
    val subtitleBadges: List<YtBadge>? = null,
    val navigationEndpoint: YtNavigationEndpoint? = null,
    val thumbnailRenderer: YtMusicThumbnail? = null,
    val thumbnailOverlay: YtMusicOverlay? = null,
    val menu: YtMenu? = null,
    val aspectRatio: String? = null
) {
    val isSong: Boolean
        get() = navigationEndpoint?.watchEndpoint != null
            && navigationEndpoint?.browseEndpoint == null

    val isAlbum: Boolean
        get() = navigationEndpoint?.browseEndpoint?.browseId?.startsWith("MPR") == true
            || navigationEndpoint?.browseEndpoint?.browseId?.startsWith("MPA") == true
            || subtitle?.text?.let {
                val lastPart = it.split(" • ").lastOrNull()?.trim()
                lastPart?.toIntOrNull() != null // year at the end = album
            } == true

    val isPlaylist: Boolean
        get() = navigationEndpoint?.browseEndpoint?.browseId?.startsWith("VL") == true
            || navigationEndpoint?.browseEndpoint?.browseId?.startsWith("PL") == true
            || navigationEndpoint?.browseEndpoint?.browseId?.startsWith("OLA") == true

    val isArtist: Boolean
        get() = navigationEndpoint?.browseEndpoint?.browseId?.startsWith("UC") == true
            || navigationEndpoint?.browseEndpoint?.browseId?.startsWith("MCP") == true
}

// ─── Music Navigation Button Renderer (mood/genre categories) ───

@Serializable
data class YtMusicNavigationButtonRenderer(
    val buttonText: YtRuns? = null,
    val solid: YtSolid? = null,
    val clickCommand: YtNavigationEndpoint? = null,
    val icon: YtIcon? = null
)

@Serializable
data class YtSolid(
    val leftStripeColor: Long? = null
)

// ─── Music Carousel Shelf Renderer (artist sections, home rows) ───

@Serializable
data class YtMusicCarouselShelfRenderer(
    val header: YtCarouselHeader? = null,
    val contents: List<YtCarouselItem> = emptyList()
)

@Serializable
data class YtCarouselHeader(
    val musicCarouselShelfBasicHeaderRenderer: YtCarouselHeaderRenderer? = null
)

@Serializable
data class YtCarouselHeaderRenderer(
    val title: YtRuns? = null,
    val moreContentButton: YtMoreContentButton? = null,
    val strapline: YtRuns? = null
)

@Serializable
data class YtMoreContentButton(
    val buttonRenderer: YtMoreButtonRenderer? = null
)

@Serializable
data class YtMoreButtonRenderer(
    val navigationEndpoint: YtNavigationEndpoint? = null
)

@Serializable
data class YtCarouselItem(
    val musicTwoRowItemRenderer: YtMusicTwoRowItemRenderer? = null,
    val musicResponsiveListItemRenderer: YtMusicResponsiveListItemRenderer? = null
)

// ─── Music Description Shelf (artist bio) ───

@Serializable
data class YtMusicDescriptionShelfRenderer(
    val description: YtRuns? = null,
    val subheader: YtRuns? = null,
    val footer: YtRuns? = null
)

// ─── SectionContent expansion for browse ───

// Extend existing YtSectionContent to include browse-specific renderers
// We'll handle this by adding nullable fields to the existing model

// ─── Browse-specific extensions to YtSectionContent ───
// These are added to the existing YtSectionContent data class via the edit below
