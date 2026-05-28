package com.liquidmusicglass.api.youtube.models.body

import com.liquidmusicglass.api.youtube.models.YtContext
import kotlinx.serialization.Serializable

/**
 * POST body for /search endpoint.
 */
@Serializable
data class YtSearchBody(
    val context: YtContext,
    val query: String? = null,
    val params: String? = null
)
