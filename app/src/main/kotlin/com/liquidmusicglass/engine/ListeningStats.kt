package com.liquidmusicglass.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Listening Stats — сбор и хранение статистики прослушиваний.
 *
 * Хранит:
 * - Количество прослушиваний каждого трека
 * - Суммарное время прослушивания (мс)
 * - Историю прослушиваний с timestamps
 * - Статистику по дням
 */
object ListeningStats {

    private lateinit var prefs: SharedPreferences
    private const val PREFS_NAME = "listening_stats"

    // ── State ──
    private val _totalPlayCount = MutableStateFlow(0)
    val totalPlayCount: StateFlow<Int> = _totalPlayCount

    private val _totalListenTimeMs = MutableStateFlow(0L)
    val totalListenTimeMs: StateFlow<Long> = _totalListenTimeMs

    private val _topTracks = MutableStateFlow<List<TrackStat>>(emptyList())
    val topTracks: StateFlow<List<TrackStat>> = _topTracks

    private val _topArtists = MutableStateFlow<List<ArtistStat>>(emptyList())
    val topArtists: StateFlow<List<ArtistStat>> = _topArtists

    private val _dailyStats = MutableStateFlow<List<DayStat>>(emptyList())
    val dailyStats: StateFlow<List<DayStat>> = _dailyStats

    // ── Data classes ──
    data class TrackStat(
        val trackId: Long,
        val title: String,
        val artist: String,
        val playCount: Int,
        val totalTimeMs: Long
    )

    data class ArtistStat(
        val artist: String,
        val playCount: Int,
        val trackCount: Int,
        val totalTimeMs: Long
    )

    data class DayStat(
        val date: String, // "2026-03-19"
        val playCount: Int,
        val listenTimeMs: Long
    )

    // ── Track play data (in-memory map backed by SharedPrefs) ──
    // Key: trackId → JSON: {title, artist, playCount, totalTimeMs}
    private val trackStatsMap = mutableMapOf<Long, TrackPlayData>()

    private data class TrackPlayData(
        val title: String,
        val artist: String,
        var playCount: Int,
        var totalTimeMs: Long
    )

    // Daily stats: "2026-03-19" → {playCount, listenTimeMs}
    private val dailyStatsMap = mutableMapOf<String, DayPlayData>()

    private data class DayPlayData(
        var playCount: Int,
        var listenTimeMs: Long
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
        refreshFlows()
    }

    /**
     * Записать прослушивание трека.
     * Вызывать когда трек проигран >= 30 секунд.
     */
    fun recordPlay(trackId: Long, title: String, artist: String, durationMs: Long) {
        val data = trackStatsMap.getOrPut(trackId) {
            TrackPlayData(title, artist, 0, 0L)
        }
        data.playCount++
        data.totalTimeMs += durationMs

        // Daily
        val today = todayKey()
        val day = dailyStatsMap.getOrPut(today) { DayPlayData(0, 0L) }
        day.playCount++
        day.listenTimeMs += durationMs

        saveToPrefs()
        refreshFlows()
    }

    /**
     * Добавить время прослушивания (для незавершённых треков).
     */
    fun addListenTime(trackId: Long, title: String, artist: String, timeMs: Long) {
        if (timeMs <= 0) return
        val data = trackStatsMap.getOrPut(trackId) {
            TrackPlayData(title, artist, 0, 0L)
        }
        data.totalTimeMs += timeMs

        val today = todayKey()
        val day = dailyStatsMap.getOrPut(today) { DayPlayData(0, 0L) }
        day.listenTimeMs += timeMs

        saveToPrefs()
        refreshFlows()
    }

    /**
     * Получить количество прослушиваний трека.
     */
    fun getPlayCount(trackId: Long): Int {
        return trackStatsMap[trackId]?.playCount ?: 0
    }

    /**
     * Сброс статистики.
     */
    fun reset() {
        trackStatsMap.clear()
        dailyStatsMap.clear()
        prefs.edit().clear().apply()
        refreshFlows()
    }

    // ── Private ──

    private fun todayKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    private fun refreshFlows() {
        var totalPlays = 0
        var totalTime = 0L

        // Top tracks
        val tracks = trackStatsMap.map { (id, data) ->
            totalPlays += data.playCount
            totalTime += data.totalTimeMs
            TrackStat(id, data.title, data.artist, data.playCount, data.totalTimeMs)
        }.sortedByDescending { it.playCount }

        _totalPlayCount.value = totalPlays
        _totalListenTimeMs.value = totalTime
        _topTracks.value = tracks.take(50)

        // Top artists
        val artistMap = mutableMapOf<String, Triple<Int, Int, Long>>() // plays, tracks, time
        trackStatsMap.values.forEach { data ->
            val key = data.artist
            val (plays, tracks2, time) = artistMap.getOrDefault(key, Triple(0, 0, 0L))
            artistMap[key] = Triple(plays + data.playCount, tracks2 + 1, time + data.totalTimeMs)
        }
        _topArtists.value = artistMap.map { (artist, triple) ->
            ArtistStat(artist, triple.first, triple.second, triple.third)
        }.sortedByDescending { it.playCount }.take(30)

        // Daily stats (last 30 days)
        _dailyStats.value = dailyStatsMap.map { (date, data) ->
            DayStat(date, data.playCount, data.listenTimeMs)
        }.sortedByDescending { it.date }.take(30)
    }

    private fun saveToPrefs() {
        val tracksJson = JSONObject()
        trackStatsMap.forEach { (id, data) ->
            tracksJson.put(id.toString(), JSONObject().apply {
                put("t", data.title)
                put("a", data.artist)
                put("p", data.playCount)
                put("m", data.totalTimeMs)
            })
        }

        val daysJson = JSONObject()
        dailyStatsMap.forEach { (date, data) ->
            daysJson.put(date, JSONObject().apply {
                put("p", data.playCount)
                put("m", data.listenTimeMs)
            })
        }

        prefs.edit()
            .putString("tracks", tracksJson.toString())
            .putString("days", daysJson.toString())
            .apply()
    }

    private fun loadFromPrefs() {
        trackStatsMap.clear()
        dailyStatsMap.clear()

        try {
            val tracksStr = prefs.getString("tracks", null) ?: return
            val tracksJson = JSONObject(tracksStr)
            tracksJson.keys().forEach { key ->
                val obj = tracksJson.getJSONObject(key)
                trackStatsMap[key.toLong()] = TrackPlayData(
                    title = obj.optString("t", ""),
                    artist = obj.optString("a", ""),
                    playCount = obj.optInt("p", 0),
                    totalTimeMs = obj.optLong("m", 0L)
                )
            }
        } catch (_: Exception) {}

        try {
            val daysStr = prefs.getString("days", null) ?: return
            val daysJson = JSONObject(daysStr)
            daysJson.keys().forEach { key ->
                val obj = daysJson.getJSONObject(key)
                dailyStatsMap[key] = DayPlayData(
                    playCount = obj.optInt("p", 0),
                    listenTimeMs = obj.optLong("m", 0L)
                )
            }
        } catch (_: Exception) {}
    }

    // ── Formatting ──

    fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }

    fun formatTimeShort(ms: Long): String {
        val hours = ms / 3600000
        return if (hours > 0) "${hours}h" else "${ms / 60000}m"
    }
}
