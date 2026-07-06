package com.liquidmusicglass.data.playlistimport

import com.liquidmusicglass.security.ResolveNative
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Трек из публичного плейлиста Spotify (title + artist). */
data class SpotifyRawTrack(val title: String, val artist: String)

/**
 * On-device резолвер публичных плейлистов Spotify — БЕЗ токена и секрета.
 *
 * Батч 16 (#82): вся логика скрейпа (id из ссылки, embed-URL, заголовки,
 * поиск "trackList" и парсинг title/subtitle) уехала в натив
 * [ResolveNative] / liblmg_resolve.so, чтобы не светиться в декомпиле Kotlin.
 * Здесь остался только HTTP (OkHttp): натив строит URL/заголовки и парсит
 * тело, сокет — тут.
 *
 * Оговорка: для ОЧЕНЬ больших плейлистов embed может отдать не все треки
 * (лимит Spotify). Число реально распарсенного логируем выше по стеку.
 */
object SpotifyPlaylistFetcher {

    private const val MAX_HTML_BYTES = 8L * 1024 * 1024
    private const val MIN_HTML_BYTES = 500

    // Разделители из нативного парсера: запись (RS, 0x1E) и поле (US, 0x1F).
    private const val REC_SEP = '\u001E'
    private const val FIELD_SEP = '\u001F'

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Блокирующий — звать с Dispatchers.IO. Бросает [YandexResolveException]. */
    fun resolve(url: String): List<SpotifyRawTrack> {
        val embedUrl = ResolveNative.spotifyEmbedUrl(url)
        if (embedUrl.isBlank())
            throw YandexResolveException("Not a Spotify playlist link.")
        val html = fetchEmbed(embedUrl)
        return parseNative(html)
    }

    private fun fetchEmbed(embedUrl: String): String {
        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", ResolveNative.spotifyUa())
            .header("Accept", ResolveNative.spotifyAccept())
            .header("Accept-Language", ResolveNative.spotifyAcceptLang())
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw YandexResolveException("Cannot reach Spotify. Check your connection.", e)
        }
        response.use { resp ->
            when (resp.code) {
                200 -> {}
                404 -> throw YandexResolveException("Playlist not found on Spotify.")
                else -> throw YandexResolveException("Spotify returned HTTP ${resp.code}.")
            }
            val source = resp.body?.source()
                ?: throw YandexResolveException("Spotify returned an empty page.")
            val sb = StringBuilder(256 * 1024)
            val chunk = okio.Buffer()
            while (sb.length < MAX_HTML_BYTES) {
                val read = try {
                    source.read(chunk, 64 * 1024L)
                } catch (e: Exception) {
                    if (sb.length >= MIN_HTML_BYTES) break
                    throw YandexResolveException("Connection to Spotify dropped mid-page.", e)
                }
                if (read == -1L) break
                sb.append(chunk.readUtf8())
            }
            if (sb.length < MIN_HTML_BYTES)
                throw YandexResolveException("Spotify returned an empty or truncated page.")
            return sb.toString()
        }
    }

    /**
     * Парсинг — целиком в нативе. HTML отдаём как UTF-8, получаем UTF-8:
     * записи разделены RS(0x1E), поля title/artist — US(0x1F).
     */
    private fun parseNative(html: String): List<SpotifyRawTrack> {
        val bytes = ResolveNative.spotifyParse(html.toByteArray(Charsets.UTF_8))
        if (bytes.isEmpty())
            throw YandexResolveException("Couldn't read the Spotify playlist (format changed?).")
        val out = String(bytes, Charsets.UTF_8)
            .split(REC_SEP)
            .mapNotNull { rec ->
                val parts = rec.split(FIELD_SEP)
                val title = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artist = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                SpotifyRawTrack(title, artist)
            }
        if (out.isEmpty())
            throw YandexResolveException("Playlist found but contains no tracks.")
        return out
    }
}
