package com.liquidmusicglass.data.playlistimport

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import java.util.concurrent.TimeUnit

/** Трек, извлечённый из публичного плейлиста Яндекс Музыки. */
data class YandexRawTrack(val title: String, val artist: String)

/** Ошибка резолва с человекочитаемым сообщением для UI импорта. */
class YandexResolveException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * On-device резолвер публичных плейлистов Яндекс Музыки.
 *
 * Порт полевой схемы с сервера (FastAPI на умершем хостинге) внутрь
 * приложения: запросы идут С ТЕЛЕФОНА юзера — жилой IP, для Яндекса
 * неотличимо от браузера. Сервер/прокси не нужны (ICM Яндексом
 * заблокирован, их джоба импорта мертва — поэтому резолвим сами).
 *
 * Схема:
 *  1. Страница плейлиста тянется с заголовками живого Chrome
 *     (без Sec-Fetch-*/ru-локали Яндекс отдаёт 403/451).
 *  2. Новая ссылка (/playlists/lk.<uuid>): из HTML достаём "uid"/"kind"
 *     владельца → официальное api.music.yandex.net/users/{uid}/playlists/{kind}
 *     отвечает БЕЗ авторизации чистым JSON со всеми треками.
 *  3. Старая ссылка (/users/<login>/playlists/<kind>): парсим
 *     __STATE_SNAPSHOT__-блоки из HTML (playlist.items[].data).
 *
 * Защита от «подавился HTML/JSON» (телефон — не сервер):
 *  - читаем страницу кусками с потолком [MAX_HTML_BYTES], для uuid-ссылок
 *    останавливаемся, как только найдены uid и kind (ранний выход);
 *  - НИКАКИХ регэкспов по всей странице с .*? (бэктрекинг-бомба) —
 *    снапшот ищется линейным indexOf + подсчётом скобок;
 *  - JSON парсится потоковым [JsonReader] (только title/artists, без
 *    полного дерева в памяти), кап [MAX_TRACKS];
 *  - звать ТОЛЬКО с Dispatchers.IO (блокирующий сетевой код).
 */
object YandexPlaylistFetcher {

    private const val MAX_HTML_BYTES = 8L * 1024 * 1024   // потолок страницы
    private const val MAX_TRACKS = 3000                    // кап треков
    private const val MIN_HTML_BYTES = 1000                // короче — обрубок

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    private const val YANDEX_API = "https://api.music.yandex.net"

    // Точечные регэкспы по паре десятков символов вокруг ключа — безопасны
    // (в отличие от .*?-сканов по всей странице).
    private val UID_RE = Regex("\"uid\":(\\d+)")
    private val KIND_RE = Regex("\"kind\":(\\d+)")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Разрешить URL плейлиста в список треков. Блокирующий вызов —
     * только с Dispatchers.IO. Бросает [YandexResolveException] с
     * готовым для UI сообщением.
     */
    fun resolve(url: String): List<YandexRawTrack> {
        if (!url.contains("music.yandex")) {
            throw YandexResolveException("Not a Yandex Music link")
        }
        val oldStyle = url.contains("/users/")
        val html = fetchPage(url, earlyExitOnUidKind = !oldStyle)

        if (!oldStyle) {
            val uid = UID_RE.find(html)?.groupValues?.getOrNull(1)
            val kind = KIND_RE.find(html)?.groupValues?.getOrNull(1)
            if (uid != null && kind != null) {
                val viaApi = fetchViaApi(uid, kind)
                if (viaApi.isNotEmpty()) return viaApi
            }
            // uid/kind не нашлись или API пуст — пробуем снапшот той же страницы.
        }
        return parseSnapshot(html)
    }

    // ── Шаг 1: страница плейлиста ──

    private fun fetchPage(url: String, earlyExitOnUidKind: Boolean): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9," +
                    "image/avif,image/webp,image/apng,*/*;q=0.8"
            )
            .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("DNT", "1")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Cache-Control", "max-age=0")
            .build()
        // Accept-Encoding не ставим руками: OkHttp сам добавит gzip и
        // прозрачно распакует.

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw YandexResolveException(
                "Cannot reach Yandex Music. Check your connection.", e
            )
        }

        response.use { resp ->
            when {
                resp.code == 403 || resp.code == 451 -> throw YandexResolveException(
                    "Yandex blocked the request (geo). If you're on VPN, try disabling it and retry."
                )
                resp.code == 404 -> throw YandexResolveException(
                    "Playlist not found on Yandex Music."
                )
                resp.code != 200 -> throw YandexResolveException(
                    "Yandex returned HTTP ${resp.code}."
                )
            }

            val source = resp.body?.source()
                ?: throw YandexResolveException("Yandex returned an empty page.")

            // Кусочное чтение с потолком; для uuid-ссылок — ранний выход,
            // как только оба ключа найдены (весь HTML не нужен).
            val sb = StringBuilder(256 * 1024)
            val chunk = okio.Buffer()
            var uidFound = false
            var kindFound = false
            while (sb.length < MAX_HTML_BYTES) {
                val read = try {
                    source.read(chunk, 64 * 1024L)
                } catch (e: Exception) {
                    if (sb.length >= MIN_HTML_BYTES) break   // хвост оборвался — работаем с тем, что есть
                    throw YandexResolveException("Connection to Yandex dropped mid-page.", e)
                }
                if (read == -1L) break
                val prevLen = sb.length
                sb.append(chunk.readUtf8())
                if (earlyExitOnUidKind) {
                    // ищем только в свежем куске (+нахлёст на границу)
                    val from = maxOf(0, prevLen - 16)
                    if (!uidFound) uidFound = sb.indexOf("\"uid\":", from) >= 0
                    if (!kindFound) kindFound = sb.indexOf("\"kind\":", from) >= 0
                    if (uidFound && kindFound) break
                }
            }

            if (sb.length < MIN_HTML_BYTES) {
                throw YandexResolveException("Yandex returned an empty or truncated page.")
            }
            return sb.toString()
        }
    }

    // ── Шаг 2а: новая ссылка → официальное API ──

    private fun fetchViaApi(uid: String, kind: String): List<YandexRawTrack> {
        val request = Request.Builder()
            .url("$YANDEX_API/users/$uid/playlists/$kind")
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .header("Referer", "https://music.yandex.ru/")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw YandexResolveException(
                "Cannot reach Yandex Music API. Check your connection.", e
            )
        }

        response.use { resp ->
            if (resp.code != 200) {
                throw YandexResolveException("Yandex API returned HTTP ${resp.code}.")
            }
            val stream = resp.body?.charStream()
                ?: throw YandexResolveException("Yandex API returned an empty body.")
            return try {
                JsonReader(stream).use { parseApiResponse(it) }
            } catch (e: YandexResolveException) {
                throw e
            } catch (e: Exception) {
                throw YandexResolveException("Failed to parse Yandex API response.", e)
            }
        }
    }

    /** result.tracks[].track.{title, artists[].name, major.name} — потоково. */
    private fun parseApiResponse(reader: JsonReader): List<YandexRawTrack> {
        val out = ArrayList<YandexRawTrack>()
        var apiError: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "result" -> {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "tracks" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                if (out.size >= MAX_TRACKS) { reader.skipValue(); continue }
                                parseApiTrackItem(reader)?.let { out.add(it) }
                            }
                            reader.endArray()
                        } else reader.skipValue()
                    }
                    reader.endObject()
                }
                "error" -> {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "name") apiError = readStringOrNull(reader)
                            else reader.skipValue()
                        }
                        reader.endObject()
                    } else reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (out.isEmpty() && apiError != null) {
            throw YandexResolveException("Yandex API error: $apiError")
        }
        return out
    }

    /** Элемент tracks[]: {track:{...}} — метаданные лежат в track. */
    private fun parseApiTrackItem(reader: JsonReader): YandexRawTrack? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null }
        var result: YandexRawTrack? = null
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "track" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                result = parseTrackObject(reader)
            } else reader.skipValue()
        }
        reader.endObject()
        return result
    }

    /** {title, artists[{name}], major{name}} → YandexRawTrack. */
    private fun parseTrackObject(reader: JsonReader): YandexRawTrack? {
        var title: String? = null
        val artistNames = ArrayList<String>(4)
        var majorName: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "title" -> title = readStringOrNull(reader)
                "artists" -> {
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); continue }
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "name") {
                                readStringOrNull(reader)?.let { artistNames.add(it) }
                            } else reader.skipValue()
                        }
                        reader.endObject()
                    }
                    reader.endArray()
                }
                "major" -> {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "name") majorName = readStringOrNull(reader)
                        else reader.skipValue()
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val t = title?.takeIf { it.isNotBlank() } ?: return null
        val artist = when {
            artistNames.isNotEmpty() -> artistNames.joinToString(", ")
            !majorName.isNullOrBlank() && majorName != "-" -> majorName!!
            else -> "Unknown Artist"
        }
        return YandexRawTrack(t, artist)
    }

    // ── Шаг 2б: старая ссылка → __STATE_SNAPSHOT__ из HTML ──

    /**
     * Ищем блоки `(window.__STATE_SNAPSHOT__ = ...).push({...})` линейным
     * indexOf + подсчётом скобок (регэксп с .*?+DOTALL на многомегабайтной
     * строке — бэктрекинг-бомба, поэтому НЕ он).
     */
    private fun parseSnapshot(html: String): List<YandexRawTrack> {
        var from = 0
        while (true) {
            val marker = html.indexOf("__STATE_SNAPSHOT__", from)
            if (marker < 0) break
            val push = html.indexOf(".push(", marker)
            if (push < 0) break
            val braceStart = html.indexOf('{', push)
            if (braceStart < 0) break

            val braceEnd = matchBrace(html, braceStart)
            if (braceEnd > braceStart) {
                val block = html.substring(braceStart, braceEnd + 1)
                val tracks = try {
                    JsonReader(StringReader(block)).use { r ->
                        r.isLenient = true
                        parseSnapshotBlock(r)
                    }
                } catch (_: Exception) {
                    emptyList()
                }
                if (tracks.isNotEmpty()) return tracks
                from = braceEnd + 1
            } else {
                from = push + 6
            }
        }
        throw YandexResolveException(
            "Playlist data not found on the page (private playlist or geo-block?)."
        )
    }

    /** Скобочный скан с учётом строк и экранирования — линейное время. */
    private fun matchBrace(s: String, start: Int): Int {
        var depth = 0
        var i = start
        var inString = false
        val cap = minOf(s.length, start + 6 * 1024 * 1024)   // сверх-потолок на блок
        while (i < cap) {
            val c = s[i]
            if (inString) {
                when (c) {
                    '\\' -> i++          // пропустить экранированный символ
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    /** {playlist:{items:[{data:{title, artists[], major}}]}} — потоково. */
    private fun parseSnapshotBlock(reader: JsonReader): List<YandexRawTrack> {
        val out = ArrayList<YandexRawTrack>()
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "playlist" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "items" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (out.size >= MAX_TRACKS) { reader.skipValue(); continue }
                            if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                            reader.beginObject()
                            while (reader.hasNext()) {
                                if (reader.nextName() == "data" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                                    parseTrackObject(reader)?.let { out.add(it) }
                                } else reader.skipValue()
                            }
                            reader.endObject()
                        }
                        reader.endArray()
                    } else reader.skipValue()
                }
                reader.endObject()
            } else reader.skipValue()
        }
        reader.endObject()
        return out
    }

    private fun readStringOrNull(reader: JsonReader): String? =
        if (reader.peek() == JsonToken.STRING) reader.nextString()
        else { reader.skipValue(); null }
}
