package com.liquidmusicglass.api.youtube.models

import kotlinx.serialization.Serializable

/**
 * InnerTube request context — sent in every POST body.
 */
@Serializable
data class YtContext(
    val client: Client,
    val thirdParty: ThirdParty? = null,
    val user: User? = null
) {
    @Serializable
    data class Client(
        val clientName: String,
        val clientVersion: String,
        val osVersion: String? = null,
        val gl: String,
        val hl: String,
        val visitorData: String? = null
    )

    @Serializable
    data class ThirdParty(
        val embedUrl: String
    )

    @Serializable
    data class User(
        val lockedSafetyMode: Boolean = false
    )
}
