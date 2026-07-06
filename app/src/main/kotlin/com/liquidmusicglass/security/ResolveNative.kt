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

    // ── Яндекс Музыка (вторая половина #82) ──
    external fun yandexUa(): String
    external fun yandexAccept(): String
    external fun yandexAcceptLang(): String
    external fun yandexReferer(): String
    external fun yandexApiAccept(): String

    /** Из HTML страницы плейлиста достать uid/kind → официальный api-URL; "" если нет. */
    external fun yandexApiUrl(htmlUtf8: ByteArray): String

    /** Тело официального API (UTF-8) → записи title/artist (\x1E между, \x1F внутри). */
    external fun yandexApiParse(bodyUtf8: ByteArray): ByteArray

    /** HTML старой ссылки (UTF-8) → те же записи из __STATE_SNAPSHOT__. */
    external fun yandexSnapshotParse(htmlUtf8: ByteArray): ByteArray
}
