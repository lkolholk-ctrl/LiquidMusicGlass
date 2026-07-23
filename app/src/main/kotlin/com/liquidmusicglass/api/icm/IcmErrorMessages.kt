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
                "Connection timed out. Please try again."
            // Реально нет сети/маршрута или DNS не резолвится.
            "unable to resolve host" in m || "unknownhost" in m
                || "network is unreachable" in m || "no address associated" in m ->
                "No internet connection."
            // Сеть есть, но не достучались до сервера (мёртвый маршрут/протухший коннект/
            // сервер недоступен) — НЕ называем это «нет интернета».
            "failed to connect" in m || "connection reset" in m || "unexpected end of stream" in m
                || "connection closed" in m || "broken pipe" in m ->
                "Can't reach the server. Please try again."
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
        // Это код ОТ СЕРВЕРА (его upstream-проблема), а не отсутствие сети у юзера.
        "network_error" -> return "Server is having trouble. Please try again."
        "not_linked", "no_partner_user", "unauthorized" -> return "Sign in with Telegram to continue."
        // ── Email-вход (OTP) и пароль ICM ──
        "invalid_email" -> return "That email doesn't look right."
        "invalid_otp" -> return "Wrong code. Check the email and try again."
        "invalid_nonce" -> return "Code expired. Request a new one."
        "nonce_locked" -> return "Too many attempts. Request a new code."
        "nonce_belongs_to_another_partner" -> return "Code expired. Request a new one."
        "invalid_current_password" -> return "Current password is incorrect."
        "new_password_must_differ" -> return "New password must be different."
        "email_account_missing" -> return "No email is linked to this account."
        "user_not_linked" -> return "Sign in first to manage your password."
        // ── Видеоклипы ──
        "clip_preparing" -> return "Clip is being prepared — try again in a moment."
        "clip_failed", "clip_download_failed" -> return "Couldn't load this clip. Try another."
    }
    return when (httpCode) {
        429 -> "Too many requests. Please wait a moment."
        401, 403 -> "Access denied. Try signing in again."
        404 -> "Nothing found."
        451 -> "Not available in your region."
        in 500..599 -> "Server is unavailable. Please try again later."
        // code 0 = пустой/нечитаемый ответ (IcmApiException(0, …)), НЕ отсутствие сети.
        0 -> "Can't reach the server. Please try again."
        else -> "Something went wrong. Please try again."
    }
}
