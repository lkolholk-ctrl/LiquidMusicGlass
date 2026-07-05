package com.liquidmusicglass.data.playlistimport

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import java.util.concurrent.TimeUnit

/** Трек из публичного плейлиста Spotify (title + artist). */
data class SpotifyRawTrack(val title: String, val artist: String)

/**
 * On-device резолвер публичных плейлистов Spotify — БЕЗ токена и секрета.
 *
 * Embed-страница open.spotify.com/embed/playlist/<id> отдаёт данные плейлиста
 * прямо в HTML: в теге <script id="__NEXT_DATA__"> лежит JSON, внутри —
 * "trackList":[{title, subtitle, ...}]. title = название трека, subtitle =
 * артист (подтверждено полем). Анонимно, один запрос — весь список.
 *
 * Это проще Яндекса (там два шага: HTML → uid/kind → API). Client Credentials
 * (client_id+secret) НЕ нужен — а значит не нужен и прокти для секрета.
 *
 * Оговорка: для ОЧЕНЬ больших плейлистов embed может отдать не все треки
 * (Spotify лимитирует). Логируем фактическое число — если режет, добьём через
 * официальную пагинацию отдельным пунктом.
 *
 * Защита от «подавился HTML/JSON» — как в YandexPlaylistFetcher: всё на IO,
 * потолок страницы, линейный indexOf + скобочный скан (не регэксп-бомба),
 * потоковый JsonReader, кап треков.
 */
object SpotifyPlaylistFetcher {

    private const val MAX_HTML_BYTES = 8L * 1024 * 1024
    private const val MAX_TRACKS = 5000
    private const val MIN_HTML_BYTES = 500

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    // Достаём id плейлиста из любой формы ссылки:
    //   open.spotify.com/playlist/<id>?si=...  |  /embed/playlist/<id>
    //   spotify:playlist:<id>
    private val PLAYLIST_ID_RE = Regex("playlist[:/]([A-Za-z0-9]{16,})")

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
        val id = PLAYLIST_ID_RE.find(url)?.groupValues?.getOrNull(1)
            ?: throw YandexResolveException("Not a Spotify playlist link.")
        val html = fetchEmbed(id)
        return parseTrackList(html)
    }

    private fun fetchEmbed(id: String): String {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/playlist/$id")
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
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
     * Ищем `"trackList":[ ... ]` линейным indexOf + скобочным сканом (не
     * регэксп с .*? — бэктрекинг-бомба), парсим массив потоковым JsonReader,
     * берём title (трек) + subtitle (артист).
     */
    private fun parseTrackList(html: String): List<SpotifyRawTrack> {
        val marker = html.indexOf("\"trackList\":")
        if (marker < 0)
            throw YandexResolveException("Couldn't read the Spotify playlist (format changed?).")
        val arrStart = html.indexOf('[', marker)
        if (arrStart < 0)
            throw YandexResolveException("Couldn't read the Spotify playlist (format changed?).")
        val arrEnd = matchBracket(html, arrStart)
        if (arrEnd <= arrStart)
            throw YandexResolveException("Couldn't read the Spotify playlist (format changed?).")

        val arr = html.substring(arrStart, arrEnd + 1)
        val out = ArrayList<SpotifyRawTrack>()
        JsonReader(StringReader(arr)).use { r ->
            r.isLenient = true
            r.beginArray()
            while (r.hasNext()) {
                if (out.size >= MAX_TRACKS) { r.skipValue(); continue }
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                var title: String? = null
                var subtitle: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "title" -> title = readStringOrNull(r)
                        "subtitle" -> subtitle = readStringOrNull(r)
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                val t = title?.takeIf { it.isNotBlank() } ?: continue
                out.add(SpotifyRawTrack(t, subtitle?.takeIf { it.isNotBlank() } ?: "Unknown Artist"))
            }
            r.endArray()
        }
        if (out.isEmpty())
            throw YandexResolveException("Playlist found but contains no tracks.")
        return out
    }

    /** Скобочный скан [ ] с учётом строк/экранирования — линейное время. */
    private fun matchBracket(s: String, start: Int): Int {
        var depth = 0
        var i = start
        var inString = false
        val cap = minOf(s.length, start + 8 * 1024 * 1024)
        while (i < cap) {
            val c = s[i]
            if (inString) {
                when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) return i }
                }
            }
            i++
        }
        return -1
    }

    private fun readStringOrNull(reader: JsonReader): String? =
        if (reader.peek() == JsonToken.STRING) reader.nextString()
        else { reader.skipValue(); null }
}
