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
     * Полный поиск: сначала ICM API, потом embedded.
     */
    suspend fun loadLyrics(
        context: Context,
        uri: Uri?,
        title: String,
        artist: String,
        durationMs: Long,
        trackId: String? = null
    ): Lyrics {
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
