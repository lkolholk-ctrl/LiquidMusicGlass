package com.liquidmusicglass.data.yandex

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        /** Активная подписка Яндекс Плюс — без неё API не отдаёт полные треки на скачивание. */
        val hasPlus: Boolean = false,
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
            hasPlus = result.optJSONObject("plus")?.optBoolean("hasPlus", false) ?: false,
        )
    }

    /** Короткая запись лайка: id трека (+ albumId для точной версии релиза). */
    data class LikedRef(val id: String, val albumId: String?)

    /**
     * Лайкнутые треки юзера. `/users/{uid}/likes/tracks` отдаёт только id
     * (без названий/обложек) — метаданные добираются батчами POST /tracks.
     * Порядок как в ЯМ: свежие лайки первыми.
     */
    fun fetchLikedTracks(uid: Long, hydrateChunk: Int = 100): List<Track> {
        val root = getJson("$API/users/$uid/likes/tracks")
        val arr = root.optJSONObject("result")
            ?.optJSONObject("library")
            ?.optJSONArray("tracks")
            ?: return emptyList()
        val refs = ArrayList<LikedRef>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
            val albumId = o.opt("albumId")?.toString()?.takeIf { it.isNotBlank() }
            refs += LikedRef(id, albumId)
        }
        if (refs.isEmpty()) return emptyList()
        val out = ArrayList<Track>(refs.size)
        refs.chunked(hydrateChunk).forEach { chunk ->
            out += fetchTracksByIds(chunk)
        }
        return out
    }

    /** Лайкнуть трек в аккаунте: POST /users/{uid}/likes/tracks/add-multiple. */
    fun likeTrack(uid: Long, trackId: String) {
        postForm(
            "$API/users/$uid/likes/tracks/add-multiple",
            mapOf("track-ids" to trackId.substringBefore(":"))
        )
    }

    /** Снять лайк: POST /users/{uid}/likes/tracks/remove. */
    fun unlikeTrack(uid: Long, trackId: String) {
        postForm(
            "$API/users/$uid/likes/tracks/remove",
            mapOf("track-ids" to trackId.substringBefore(":"))
        )
    }

    /** Полные объекты треков по id: POST /tracks, form `track-ids=id:albumId,…`. */
    fun fetchTracksByIds(refs: List<LikedRef>): List<Track> {
        if (refs.isEmpty()) return emptyList()
        val ids = refs.joinToString(",") { ref ->
            if (ref.albumId != null) "${ref.id}:${ref.albumId}" else ref.id
        }
        val root = postForm(
            "$API/tracks",
            mapOf("track-ids" to ids, "with-positions" to "false")
        )
        val arr = root.optJSONArray("result") ?: return emptyList()
        return arr.mapTracks()
    }

    data class Playlist(
        val kind: Long,
        val title: String,
        val trackCount: Int,
        val coverUrl: String?,
    )

    /** Пачка треков ротора (волны): batchId нужен для фидбека обучения. */
    data class StationBatch(val batchId: String?, val tracks: List<Track>)

    /** Готовая станция ротора (жанр/настроение/активность). [id] = `type:tag`. */
    data class Station(val id: String, val name: String, val bgColor: String?)

    /** Каталог станций ротора: GET /rotor/stations/list. */
    fun rotorStations(): List<Station> {
        val root = getJson("$API/rotor/stations/list")
        val arr = root.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<Station>(arr.length())
        for (i in 0 until arr.length()) {
            val st = arr.optJSONObject(i)?.optJSONObject("station") ?: continue
            val idObj = st.optJSONObject("id") ?: continue
            val type = idObj.optString("type")
            val tag = idObj.optString("tag")
            if (type.isBlank() || tag.isBlank()) continue
            // Личную волну не дублируем — она отдельной кнопкой.
            if (type == "user" && tag == "onyourwave") continue
            out += Station(
                id = "$type:$tag",
                name = st.optString("name").ifBlank { tag },
                bgColor = st.optJSONObject("icon")?.optString("backgroundColor")?.takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    /**
     * Пачка треков станции ротора («Моя волна» = `user:onyourwave`).
     * [queue] — id последнего трека очереди: продолжение с учётом контекста.
     */
    fun rotorStationTracks(station: String, queue: String? = null): StationBatch {
        val url = buildString {
            append("$API/rotor/station/")
            append(urlEncode(station))
            append("/tracks?settings2=true")
            if (!queue.isNullOrBlank()) append("&queue=${urlEncode(queue)}")
        }
        val root = getJson(url)
        val result = root.optJSONObject("result") ?: return StationBatch(null, emptyList())
        val seq = result.optJSONArray("sequence") ?: JSONArray()
        val tracks = ArrayList<Track>(seq.length())
        for (i in 0 until seq.length()) {
            val t = seq.optJSONObject(i)?.optJSONObject("track") ?: continue
            parseTrack(t)?.let { tracks += it }
        }
        return StationBatch(
            batchId = result.optString("batchId").takeIf { it.isNotBlank() },
            tracks = tracks,
        )
    }

    /**
     * Фидбек ротору — то, чем волна обучается: `radioStarted` при старте,
     * `trackFinished`/`skip` по итогам прослушивания (с batchId пачки).
     */
    fun sendRotorFeedback(
        station: String,
        type: String,
        batchId: String? = null,
        trackId: String? = null,
        totalPlayedSeconds: Double? = null,
    ) {
        val url = buildString {
            append("$API/rotor/station/")
            append(urlEncode(station))
            append("/feedback")
            if (!batchId.isNullOrBlank()) append("?batch-id=${urlEncode(batchId)}")
        }
        val body = JSONObject().apply {
            put("type", type)
            put("timestamp", java.time.Instant.now().toString())
            if (!trackId.isNullOrBlank()) put("trackId", trackId)
            if (totalPlayedSeconds != null) put("totalPlayedSeconds", totalPlayedSeconds)
            if (type == "radioStarted") put("from", "mobile-radio-user-onyourwave")
        }
        postJson(url, body)
    }

    /** Собственные плейлисты юзера: GET /users/{uid}/playlists/list. */
    fun fetchUserPlaylists(uid: Long): List<Playlist> {
        val root = getJson("$API/users/$uid/playlists/list")
        val arr = root.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<Playlist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = o.optLong("kind", -1L)
            if (kind < 0) continue
            out += Playlist(
                kind = kind,
                title = o.optString("title").ifBlank { "Playlist $kind" },
                trackCount = o.optInt("trackCount", 0),
                coverUrl = playlistCoverUrl(o),
            )
        }
        return out
    }

    /**
     * Треки плейлиста: GET /users/{uid}/playlists/{kind}. Обычно объекты
     * треков вложены целиком; короткие записи (только id) добираются батчем.
     */
    fun fetchPlaylistTracks(uid: Long, kind: Long): List<Track> {
        val root = getJson("$API/users/$uid/playlists/$kind")
        val arr = root.optJSONObject("result")?.optJSONArray("tracks") ?: return emptyList()
        val out = ArrayList<Track>(arr.length())
        val refs = ArrayList<LikedRef>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val trackObj = item.optJSONObject("track")
            if (trackObj != null) {
                parseTrack(trackObj)?.let { out += it }
            } else {
                val id = item.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val albumId = item.opt("albumId")?.toString()?.takeIf { it.isNotBlank() }
                refs += LikedRef(id, albumId)
            }
        }
        if (refs.isNotEmpty()) {
            refs.chunked(100).forEach { chunk -> out += fetchTracksByIds(chunk) }
        }
        return out
    }

    /** Обложка плейлиста: одиночная (`cover.uri`) или мозаика (`itemsUri[0]`). */
    private fun playlistCoverUrl(o: JSONObject): String? {
        val cover = o.optJSONObject("cover") ?: return null
        val uri = cover.optString("uri").ifBlank {
            cover.optJSONArray("itemsUri")?.optString(0).orEmpty()
        }
        return coverUriToUrl(uri.takeIf { it.isNotBlank() }, size = "400x400")
    }

    /**
     * Текст трека: сначала синхронизированный LRC (подписанный
     * /tracks/{id}/lyrics → downloadUrl), затем обычный текст из
     * /tracks/{id}/supplement. Возвращает сырой LRC/plain или null.
     */
    fun fetchLyrics(trackId: String): String? {
        val bare = trackId.substringBefore(":")
        fetchSyncedLyrics(bare)?.let { return it }
        return fetchSupplementLyrics(bare)
    }

    /** Подписанный синхро-LRC: sign = base64(HMAC(secret, ts+trackId))[:-1]. */
    private fun fetchSyncedLyrics(trackId: String): String? {
        val ts = System.currentTimeMillis() / 1000
        val sign = hmacSha256Base64(SIGN_SECRET, "$ts$trackId").dropLast(1)
        val url = "$API/tracks/$trackId/lyrics?format=LRC&timeStamp=$ts&sign=${urlEncode(sign)}"
        val result = try {
            getJson(url).optJSONObject("result")
        } catch (_: Exception) {
            null
        } ?: return null
        val downloadUrl = result.optString("downloadUrl").takeIf { it.isNotBlank() } ?: return null
        return downloadPlainText(downloadUrl)
    }

    /** Обычный (не синхронизированный) текст из supplement. */
    private fun fetchSupplementLyrics(trackId: String): String? {
        val root = try {
            getJson("$API/tracks/$trackId/supplement")
        } catch (_: Exception) {
            return null
        }
        return root.optJSONObject("result")
            ?.optJSONObject("lyrics")
            ?.optString("fullLyrics")
            ?.takeIf { it.isNotBlank() }
    }

    /** GET текстового тела без авторизации (downloadUrl — уже подписанная ссылка). */
    private fun downloadPlainText(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
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
     * Лучший битрейт — первым. Это lossy-путь (mp3/aac); FLAC — отдельно
     * через [getLosslessInfo].
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
     * Прямая ссылка выбранного качества:
     *  - LOSSLESS → FLAC через /get-file-info (при неудаче — best lossy);
     *  - HIGH     → лучший битрейт из download-info (mp3 320 / aac 256);
     *  - NORMAL   → mp3 ближе к 192 (экономия трафика/места).
     * FLAC требует активной подписки Плюс — сервер иначе не отдаёт lossless.
     */
    fun getStreamInfo(trackId: String, quality: YandexQuality): DownloadInfo? {
        if (quality == YandexQuality.LOSSLESS) {
            getLosslessInfo(trackId)?.let { return it }
            // Нет lossless (нет Плюса / трек без FLAC) — деградируем в best lossy.
        }
        val infos = getDownloadInfo(trackId)
        return when (quality) {
            YandexQuality.NORMAL ->
                infos.filter { it.codec.equals("mp3", ignoreCase = true) }
                    .minByOrNull { kotlin.math.abs(it.bitrateInKbps - 192) }
                    ?: infos.firstOrNull()
            else -> infos.firstOrNull() // HIGH или LOSSLESS-фолбэк = лучший доступный
        }
    }

    /**
     * FLAC lossless: /get-file-info с HMAC-подписью параметров.
     *
     * Схема (реверс официального мобильного клиента, как md5-соль у lossy):
     * sign = base64(HMAC_SHA256(SIGN_SECRET, ts+trackId+quality+codecs_без_запятых+transports))
     * без последнего символа. transports=raw → в ответе прямая (не зашифрованная)
     * ссылка на файл. Возвращает null, если lossless недоступен.
     */
    private fun getLosslessInfo(trackId: String): DownloadInfo? {
        val bare = trackId.substringBefore(":")
        val ts = System.currentTimeMillis() / 1000
        val quality = "lossless"
        val codecs = "flac,aac,he-aac,mp3,aac-preview,mp3-preview"
        val transports = "raw"
        val message = "$ts$bare$quality${codecs.replace(",", "")}$transports"
        val sign = hmacSha256Base64(SIGN_SECRET, message).dropLast(1)
        val url = "$API/get-file-info?ts=$ts&trackId=${urlEncode(bare)}" +
            "&quality=$quality&codecs=${urlEncode(codecs)}" +
            "&transports=$transports&sign=${urlEncode(sign)}"
        val di = try {
            getJson(url).optJSONObject("result")?.optJSONObject("downloadInfo")
        } catch (_: Exception) {
            null
        } ?: return null
        val direct = di.optString("url").ifBlank {
            di.optJSONArray("urls")?.optString(0).orEmpty()
        }
        if (direct.isBlank()) return null
        return DownloadInfo(
            codec = di.optString("codec", "flac"),
            bitrateInKbps = di.optInt("bitrate", 0),
            directLink = direct,
        )
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
        quality: YandexQuality = YandexQuality.HIGH,
        onProgress: (Float) -> Unit = {},
        shouldAbort: () -> Boolean = { false },
    ): DownloadInfo {
        var last: Exception? = null
        repeat(5) { attempt ->
            try {
                val best = getStreamInfo(trackId, quality)
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

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "OAuth $oauthToken")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                throw YandexUnauthorizedException("HTTP ${resp.code}: unauthorized")
            }
            if (!resp.isSuccessful) {
                throw YandexMusicException("HTTP ${resp.code} for ${safePath(url)}")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun postForm(url: String, form: Map<String, String>): JSONObject {
        val body = okhttp3.FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "OAuth $oauthToken")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                throw YandexUnauthorizedException("HTTP ${resp.code}: unauthorized")
            }
            if (!resp.isSuccessful) {
                throw YandexMusicException("HTTP ${resp.code} for ${safePath(url)}")
            }
            return JSONObject(text)
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

    // 700x700: хватает и для артворка полного плеера, и для Palette;
    // 200x200 в полном плеере мылилось.
    private fun coverUriToUrl(coverUri: String?, size: String = "700x700"): String? {
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
        // Ключ подписи /get-file-info (FLAC). Реверс мобильного клиента —
        // тот же класс «известной константы», что и SALT для lossy-ссылок.
        private const val SIGN_SECRET = "kzqU4XhfCaY6B6JTHODeq5"

        private fun hmacSha256Base64(secret: String, message: String): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val digest = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            return java.util.Base64.getEncoder().encodeToString(digest)
        }

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

/** Пресет качества стрима/скачивания Y. FLAC требует подписки Плюс. */
enum class YandexQuality { NORMAL, HIGH, LOSSLESS }

open class YandexMusicException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class YandexUnauthorizedException(message: String) : YandexMusicException(message)

/** Загрузка прервана пользователем — не ошибка, ретраить не нужно. */
class YandexDownloadCancelledException : YandexMusicException("Download cancelled")
