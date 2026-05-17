package com.liquidmusicglass.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Локальное хранилище для истории прослушиваний, кеша и настроек.
 * ICM Music не предоставляет историю — храним локально.
 */
object LocalStorage {

    private const val PREFS_NAME = "liquid_music_glass"
    private const val KEY_HISTORY = "play_history"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_DOWNLOADS = "downloads"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_SEARCH_CACHE = "search_cache"
    private const val KEY_TRACK_META_CACHE = "track_meta_cache"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ═══════════════════════════════════════════════════════════
    //  Play History
    // ═══════════════════════════════════════════════════════════

    /** Максимум записей в истории */
    private const val MAX_HISTORY = 200

    fun addToHistory(context: Context, entry: HistoryEntry) {
        val list = getHistory(context).toMutableList()
        list.removeAll { it.trackId == entry.trackId }
        list.add(0, entry)
        if (list.size > MAX_HISTORY) list.removeLast()
        saveHistory(context, list)
    }

    fun getHistory(context: Context): List<HistoryEntry> {
        val str = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            json.decodeFromString(str)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }

    private fun saveHistory(context: Context, list: List<HistoryEntry>) {
        prefs(context).edit().putString(KEY_HISTORY, json.encodeToString(list)).apply()
    }

    // ═══════════════════════════════════════════════════════════
    //  Favorites
    // ═══════════════════════════════════════════════════════════

    fun toggleFavorite(context: Context, trackId: String) {
        val set = getFavorites(context).toMutableSet()
        if (set.contains(trackId)) set.remove(trackId) else set.add(trackId)
        prefs(context).edit().putStringSet(KEY_FAVORITES, set).apply()
    }

    fun isFavorite(context: Context, trackId: String): Boolean {
        return getFavorites(context).contains(trackId)
    }

    fun getFavorites(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
    }

    // ═══════════════════════════════════════════════════════════
    //  Search Cache (query -> results, 5 min TTL)
    // ═══════════════════════════════════════════════════════════

    fun cacheSearch(context: Context, query: String, region: String, results: String) {
        val key = "${KEY_SEARCH_CACHE}_${query.lowercase()}_$region"
        val entry = CacheEntry(data = results, timestamp = System.currentTimeMillis())
        prefs(context).edit().putString(key, json.encodeToString(entry)).apply()
    }

    fun getCachedSearch(context: Context, query: String, region: String): String? {
        val key = "${KEY_SEARCH_CACHE}_${query.lowercase()}_$region"
        val str = prefs(context).getString(key, null) ?: return null
        return try {
            val entry = json.decodeFromString<CacheEntry>(str)
            val ageMs = System.currentTimeMillis() - entry.timestamp
            if (ageMs < 5 * 60 * 1000) entry.data else null // 5 min TTL
        } catch (_: Exception) {
            null
        }
    }

    fun clearSearchCache(context: Context) {
        val editor = prefs(context).edit()
        prefs(context).all.keys.filter { it.startsWith(KEY_SEARCH_CACHE) }.forEach {
            editor.remove(it)
        }
        editor.apply()
    }

    // ═══════════════════════════════════════════════════════════
    //  Track Meta Cache (trackId -> metadata, 7 days TTL)
    // ═══════════════════════════════════════════════════════════

    fun cacheTrackMeta(context: Context, trackId: String, meta: String) {
        val key = "${KEY_TRACK_META_CACHE}_$trackId"
        val entry = CacheEntry(data = meta, timestamp = System.currentTimeMillis())
        prefs(context).edit().putString(key, json.encodeToString(entry)).apply()
    }

    fun getCachedTrackMeta(context: Context, trackId: String): String? {
        val key = "${KEY_TRACK_META_CACHE}_$trackId"
        val str = prefs(context).getString(key, null) ?: return null
        return try {
            val entry = json.decodeFromString<CacheEntry>(str)
            val ageMs = System.currentTimeMillis() - entry.timestamp
            if (ageMs < 7 * 24 * 60 * 60 * 1000) entry.data else null // 7 days TTL
        } catch (_: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Downloads (offline tracks)
    // ═══════════════════════════════════════════════════════════

    fun addDownload(context: Context, trackId: String, filePath: String) {
        val map = getDownloads(context).toMutableMap()
        map[trackId] = filePath
        prefs(context).edit().putString(KEY_DOWNLOADS, json.encodeToString(map)).apply()
    }

    fun removeDownload(context: Context, trackId: String) {
        val map = getDownloads(context).toMutableMap()
        map.remove(trackId)
        prefs(context).edit().putString(KEY_DOWNLOADS, json.encodeToString(map)).apply()
    }

    fun getDownloads(context: Context): Map<String, String> {
        val str = prefs(context).getString(KEY_DOWNLOADS, null) ?: return emptyMap()
        return try {
            json.decodeFromString(str)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun isDownloaded(context: Context, trackId: String): Boolean {
        return getDownloads(context).containsKey(trackId)
    }

    // ═══════════════════════════════════════════════════════════
    //  Settings
    // ═══════════════════════════════════════════════════════════

    fun saveSettings(context: Context, settings: AppSettings) {
        prefs(context).edit().putString(KEY_SETTINGS, json.encodeToString(settings)).apply()
    }

    fun getSettings(context: Context): AppSettings {
        val str = prefs(context).getString(KEY_SETTINGS, null)
        return try {
            if (str != null) json.decodeFromString(str) else AppSettings()
        } catch (_: Exception) {
            AppSettings()
        }
    }
}

@Serializable
data class HistoryEntry(
    val trackId: String,
    val title: String,
    val artist: String,
    val coverUrl: String? = null,
    val playedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0
)

@Serializable
data class CacheEntry(
    val data: String,
    val timestamp: Long
)

@Serializable
data class AppSettings(
    val streamQuality: String = "256K",
    val region: String = "us",
    val hideExplicit: Boolean = false,
    val autoMixEnabled: Boolean = false,
    val themeMode: Int = 0 // 0=System, 1=Dark, 2=Light
)
