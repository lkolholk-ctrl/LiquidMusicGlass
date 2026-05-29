package com.liquidmusicglass.api.youtube.models.body

import com.liquidmusicglass.api.youtube.models.YtContext
import kotlinx.serialization.Serializable

/**
 * POST body for /browse endpoint (explore, album, artist, playlist details).
 */
@Serializable
data class YtBrowseBody(
    val context: YtContext,
    val browseId: String? = null,
    val params: String? = null,
    val continuation: String? = null
)
