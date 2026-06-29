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

    // In-memory lyrics cache — key: trackId
    private val lyricsCache = mutableMapOf<String, Lyrics>()

    fun getCachedLyrics(trackId: String?): Lyrics? {
        if (trackId.isNullOrBlank()) return null
        return lyricsCache[trackId]
    }

    fun cacheLyrics(trackId: String, lyrics: Lyrics) {
        lyricsCache[trackId] = lyrics
    }

    data class LyricLine(
        val timeMs: Long,    // -1 если нет таймстампа
        val text: String
    )

    data class Lyrics(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val title: String?,
        val artist: String?,
        val source: String = "none" // "embedded", "lrclib", "none"
    ) {
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
            val response = kotlinx.coroutines.withTimeout(12_000) {
                com.liquidmusicglass.api.icm.IcmRepository.getLyrics(trackId)
            }
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
     * СТРИМИНГ/ICM → как было (ICM API, потом embedded). LRCLIB для стриминга НЕ трогаем.
     */
    suspend fun loadLyrics(
        context: Context,
        uri: Uri?,
        title: String,
        artist: String,
        durationMs: Long,
        trackId: String? = null
    ): Lyrics {
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
        val raw = try {
            httpGetLrcLib(buildGetUrl(artist, title, album, durationSec))
                ?: httpGetLrcLib(buildSearchUrl(artist, title))
        } catch (_: Exception) {
            null
        }

        if (raw.isNullOrBlank()) {
            // нет текста / 404 / сеть недоступна → негатив в память (на сессию), на диск не пишем
            if (!trackId.isNullOrBlank()) cacheLyrics(trackId, Lyrics.EMPTY)
            return@withContext Lyrics.EMPTY
        }

        writeTextSafe(cacheFile, raw)             // на диск → офлайн в будущем
        finalizeLrc(raw, title, artist, trackId)
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

    /** GET с User-Agent. 200 → текст LRC (synced приоритетнее plain); иначе null. */
    private fun httpGetLrcLib(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            extractLrcFromJson(body)
        } catch (_: Exception) {
            null
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
        val lyricLines = mutableListOf<LyricLine>()
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
            if (trimmed.startsWith("[al:") || trimmed.startsWith("[by:") ||
                trimmed.startsWith("[offset:") || trimmed.startsWith("[re:") ||
                trimmed.startsWith("[ve:")) {
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
                    val text = match.groupValues[4].trim()
                    val timeMs = minutes * 60000 + seconds * 1000 + fraction

                    if (text.isNotBlank()) {
                        lyricLines.add(LyricLine(timeMs, text))
                        hasSyncedLines = true
                    }
                }
            } else if (trimmed.isNotBlank() && !trimmed.startsWith("[")) {
                lyricLines.add(LyricLine(-1L, trimmed))
            }
        }

        if (hasSyncedLines) {
            lyricLines.sortBy { it.timeMs }
        }

        return Lyrics(lyricLines, hasSyncedLines, title, artist)
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
