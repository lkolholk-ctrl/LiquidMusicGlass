package com.liquidmusicglass.api.icm

/**
 * Maps technical API failures (raw JSON bodies, HTTP codes, error codes) to short,
 * human-readable English messages for the UI.
 *
 * Before this, the raw response body (a big JSON blob) leaked straight into UI error
 * text — e.g. a failed search showed JSON to the user. Surfaces should call this
 * instead of using exception.message / lastError directly.
 */
fun icmUserMessage(throwable: Throwable?): String = when (throwable) {
    is IcmApiException -> icmUserMessage(throwable.code, throwable.errorCode)
    null -> "Something went wrong. Please try again."
    else -> {
        val m = throwable.message?.lowercase() ?: ""
        when {
            "timeout" in m || "timed out" in m ->
                "Connection timed out. Check your internet."
            "unable to resolve host" in m || "unknownhost" in m || "failed to connect" in m
                || "network is unreachable" in m || "no address associated" in m ->
                "No internet connection."
            else -> "Something went wrong. Please try again."
        }
    }
}

/** Overload by HTTP status + server error code (e.g. from IcmApiException / StreamResult). */
fun icmUserMessage(httpCode: Int, errorCode: String?): String {
    when (errorCode?.lowercase()) {
        "rate_limited", "ip_temporarily_blocked" -> return "Too many requests. Please wait a moment."
        "region_unavailable" -> return "Not available in your region."
        "source_not_allowed" -> return "This source isn't available right now."
        "track_not_found" -> return "Nothing found."
        "early_access" -> return "This track isn't available yet."
        "network_error" -> return "No internet connection."
        "not_linked", "no_partner_user", "unauthorized" -> return "Sign in with Telegram to continue."
    }
    return when (httpCode) {
        429 -> "Too many requests. Please wait a moment."
        401, 403 -> "Access denied. Try signing in again."
        404 -> "Nothing found."
        451 -> "Not available in your region."
        in 500..599 -> "Server is unavailable. Please try again later."
        0 -> "No internet connection."
        else -> "Something went wrong. Please try again."
    }
}
