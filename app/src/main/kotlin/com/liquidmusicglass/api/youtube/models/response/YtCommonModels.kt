package com.liquidmusicglass.api.youtube.models.response

import kotlinx.serialization.Serializable

// ─── Shared / Common models used across multiple responses ───

@Serializable
data class YtResponseContext(
    val visitorData: String? = null,
    val serviceTrackingParams: List<YtServiceTrackingParam>? = null
)

@Serializable
data class YtServiceTrackingParam(
    val service: String? = null,
    val params: List<YtParam>? = null
)

@Serializable
data class YtParam(
    val key: String? = null,
    val value: String? = null
)

@Serializable
data class YtThumbnails(
    val thumbnails: List<YtThumbnail>? = null
)

@Serializable
data class YtThumbnail(
    val url: String,
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class YtRuns(
    val runs: List<YtRun>? = null
) {
    val text: String?
        get() = runs?.joinToString("") { it.text ?: "" }
}

@Serializable
data class YtRun(
    val text: String? = null,
    val navigationEndpoint: YtNavigationEndpoint? = null
)

@Serializable
data class YtNavigationEndpoint(
    val watchEndpoint: YtWatchEndpoint? = null,
    val watchPlaylistEndpoint: YtWatchPlaylistEndpoint? = null,
    val browseEndpoint: YtBrowseEndpoint? = null,
    val searchEndpoint: YtSearchEndpoint? = null
) {
    val videoId: String?
        get() = watchEndpoint?.videoId ?: watchPlaylistEndpoint?.videoId

    val playlistId: String?
        get() = watchPlaylistEndpoint?.playlistId ?: watchEndpoint?.playlistId
}

@Serializable
data class YtWatchEndpoint(
    val videoId: String? = null,
    val playlistId: String? = null,
    val playlistSetVideoId: String? = null,
    val params: String? = null,
    val index: Int? = null
)

@Serializable
data class YtWatchPlaylistEndpoint(
    val playlistId: String? = null,
    val videoId: String? = null,
    val params: String? = null
)

@Serializable
data class YtBrowseEndpoint(
    val browseId: String? = null,
    val params: String? = null
)

@Serializable
data class YtSearchEndpoint(
    val query: String? = null,
    val params: String? = null
)

@Serializable
data class YtContinuation(
    val nextContinuationData: YtNextContinuationData? = null,
    val reloadContinuationData: YtReloadContinuationData? = null
)

@Serializable
data class YtNextContinuationData(
    val continuation: String? = null
)

@Serializable
data class YtReloadContinuationData(
    val continuation: String? = null
)

@Serializable
data class YtBadge(
    val musicInlineBadgeRenderer: YtMusicInlineBadgeRenderer? = null
)

@Serializable
data class YtMusicInlineBadgeRenderer(
    val icon: YtIcon? = null,
    val accessibilityData: YtAccessibilityData? = null
)

@Serializable
data class YtIcon(
    val iconType: String? = null
)

@Serializable
data class YtMenu(
    val menuRenderer: YtMenuRenderer? = null
)

@Serializable
data class YtMenuRenderer(
    val items: List<YtMenuItem>? = null
)

@Serializable
data class YtMenuItem(
    val menuNavigationItemRenderer: YtMenuNavigationItemRenderer? = null
)

@Serializable
data class YtMenuNavigationItemRenderer(
    val text: YtRuns? = null,
    val icon: YtIcon? = null,
    val navigationEndpoint: YtNavigationEndpoint? = null
)

// ─── Extension helpers ───

fun List<YtRun>?.splitBySeparator(): List<List<YtRun>> {
    if (this == null) return emptyList()
    val result = mutableListOf<List<YtRun>>()
    var temp = mutableListOf<YtRun>()
    forEach { run ->
        if (run.text == " • ") {
            result.add(temp)
            temp = mutableListOf()
        } else {
            temp.add(run)
        }
    }
    result.add(temp)
    return result
}

fun List<YtRun>?.oddElements(): List<YtRun> {
    if (this == null) return emptyList()
    return filterIndexed { index, _ -> index % 2 == 0 }
}

fun String.parseDurationSeconds(): Int? {
    val parts = split(":").mapNotNull { it.toIntOrNull() }
    return when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> null
    }
}
