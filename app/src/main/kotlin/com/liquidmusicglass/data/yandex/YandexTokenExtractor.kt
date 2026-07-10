package com.liquidmusicglass.data.yandex

/**
 * Разбор redirect-URL встроенного входа Яндекса.
 *
 * OAuth-флоу `response_type=token` возвращает токен в URL-фрагменте:
 * `https://music.yandex.ru/#access_token=y0_…&token_type=bearer&expires_in=…`
 * Фрагмент не уходит на сервер — токен виден только клиенту.
 */
object YandexTokenExtractor {

    /** access_token из фрагмента URL, или null если его там нет. */
    fun accessTokenFrom(url: String?): String? {
        if (url == null) return null
        val fragment = url.substringAfter('#', "")
        if (fragment.isBlank()) return null
        return fragment.split('&')
            .firstOrNull { it.startsWith("access_token=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            }
    }

    /** true, если Яндекс вернул ошибку авторизации (`#error=access_denied&…`). */
    fun isAuthError(url: String?): Boolean {
        val fragment = url?.substringAfter('#', "") ?: return false
        if (fragment.isBlank()) return false
        return fragment.split('&').any { it.startsWith("error=") }
    }
}
