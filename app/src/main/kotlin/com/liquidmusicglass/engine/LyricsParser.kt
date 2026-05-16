package com.liquidmusicglass.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics Parser — извлечение и разбор текстов песен.
 *
 * Источники (по приоритету):
 * 1. Embedded lyrics из тегов аудиофайлов
 * 2. LRCLIB.net — бесплатный онлайн API (синхронизированные тексты)
 * 3. Plain text fallback
 */
object LyricsParser {

    private const val LRCLIB_BASE = "https://lrclib.net/api"

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
     * Ищет lyrics онлайн через LRCLIB.
     * Бесплатный API, без ключа.
     */
    suspend fun fetchOnlineLyrics(
        title: String,
        artist: String,
        durationSec: Int = 0
    ): Lyrics = withContext(Dispatchers.IO) {
        try {
            // Попытка 1: точный поиск по get
            val exact = fetchLrclib(
                "$LRCLIB_BASE/get?" +
                "artist_name=${enc(artist)}" +
                "&track_name=${enc(title)}" +
                if (durationSec > 0) "&duration=$durationSec" else ""
            )
            if (exact != Lyrics.EMPTY) return@withContext exact

            // Попытка 2: поиск search
            val search = fetchLrclibSearch(title, artist)
            if (search != Lyrics.EMPTY) return@withContext search

            Lyrics.EMPTY
        } catch (_: Exception) {
            Lyrics.EMPTY
        }
    }

    /**
     * Полный поиск: сначала embedded, потом онлайн.
     */
    suspend fun loadLyrics(
        context: Context,
        uri: Uri?,
        title: String,
        artist: String,
        durationMs: Long
    ): Lyrics {
        // 1. Try embedded
        if (uri != null) {
            val embedded = extractLyrics(context, uri)
            if (embedded.lines.isNotEmpty()) return embedded
        }

        // 2. Try online
        val online = fetchOnlineLyrics(title, artist, (durationMs / 1000).toInt())
        if (online.lines.isNotEmpty()) return online

        return Lyrics.EMPTY
    }

    // ═══════════════════════════════════════════════════════════
    //  LRCLIB API
    // ═══════════════════════════════════════════════════════════

    private fun fetchLrclib(url: String): Lyrics {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "LiquidMusicGlass/1.0")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        if (conn.responseCode != 200) {
            conn.disconnect()
            return Lyrics.EMPTY
        }

        val response = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        return parseLrclibResponse(response)
    }

    private fun fetchLrclibSearch(title: String, artist: String): Lyrics {
        val url = "$LRCLIB_BASE/search?q=${enc("$artist $title")}"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "LiquidMusicGlass/1.0")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        if (conn.responseCode != 200) {
            conn.disconnect()
            return Lyrics.EMPTY
        }

        val response = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        // Search returns array — take first result
        try {
            val arr = org.json.JSONArray(response)
            if (arr.length() > 0) {
                return parseLrclibResponse(arr.getJSONObject(0).toString())
            }
        } catch (_: Exception) {}

        return Lyrics.EMPTY
    }

    private fun parseLrclibResponse(json: String): Lyrics {
        try {
            val obj = JSONObject(json)

            // Prefer synced lyrics
            val syncedLyrics = obj.optString("syncedLyrics", "")
            if (syncedLyrics.isNotBlank()) {
                val parsed = parseLyrics(syncedLyrics)
                if (parsed.lines.isNotEmpty()) {
                    return parsed.copy(
                        title = obj.optString("trackName", null),
                        artist = obj.optString("artistName", null),
                        source = "lrclib"
                    )
                }
            }

            // Fallback to plain lyrics
            val plainLyrics = obj.optString("plainLyrics", "")
            if (plainLyrics.isNotBlank()) {
                val lines = plainLyrics.lines()
                    .filter { it.isNotBlank() }
                    .map { LyricLine(-1L, it.trim()) }
                return Lyrics(
                    lines = lines,
                    isSynced = false,
                    title = obj.optString("trackName", null),
                    artist = obj.optString("artistName", null),
                    source = "lrclib"
                )
            }
        } catch (_: Exception) {}

        return Lyrics.EMPTY
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
