package com.liquidmusicglass.data.playlistimport

import com.liquidmusicglass.security.ResolveNative
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Трек, извлечённый из публичного плейлиста Яндекс Музыки. */
data class YandexRawTrack(val title: String, val artist: String)

/** Ошибка резолва с человекочитаемым сообщением для UI импорта. */
class YandexResolveException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * On-device резолвер публичных плейлистов Яндекс Музыки — БЕЗ сервера.
 *
 * Батч 16 (#82): вся логика скрейпа (эндпоинт официального API, User-Agent,
 * ru-локаль, маркеры uid/kind/__STATE_SNAPSHOT__ и парсинг title/artists/major)
 * уехала в натив [ResolveNative] / liblmg_resolve.so — в декомпиле Kotlin её
 * больше не видно. Здесь остался только HTTP (OkHttp): натив строит URL/
 * заголовки и парсит тело, сокет — тут.
 *
 * Схема (реализована в resolve.cpp):
 *  1. Страница плейлиста тянется с заголовками живого Chrome + ru-локалью
 *     (без них Яндекс отдаёт 403/451).
 *  2. Новая ссылка: из HTML достаём uid/kind владельца → официальное
 *     api.music.yandex.net отвечает без авторизации чистым JSON.
 *  3. Старая ссылка / фолбэк: парсим __STATE_SNAPSHOT__ из HTML.
 *
 * Звать ТОЛЬКО с Dispatchers.IO (блокирующий сетевой код).
 */
object YandexPlaylistFetcher {

    private const val MAX_HTML_BYTES = 8L * 1024 * 1024   // потолок страницы
    private const val MIN_HTML_BYTES = 1000               // короче — обрубок

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

    /**
     * Разрешить URL плейлиста в список треков. Блокирующий вызов — только с
     * Dispatchers.IO. Бросает [YandexResolveException] с готовым для UI текстом.
     */
    fun resolve(url: String): List<YandexRawTrack> {
        if (!url.contains("music.yandex"))
            throw YandexResolveException("Not a Yandex Music link")
        val oldStyle = url.contains("/users/")
        val htmlBytes = fetchPage(url)

        if (!oldStyle) {
            // Новая ссылка: uid/kind → официальное API (полный список треков).
            val apiUrl = ResolveNative.yandexApiUrl(htmlBytes)
            if (apiUrl.isNotBlank()) {
                val viaApi = fetchViaApi(apiUrl)
                if (viaApi.isNotEmpty()) return viaApi
            }
            // uid/kind не нашлись или API пуст — падаем в снапшот той же страницы.
        }

        val snap = splitRecords(ResolveNative.yandexSnapshotParse(htmlBytes))
        if (snap.isEmpty())
            throw YandexResolveException(
                "Playlist data not found on the page (private playlist or geo-block?)."
            )
        return snap
    }

    // ── Шаг 1: страница плейлиста ──

    private fun fetchPage(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            // Секретные значения (UA/Accept/ru-локаль) — из натива.
            .header("User-Agent", ResolveNative.yandexUa())
            .header("Accept", ResolveNative.yandexAccept())
            .header("Accept-Language", ResolveNative.yandexAcceptLang())
            // Дженерик-заголовки браузера (не секрет, одинаковы для любого скрейпа).
            .header("DNT", "1")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Cache-Control", "max-age=0")
            .build()
        // Accept-Encoding не ставим руками: OkHttp сам добавит gzip и распакует.

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw YandexResolveException("Cannot reach Yandex Music. Check your connection.", e)
        }

        response.use { resp ->
            when {
                resp.code == 403 || resp.code == 451 -> throw YandexResolveException(
                    "Yandex blocked the request (geo). If you're on VPN, try disabling it and retry."
                )
                resp.code == 404 -> throw YandexResolveException("Playlist not found on Yandex Music.")
                resp.code != 200 -> throw YandexResolveException("Yandex returned HTTP ${resp.code}.")
            }

            val source = resp.body?.source()
                ?: throw YandexResolveException("Yandex returned an empty page.")

            val acc = okio.Buffer()
            while (acc.size < MAX_HTML_BYTES) {
                val read = try {
                    source.read(acc, 64 * 1024L)
                } catch (e: Exception) {
                    if (acc.size >= MIN_HTML_BYTES) break   // хвост оборвался — работаем с тем, что есть
                    throw YandexResolveException("Connection to Yandex dropped mid-page.", e)
                }
                if (read == -1L) break
            }
            if (acc.size < MIN_HTML_BYTES)
                throw YandexResolveException("Yandex returned an empty or truncated page.")
            return acc.readByteArray()
        }
    }

    // ── Шаг 2: новая ссылка → официальное API ──

    private fun fetchViaApi(apiUrl: String): List<YandexRawTrack> {
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", ResolveNative.yandexUa())
            .header("Accept", ResolveNative.yandexApiAccept())
            .header("Accept-Language", ResolveNative.yandexAcceptLang())
            .header("Referer", ResolveNative.yandexReferer())
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw YandexResolveException("Cannot reach Yandex Music API. Check your connection.", e)
        }

        response.use { resp ->
            if (resp.code != 200)
                throw YandexResolveException("Yandex API returned HTTP ${resp.code}.")
            val body = resp.body?.bytes()
                ?: throw YandexResolveException("Yandex API returned an empty body.")
            return splitRecords(ResolveNative.yandexApiParse(body))
        }
    }

    /** UTF-8 из натива: записи через RS(0x1E), поля title/artist — US(0x1F). */
    private fun splitRecords(bytes: ByteArray): List<YandexRawTrack> {
        if (bytes.isEmpty()) return emptyList()
        return String(bytes, Charsets.UTF_8)
            .split(REC_SEP)
            .mapNotNull { rec ->
                val parts = rec.split(FIELD_SEP)
                val title = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artist = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                YandexRawTrack(title, artist)
            }
    }
}
