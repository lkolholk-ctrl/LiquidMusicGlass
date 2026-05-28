package com.liquidmusicglass.api.youtube.models.body

import com.liquidmusicglass.api.youtube.models.YtContext
import kotlinx.serialization.Serializable

/**
 * POST body for /next endpoint (radio / queue).
 */
@Serializable
data class YtNextBody(
    val context: YtContext,
    val videoId: String? = null,
    val playlistId: String? = null,
    val playlistSetVideoId: String? = null,
    val index: Int? = null,
    val params: String? = null,
    val continuation: String? = null
)
