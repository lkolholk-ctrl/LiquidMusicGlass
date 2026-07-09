package com.liquidmusicglass.data.yandex

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Клиент API Яндекс.Музыки (порт схемы userbot-модуля Yandex_Music.py /
 * библиотеки yandex-music: OAuth + search + download-info).
 *
 * Синхронный — звать только с [Dispatchers.IO].
 * Токен: [YandexAuthRepository].
 */
class YandexMusicClient(
    private val oauthToken: String,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    init {
        require(oauthToken.isNotBlank()) { "Yandex OAuth token is required" }
    }

    // ── models ───────────────────────────────────────────────

    data class Artist(val id: Long?, val name: String)

    data class Track(
        val id: String,
        val title: String,
        val artists: List<Artist>,
        val durationMs: Long,
        val available: Boolean = true,
        val albumTitle: String? = null,
        /** HTTPS cover URL (size already substituted), or null. */
        val coverUrl: String? = null,
    ) {
        val artistsLine: String get() = artists.joinToString(", ") { it.name }
        val bareTrackId: String get() = id.substringBefore(":")
    }

    data class AccountInfo(
        val uid: Long,
        val login: String?,
        val displayName: String?,
    )

    data class DownloadInfo(
        val codec: String,
        val bitrateInKbps: Int,
        val directLink: String,
    )

    // ── public API ───────────────────────────────────────────

    /**
     * Проверка токена: GET /account/status.
     * Бросает [YandexUnauthorizedException] при 401/403.
     */
    fun fetchAccount(): AccountInfo {
        val root = getJson("$API/account/status")
        val result = root.optJSONObject("result")
            ?: throw YandexMusicException("Empty account/status")
        val account = result.optJSONObject("account")
            ?: throw YandexMusicException("No account in status")
        val uid = account.optLong("uid", 0L)
        if (uid == 0L) throw YandexUnauthorizedException("Invalid token (no uid)")
        return AccountInfo(
            uid = uid,
            login = account.optString("login").takeIf { it.isNotBlank() },
            displayName = account.optJSONObject("displayName")
                ?.optString("name")
                ?.takeIf { it.isNotBlank() }
                ?: account.optString("fullName").takeIf { it.isNotBlank() },
        )
    }

    /** Python: `client.search(query, type_="track")` */
    fun searchTracks(query: String, page: Int = 0): List<Track> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = "$API/search?text=${urlEncode(q)}&type=track&page=$page&nocorrect=false"
        val root = getJson(url)
        val results = root.optJSONObject("result")
            ?.optJSONObject("tracks")
            ?.optJSONArray("results")
            ?: return emptyList()
        return results.mapTracks()
    }

    /**
     * Python: `tracks_download_info(id, get_direct_links=True)`.
     * Лучший битрейт — первым.
     */
    fun getDownloadInfo(trackId: String): List<DownloadInfo> {
        val bare = trackId.substringBefore(":")
        val root = getJson("$API/tracks/$bare/download-info")
        val arr = root.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<DownloadInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val infoUrl = item.optString("downloadInfoUrl")
            if (infoUrl.isBlank()) continue
            val direct = resolveDirectLink(infoUrl) ?: continue
            out += DownloadInfo(
                codec = item.optString("codec", "mp3"),
                bitrateInKbps = item.optInt("bitrateInKbps", 0),
                directLink = direct,
            )
        }
        return out.sortedByDescending { it.bitrateInKbps }
    }

    /**
     * Скачать трек стримингом сразу в [dest] — без буферизации всего файла в
     * памяти, с реальным прогрессом по байтам. До 5 попыток (как в python-порте
     * `__download_track`). [shouldAbort] проверяется между чанками: отмена
     * прерывает запись через [YandexDownloadCancelledException] без ретраев.
     * Возвращает метаданные выбранного варианта (codec/bitrate).
     */
    fun downloadTrackToFileStreaming(
        trackId: String,
        dest: File,
        onProgress: (Float) -> Unit = {},
        shouldAbort: () -> Boolean = { false },
    ): DownloadInfo {
        var last: Exception? = null
        repeat(5) { attempt ->
            try {
                val best = getDownloadInfo(trackId).firstOrNull()
                    ?: throw YandexMusicException("No download-info for track $trackId")
                downloadUrlToFile(best.directLink, dest, onProgress, shouldAbort)
                return best
            } catch (e: YandexDownloadCancelledException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt < 4) Thread.sleep(1000L)
            }
        }
        throw (last ?: YandexMusicException("Download failed for $trackId"))
    }

    /** Скачать обложку (если URL есть). */
    fun downloadCoverBytes(coverUrl: String): ByteArray? = try {
        downloadUrl(coverUrl)
    } catch (_: Exception) {
        null
    }

    // ── HTTP ─────────────────────────────────────────────────

    private fun authorizedRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "OAuth $oauthToken")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
            .get()
            .build()

    private fun getJson(url: String): JSONObject {
        http.newCall(authorizedRequest(url)).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                // Без тела ответа и без токена — не светим секреты в UI/log
                throw YandexUnauthorizedException("HTTP ${resp.code}: unauthorized")
            }
            if (!resp.isSuccessful) {
                // path only, no Authorization, no raw body dump (может содержать PII)
                throw YandexMusicException("HTTP ${resp.code} for ${safePath(url)}")
            }
            return JSONObject(body)
        }
    }

    private fun safePath(url: String): String =
        try {
            val u = java.net.URI(url)
            u.path ?: url
        } catch (_: Exception) {
            "request"
        }

    private fun downloadUrl(url: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw YandexMusicException("Download HTTP ${resp.code}")
            return resp.body?.bytes() ?: throw YandexMusicException("Empty body")
        }
    }

    /** Стриминг тела ответа в файл чанками по 64 КБ с прогрессом по байтам. */
    private fun downloadUrlToFile(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
        shouldAbort: () -> Boolean,
    ) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw YandexMusicException("Download HTTP ${resp.code}")
            val body = resp.body ?: throw YandexMusicException("Empty body")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        if (shouldAbort()) throw YandexDownloadCancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            onProgress((read.toDouble() / total).toFloat().coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
    }

    /**
     * yandex_music DownloadInfo.get_direct_link:
     * sign = md5("XGRlBW9FXlekgbPrRHuSiA" + path[1:] + s)
     */
    private fun resolveDirectLink(downloadInfoUrl: String): String? {
        val req = Request.Builder()
            .url(downloadInfoUrl)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val raw = resp.body?.string().orEmpty()
            if (raw.isBlank()) return null

            val host: String
            val path: String
            val ts: String
            val s: String
            if (raw.trimStart().startsWith("{")) {
                val j = JSONObject(raw)
                host = j.optString("host")
                path = j.optString("path")
                ts = j.optString("ts")
                s = j.optString("s")
            } else {
                host = xmlTag(raw, "host") ?: return null
                path = xmlTag(raw, "path") ?: return null
                ts = xmlTag(raw, "ts") ?: return null
                s = xmlTag(raw, "s") ?: return null
            }
            if (host.isBlank() || path.isBlank() || ts.isBlank() || s.isBlank()) return null
            val pathForSign = if (path.startsWith("/")) path.drop(1) else path
            val sign = md5Hex(SALT + pathForSign + s)
            return "https://$host/get-mp3/$sign/$ts$path"
        }
    }

    private fun JSONArray.mapTracks(): List<Track> {
        val out = ArrayList<Track>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            parseTrack(o)?.let { out += it }
        }
        return out
    }

    private fun parseTrack(o: JSONObject): Track? {
        val id = o.opt("id")?.toString() ?: return null
        val title = o.optString("title").ifBlank { return null }
        val artistsArr = o.optJSONArray("artists") ?: JSONArray()
        val artists = ArrayList<Artist>(artistsArr.length())
        for (i in 0 until artistsArr.length()) {
            val a = artistsArr.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (name.isBlank()) continue
            artists += Artist(
                id = a.optLong("id").takeIf { a.has("id") },
                name = name,
            )
        }
        val duration = o.optLong("durationMs", o.optLong("duration_ms", 0L))
        val album0 = o.optJSONArray("albums")?.optJSONObject(0)
        val albumId = album0?.opt("id")?.toString()
        val albumTitle = album0?.optString("title")?.takeIf { it.isNotBlank() }
        val fullId = if (albumId != null) "$id:$albumId" else id
        val coverUri = o.optString("coverUri").ifBlank {
            album0?.optString("coverUri").orEmpty()
        }
        val coverUrl = coverUriToUrl(coverUri)
        return Track(
            id = fullId,
            title = title,
            artists = artists,
            durationMs = duration,
            available = o.optBoolean("available", true),
            albumTitle = albumTitle,
            coverUrl = coverUrl,
        )
    }

    private fun coverUriToUrl(coverUri: String?, size: String = "200x200"): String? {
        if (coverUri.isNullOrBlank()) return null
        val path = coverUri.replace("%%", size)
        return if (path.startsWith("http")) path else "https://$path"
    }

    companion object {
        private const val API = "https://api.music.yandex.net"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val SALT = "XGRlBW9FXlekgbPrRHuSiA"

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        private fun md5Hex(s: String): String {
            val dig = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            return dig.joinToString("") { "%02x".format(it) }
        }

        private fun urlEncode(s: String): String =
            java.net.URLEncoder.encode(s, Charsets.UTF_8.name())

        private fun xmlTag(xml: String, tag: String): String? {
            val open = "<$tag>"
            val close = "</$tag>"
            val i = xml.indexOf(open)
            if (i < 0) return null
            val start = i + open.length
            val j = xml.indexOf(close, start)
            if (j < 0) return null
            return xml.substring(start, j).trim()
        }
    }
}

open class YandexMusicException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class YandexUnauthorizedException(message: String) : YandexMusicException(message)

/** Загрузка прервана пользователем — не ошибка, ретраить не нужно. */
class YandexDownloadCancelledException : YandexMusicException("Download cancelled")
