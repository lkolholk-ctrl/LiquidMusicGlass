package com.liquidmusicglass.api.youtube.models.body

import com.liquidmusicglass.api.youtube.models.YtContext
import kotlinx.serialization.Serializable

/**
 * POST body for /player endpoint.
 */
@Serializable
data class YtPlayerBody(
    val context: YtContext,
    val videoId: String,
    val playlistId: String? = null,
    val contentCheckOk: Boolean = true,
    val racyCheckOk: Boolean = true
)
