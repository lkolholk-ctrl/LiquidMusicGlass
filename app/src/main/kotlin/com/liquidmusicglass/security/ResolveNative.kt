package com.liquidmusicglass.security

/**
 * JNI-мост к нативным резолверам плейлистов (батч 16, защита #82).
 *
 * Логика скрейпа (эндпоинты, User-Agent, маркеры, парсинг) живёт в
 * liblmg_resolve.so и не видна в декомпиле Kotlin. Здесь — только тонкие
 * external-объявления. HTTP делает Kotlin (см. SpotifyPlaylistFetcher):
 * натив строит URL/заголовки и парсит тело, сокет — на стороне Kotlin.
 */
object ResolveNative {
    init { System.loadLibrary("lmg_resolve") }

    /** Построить embed-URL по вставленной ссылке; "" если это не плейлист Spotify. */
    external fun spotifyEmbedUrl(pastedUrl: String): String

    external fun spotifyUa(): String
    external fun spotifyAccept(): String
    external fun spotifyAcceptLang(): String

    /**
     * Разобрать HTML embed-страницы (UTF-8 байты) → UTF-8 байты:
     * запись = title  artist, записи разделены . Пусто = не распарсили.
     */
    external fun spotifyParse(htmlUtf8: ByteArray): ByteArray
}
