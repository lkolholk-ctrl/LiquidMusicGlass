package com.liquidmusicglass.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics Parser — извлечение и разбор текстов песен.
 *
 * Источники (по приоритету):
 * 1. ICM API lyrics — официальный текст от партнёра
 * 2. Embedded lyrics из тегов аудиофайлов
 * 3. Plain text fallback
 */
object LyricsParser {

    private const val LRCLIB_BASE = "https://lrclib.net/api"

    // In-memory lyrics cache — key: trackId. Concurrent: пишется/читается с
    // разных корутин (строка лирики на Wave, LyricsSheet, полный экран).
    private val lyricsCache = java.util.concurrent.ConcurrentHashMap<String, Lyrics>()

    fun getCachedLyrics(trackId: String?): Lyrics? {
        if (trackId.isNullOrBlank()) return null
        return lyricsCache[trackId]
    }

    fun cacheLyrics(trackId: String, lyrics: Lyrics) {
        lyricsCache[trackId] = lyrics
    }

    /** Сброс in-memory кэша при давлении на память (onTrimMemory): лирика
     *  перезагрузится из сети/файла при следующем обращении. */
    fun trimCache() {
        lyricsCache.clear()
    }

    /** Одно слово с таймкодом (word-level / Enhanced LRC). */
    data class LyricWord(
        val timeMs: Long,
        val text: String
    )

    data class LyricLine(
        val timeMs: Long,    // -1 если нет таймстампа
        val text: String,
        /** Непусто → у строки есть пословные тайминги (Enhanced LRC). */
        val words: List<LyricWord> = emptyList(),
        /**
         * Реальный конец строки, если он был размечен в LRC пустым таймкодом.
         * 0 — неизвестен, длительность придётся оценивать по длине текста.
         */
        val endMs: Long = 0L
    )

    data class Lyrics(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val title: String?,
        val artist: String?,
        // источники: "embedded", "lrclib", "icm", "mine_word" (моя пословная), "none"
        val source: String = "none"
    ) {
        /** Есть ли пословная разметка (караоке-подсветка вместо построчной). */
        val isWordLevel: Boolean get() = lines.any { it.words.isNotEmpty() }

        companion object {
            val EMPTY = Lyrics(emptyList(), false, null, null, "none")
        }
    }

    /**
     * Извлекает lyrics из аудиофайла (embedded только).
     */
    fun extractLyrics(context: Context, uri: Uri): Lyrics {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val rawLyrics = tryExtractEmbedded(mmr)
            if (rawLyrics.isNullOrBlank()) {
                Lyrics.EMPTY
            } else {
                parseLyrics(rawLyrics).copy(source = "embedded")
            }
        } catch (_: Exception) {
            Lyrics.EMPTY
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    /**
     * Ищет lyrics онлайн через ICM API.
     * Официальный источник текстов от партнёра.
     */
    suspend fun fetchOnlineLyrics(
        trackId: String,
        title: String,
        artist: String
    ): Lyrics = withContext(Dispatchers.IO) {
        // Return cached lyrics instantly if available
        getCachedLyrics(trackId)?.let { return@withContext it }

        if (trackId.startsWith("vk_") || trackId.startsWith("secondary_")) {
            return@withContext Lyrics.EMPTY
        }

        try {
            // Docs: "На холодных Apple-треках первый запрос может занять до 10 секунд"
            val outcome = kotlinx.coroutines.withTimeout(12_000) {
                com.liquidmusicglass.api.icm.IcmRepository.getLyricsResult(trackId)
            }
            val response = outcome.getOrNull()
            if (response != null && !response.lyrics.isNullOrBlank()) {
                val parsed = parseLyrics(response.lyrics)
                if (parsed.lines.isNotEmpty()) {
                    val result = parsed.copy(
                        title = title,
                        artist = artist,
                        source = "icm"
                    )
                    cacheLyrics(trackId, result)
                    return@withContext result
                }
            }
            // Негатив запоминаем ТОЛЬКО когда сервер ответил и текста у трека нет.
            // Без этого каждое открытие шторки на инструментале стоило до 12 секунд
            // ожидания и запроса в общую rate-limit-группу. Отказ по правам (401/403)
            // за «нет текста» не считаем: подписка может появиться в этом же сеансе.
            val err = outcome.exceptionOrNull() as? com.liquidmusicglass.api.icm.IcmApiException
            val absent = response != null || err?.code == 404 || err?.errorCode == "lyrics_not_found"
            if (absent) cacheLyrics(trackId, Lyrics.EMPTY)
            Lyrics.EMPTY
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            android.util.Log.w("LyricsParser", "Lyrics fetch timeout for $trackId")
            Lyrics.EMPTY
        } catch (_: Exception) {
            Lyrics.EMPTY
        }
    }

    /**
     * Полный поиск.
     * ЛОКАЛЬНОЕ аудио → LRCLIB (синхро-текст) + локальный кэш, потом embedded.
     * Y-трек → текст из Яндекса. СТРИМИНГ/ICM → как было.
     */
    suspend fun loadLyrics(
        context: Context,
        uri: Uri?,
        title: String,
        artist: String,
        durationMs: Long,
        trackId: String? = null
    ): Lyrics {
        // Своя пословная разметка (Enhanced LRC) временно не используется: пока
        // у ICM нет пословных таймингов, подсветка идёт целыми строками, а
        // ручная разметка на паре треков давала бы поведение, отличное от всей
        // остальной библиотеки. Загрузчик и парсер оставлены — вернём, когда
        // пословные тайминги появятся в API.

        // ── ЛОКАЛЬНЫЙ трек: источник синхро-текстов — LRCLIB (только для локального) ──
        if (isLocalTrack(uri, trackId)) {
            val lrc = fetchLrcLib(context, uri, title, artist, durationMs, trackId)
            if (lrc.lines.isNotEmpty()) return lrc
            // запасной — embedded из тегов (если вдруг есть)
            if (uri != null) {
                val embedded = extractLyrics(context, uri)
                if (embedded.lines.isNotEmpty()) return embedded
            }
            return Lyrics.EMPTY
        }

        // ── Y-трек (ym_…): синхро-текст из LRCLIB. У Яндекса тексты от Musixmatch
        //    отдаются только как plain (без таймкодов) — LRCLIB даёт синхро-LRC,
        //    поэтому для Y-треков берём именно его (по названию/артисту/длительности). ──
        if (!trackId.isNullOrBlank() &&
            com.liquidmusicglass.data.yandex.YandexDownloadManager.isYandexId(trackId)
        ) {
            return fetchLrcLib(
                context = context,
                uri = null,
                title = title,
                artist = artist,
                durationMs = durationMs,
                trackId = trackId
            )
        }

        // ── СТРИМИНГ/ICM: без изменений ──
        // 1. Try ICM API lyrics (primary source)
        if (!trackId.isNullOrBlank()) {
            val icm = fetchOnlineLyrics(trackId, title, artist)
            if (icm.lines.isNotEmpty()) return icm
        }

        // 2. Try embedded
        if (uri != null) {
            val embedded = extractLyrics(context, uri)
            if (embedded.lines.isNotEmpty()) return embedded
        }

        return Lyrics.EMPTY
    }

    /** Локальный трек: content:// (MediaStore) / file:// или trackId "local:…". Стриминг — https. */
    private fun isLocalTrack(uri: Uri?, trackId: String?): Boolean {
        if (trackId?.startsWith("local:") == true) return true
        return when (uri?.scheme) {
            "content", "file" -> true
            else -> false
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  LRCLIB (lrclib.net) — ТОЛЬКО для локального аудио
    // ═══════════════════════════════════════════════════════════

    private const val USER_AGENT =
        "LiquidMusicGlass/2026.05.30 (https://github.com/lkolholk-ctrl/liquidmusicglass)"

    /**
     * Тянет синхро-текст из LRCLIB по метаданным локального трека. Сначала локальный
     * кэш (.lrc в cacheDir → офлайн), затем сеть (/api/get → фолбэк /api/search).
     * Любая ошибка/404/нет сети → Lyrics.EMPTY (UI покажет «нет текста»), без краша.
     */
    suspend fun fetchLrcLib(
        context: Context,
        uri: Uri?,
        title: String,
        artist: String,
        durationMs: Long,
        trackId: String?
    ): Lyrics = withContext(Dispatchers.IO) {
        if (title.isBlank() && artist.isBlank()) return@withContext Lyrics.EMPTY

        // Память: уже искали этот трек в сессии (в т.ч. негатив EMPTY) — не дёргаем снова.
        if (!trackId.isNullOrBlank()) lyricsCache[trackId]?.let { return@withContext it }

        val album = readAlbumTag(context, uri)
        val durationSec = (durationMs / 1000L).toInt()
        val cacheFile = lrcCacheFile(context, cacheKey(artist, title, album, durationSec))

        // 1. Локальный .lrc кэш (работает офлайн)
        readTextOrNull(cacheFile)?.let { cached ->
            return@withContext finalizeLrc(cached, title, artist, trackId)
        }

        // 2. Сеть: /api/get (точное совпадение) → /api/search (фолбэк по track+artist)
        val primary = httpGetLrcLib(buildGetUrl(artist, title, album, durationSec))
        val fetched = if (primary is LrcFetch.Found) primary else {
            val fallback = httpGetLrcLib(buildSearchUrl(artist, title))
            when {
                fallback is LrcFetch.Found -> fallback
                // Хоть одна попытка не дошла до сервера — мы просто не знаем,
                // есть текст или нет, и делать вывод не имеем права.
                primary is LrcFetch.Failed || fallback is LrcFetch.Failed -> LrcFetch.Failed
                else -> LrcFetch.Absent
            }
        }

        when (fetched) {
            is LrcFetch.Found -> {
                writeTextSafe(cacheFile, fetched.raw)     // на диск → офлайн в будущем
                finalizeLrc(fetched.raw, title, artist, trackId)
            }
            LrcFetch.Absent -> {
                // Сервер ответил, текста нет → помним на сеанс, на диск не пишем.
                if (!trackId.isNullOrBlank()) cacheLyrics(trackId, Lyrics.EMPTY)
                Lyrics.EMPTY
            }
            // Сбой связи не запоминаем: следующая попытка должна сходить в сеть.
            LrcFetch.Failed -> Lyrics.EMPTY
        }
    }

    /**
     * Сохранить только что ОПУБЛИКОВАННЫЙ в LRCLIB текст в локальный кэш, чтобы он
     * сразу работал в существующем UI лирики (без повторного запроса в сеть).
     * lrcText — syncedLyrics (LRC) если есть, иначе plainLyrics. Зовётся после успеха.
     */
    fun cachePublishedLyrics(
        context: Context,
        artist: String,
        title: String,
        album: String,
        durationSec: Int,
        lrcText: String,
        trackId: String?
    ) {
        if (lrcText.isBlank()) return
        writeTextSafe(lrcCacheFile(context, cacheKey(artist, title, album, durationSec)), lrcText)
        if (!trackId.isNullOrBlank()) {
            val parsed = parseLyrics(lrcText)
            if (parsed.lines.isNotEmpty()) cacheLyrics(trackId, parsed.copy(title = title, artist = artist, source = "lrclib"))
        }
    }

    private fun finalizeLrc(raw: String, title: String, artist: String, trackId: String?): Lyrics {
        val parsed = parseLyrics(raw)
        if (parsed.lines.isEmpty()) return Lyrics.EMPTY
        val result = parsed.copy(title = title, artist = artist, source = "lrclib")
        if (!trackId.isNullOrBlank()) cacheLyrics(trackId, result)
        return result
    }

    /**
     * Исход запроса к LRCLIB. Раньше метод отдавал `null` и на «текста нет», и на
     * «сеть отвалилась» — вызывающий запоминал сетевой сбой как отсутствие текста,
     * и трек оставался немым до конца сеанса.
     */
    private sealed interface LrcFetch {
        data class Found(val raw: String) : LrcFetch
        /** Сервер ответил, текста нет: 404 или пустой результат. */
        object Absent : LrcFetch
        /** До сервера не дошли или он сломался — выводов не делаем. */
        object Failed : LrcFetch
    }

    /** GET с User-Agent. 200 → текст LRC (synced приоритетнее plain). */
    private fun httpGetLrcLib(urlStr: String): LrcFetch {
        val conn = try {
            (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
        } catch (_: Exception) {
            return LrcFetch.Failed
        }
        return try {
            when (conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    extractLrcFromJson(body)?.takeIf { it.isNotBlank() }
                        ?.let { LrcFetch.Found(it) }
                        ?: LrcFetch.Absent
                }
                // 404 — «такого трека у нас нет»: это ответ, а не сбой.
                404 -> LrcFetch.Absent
                else -> LrcFetch.Failed
            }
        } catch (_: Exception) {
            LrcFetch.Failed
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** /api/get → объект; /api/search → массив. Возвращает syncedLyrics, иначе plainLyrics. */
    private fun extractLrcFromJson(body: String): String? {
        val trimmed = body.trim()
        return if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            var plain: String? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("instrumental", false)) continue
                val synced = jsonStr(o, "syncedLyrics")
                if (!synced.isNullOrBlank()) return synced           // первый со синхро — лучший
                if (plain == null) jsonStr(o, "plainLyrics")?.let { if (it.isNotBlank()) plain = it }
            }
            plain
        } else {
            val o = JSONObject(trimmed)
            if (o.optBoolean("instrumental", false)) return null
            jsonStr(o, "syncedLyrics")?.takeIf { it.isNotBlank() }
                ?: jsonStr(o, "plainLyrics")?.takeIf { it.isNotBlank() }
        }
    }

    private fun jsonStr(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key, "")

    private fun readAlbumTag(context: Context, uri: Uri?): String {
        if (uri == null) return ""
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
        } catch (_: Exception) {
            ""
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    private fun buildGetUrl(artist: String, title: String, album: String, durationSec: Int): String =
        "$LRCLIB_BASE/get?artist_name=${enc(artist)}&track_name=${enc(title)}" +
            "&album_name=${enc(album)}&duration=$durationSec"

    private fun buildSearchUrl(artist: String, title: String): String =
        "$LRCLIB_BASE/search?track_name=${enc(title)}&artist_name=${enc(artist)}"

    private fun cacheKey(artist: String, title: String, album: String, durationSec: Int): String {
        val raw = "$artist|$title|$album|$durationSec".lowercase()
        return "lrc_" + Integer.toHexString(raw.hashCode()) + "_$durationSec"
    }

    private fun lrcCacheFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, "lrclib").apply { if (!exists()) mkdirs() }
        return File(dir, "$key.lrc")
    }

    private fun readTextOrNull(file: File): String? = try {
        if (file.exists() && file.length() > 0) file.readText() else null
    } catch (_: Exception) {
        null
    }

    private fun writeTextSafe(file: File, text: String) {
        try { file.writeText(text) } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════
    //  МОЯ пословная разметка (Enhanced LRC) — ОТДЕЛЬНОЕ хранилище.
    //  Лежит в filesDir/mylyrics (НЕ в cache — не чистится), НЕ перезаписывает
    //  API-кэш. При загрузке проверяется ПЕРВОЙ и замещает любой источник API.
    // ═══════════════════════════════════════════════════════════

    private fun myKey(artist: String, title: String, durationSec: Int): String {
        val raw = "$artist|$title|$durationSec".lowercase()
        return "mine_" + Integer.toHexString(raw.hashCode()) + "_$durationSec"
    }

    private fun myLyricsFile(context: Context, key: String): File {
        val dir = File(context.filesDir, "mylyrics").apply { if (!exists()) mkdirs() }
        return File(dir, "$key.elrc")
    }

    /** Есть ли МОЯ пословная разметка для этого трека. */
    fun hasMyWordLyrics(context: Context, artist: String, title: String, durationMs: Long): Boolean {
        val sec = (durationMs / 1000L).toInt()
        val f = myLyricsFile(context, myKey(artist, title, sec))
        return f.exists() && f.length() > 0
    }

    /** Сохранить МОЮ пословную разметку (Enhanced LRC) как мой источник (локально). */
    fun saveMyWordLyrics(
        context: Context, artist: String, title: String, durationMs: Long,
        enhancedLrc: String, trackId: String?
    ) {
        if (enhancedLrc.isBlank()) return
        val sec = (durationMs / 1000L).toInt()
        writeTextSafe(myLyricsFile(context, myKey(artist, title, sec)), enhancedLrc)
        // Сразу в in-memory кэш по trackId → экран лирики подхватит без перезагрузки.
        if (!trackId.isNullOrBlank()) {
            val parsed = parseLyrics(enhancedLrc)
            if (parsed.lines.isNotEmpty()) {
                cacheLyrics(trackId, parsed.copy(title = title, artist = artist, source = "mine_word"))
            }
        }
    }

    /** Удалить МОЮ пословную разметку (вернёт трек к источникам API). */
    fun deleteMyWordLyrics(context: Context, artist: String, title: String, durationMs: Long, trackId: String?) {
        val sec = (durationMs / 1000L).toInt()
        runCatching { myLyricsFile(context, myKey(artist, title, sec)).delete() }
        if (!trackId.isNullOrBlank()) lyricsCache.remove(trackId)
    }

    /** Прочитать МОЮ пословную разметку, если есть. */
    private fun loadMyWordLyrics(context: Context, title: String, artist: String, durationMs: Long): Lyrics? {
        val sec = (durationMs / 1000L).toInt()
        val raw = readTextOrNull(myLyricsFile(context, myKey(artist, title, sec))) ?: return null
        val parsed = parseLyrics(raw)
        if (parsed.lines.isEmpty()) return null
        return parsed.copy(title = title, artist = artist, source = "mine_word")
    }

    // ═══════════════════════════════════════════════════════════
    //  Embedded & Parsing
    // ═══════════════════════════════════════════════════════════

    private fun tryExtractEmbedded(mmr: MediaMetadataRetriever): String? {
        return try {
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            null
        } catch (_: Exception) {
            null
        }
    }

    fun parseLyrics(raw: String): Lyrics {
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return Lyrics.EMPTY

        var title: String? = null
        var artist: String? = null
        var offsetMs = 0L
        val lyricLines = mutableListOf<LyricLine>()
        val endMarkers = mutableListOf<Long>()
        var hasSyncedLines = false

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("[ti:")) {
                title = trimmed.removeSurrounding("[ti:", "]").trim()
                continue
            }
            if (trimmed.startsWith("[ar:")) {
                artist = trimmed.removeSurrounding("[ar:", "]").trim()
                continue
            }
            if (trimmed.startsWith("[offset:")) {
                // Стандарт LRC: общий сдвиг таймкодов в мс. «+» = лирика раньше
                // (display = tag − offset). Раньше тег ВЫБРАСЫВАЛСЯ — у песен,
                // где автор синхры задал offset, вся лирика ехала на эту величину.
                offsetMs = trimmed.removeSurrounding("[offset:", "]").trim()
                    .replace("+", "").toLongOrNull() ?: 0L
                continue
            }
            if (trimmed.startsWith("[al:") || trimmed.startsWith("[by:") ||
                trimmed.startsWith("[re:") || trimmed.startsWith("[ve:")) {
                continue
            }

            val lrcPattern = Regex("""\[(\d{1,3}):(\d{2})(?:[.:])(\d{2,3})](.*)""")
            val matches = lrcPattern.findAll(trimmed).toList()

            if (matches.isNotEmpty()) {
                for (match in matches) {
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fraction = match.groupValues[3].let { f ->
                        val v = f.toLongOrNull() ?: 0L
                        if (f.length == 2) v * 10 else v
                    }
                    val rawContent = match.groupValues[4]              // НЕ триммим: нужны пробелы для слов
                    val timeMs = minutes * 60000 + seconds * 1000 + fraction

                    // Enhanced LRC: внутри строки есть пословные теги <mm:ss.xx>слово.
                    val words = parseWordTokens(rawContent)
                    if (words.isNotEmpty()) {
                        val joined = words.joinToString(" ") { it.text }
                        lyricLines.add(LyricLine(timeMs, joined, words))
                        hasSyncedLines = true
                    } else {
                        val text = rawContent.trim()
                        if (text.isNotBlank()) {
                            lyricLines.add(LyricLine(timeMs, text))
                            hasSyncedLines = true
                        } else {
                            // Таймкод без текста — это размеченный конец
                            // предыдущей строки. Раньше такие строки просто
                            // отбрасывались, и момент, когда голос смолк,
                            // приходилось угадывать по длине текста.
                            endMarkers.add(timeMs)
                        }
                    }
                }
            } else if (trimmed.isNotBlank() && !trimmed.startsWith("[")) {
                lyricLines.add(LyricLine(-1L, trimmed))
            }
        }

        if (hasSyncedLines) {
            lyricLines.sortBy { it.timeMs }

            // Раздаём размеченные концы строкам: строке достаётся первая метка,
            // которая стоит после её начала и не позже начала следующей.
            if (endMarkers.isNotEmpty()) {
                endMarkers.sort()
                var m = 0
                for (i in lyricLines.indices) {
                    val start = lyricLines[i].timeMs
                    val limit = lyricLines.getOrNull(i + 1)?.timeMs ?: Long.MAX_VALUE
                    while (m < endMarkers.size && endMarkers[m] <= start) m++
                    val mark = endMarkers.getOrNull(m) ?: break
                    if (mark <= limit) {
                        lyricLines[i] = lyricLines[i].copy(endMs = mark)
                        m++
                    }
                }
            }
        }

        // Применяем [offset:] ко всем таймкодам (строки + пословные теги).
        if (offsetMs != 0L && hasSyncedLines) {
            for (i in lyricLines.indices) {
                val l = lyricLines[i]
                if (l.timeMs < 0) continue
                lyricLines[i] = l.copy(
                    timeMs = (l.timeMs - offsetMs).coerceAtLeast(0L),
                    endMs = if (l.endMs > 0L) (l.endMs - offsetMs).coerceAtLeast(0L) else 0L,
                    words = l.words.map { w ->
                        w.copy(timeMs = (w.timeMs - offsetMs).coerceAtLeast(0L))
                    }
                )
            }
        }

        // Строго возрастающие таймкоды: дубликаты (копипаст-опечатки в LRC,
        // клампы offset у нуля) раздвигаем на +10мс. Порядок текста стабилен
        // (sortBy устойчив), а заливка не получает нулевых гэпов.
        if (hasSyncedLines) {
            var prev = -1L
            for (i in lyricLines.indices) {
                val l = lyricLines[i]
                if (l.timeMs < 0) continue
                if (l.timeMs <= prev) lyricLines[i] = l.copy(timeMs = prev + 10)
                prev = lyricLines[i].timeMs
            }
        }

        return Lyrics(lyricLines, hasSyncedLines, title, artist)
    }

    /** Пословные теги Enhanced LRC: <mm:ss.xx>слово <mm:ss.xx>слово … */
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{2})(?:[.:])(\d{2,3})>""")

    /** Разбирает содержимое строки на слова с таймкодами. Пусто → строка без word-тегов. */
    private fun parseWordTokens(content: String): List<LyricWord> {
        val tags = WORD_TAG.findAll(content).toList()
        if (tags.isEmpty()) return emptyList()
        val words = ArrayList<LyricWord>(tags.size)
        for ((i, m) in tags.withIndex()) {
            val minutes = m.groupValues[1].toLongOrNull() ?: 0L
            val seconds = m.groupValues[2].toLongOrNull() ?: 0L
            val fraction = m.groupValues[3].let { f ->
                val v = f.toLongOrNull() ?: 0L
                if (f.length == 2) v * 10 else v
            }
            val ts = minutes * 60000 + seconds * 1000 + fraction
            val from = m.range.last + 1
            val to = if (i + 1 < tags.size) tags[i + 1].range.first else content.length
            val w = if (from <= to) content.substring(from, to).trim() else ""
            if (w.isNotEmpty()) words.add(LyricWord(ts, w))
        }
        return words
    }

    fun findCurrentLine(lyrics: Lyrics, positionMs: Long): Int {
        if (!lyrics.isSynced || lyrics.lines.isEmpty()) return -1
        var current = -1
        for (i in lyrics.lines.indices) {
            if (lyrics.lines[i].timeMs <= positionMs) {
                current = i
            } else {
                break
            }
        }
        return current
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
