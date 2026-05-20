package com.liquidmusicglass.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Playlist Manager — создание, редактирование, удаление плейлистов.
 *
 * Хранит в SharedPreferences как JSON.
 */
object PlaylistManager {

    private lateinit var prefs: SharedPreferences
    private const val PREFS_NAME = "playlists"

    private fun safePrefs(): SharedPreferences? {
        return if (::prefs.isInitialized) prefs else null
    }

    data class Playlist(
        val id: String,
        val name: String,
        val trackIds: List<String>,
        val createdAt: Long,
        val coverTrackId: String? // первый трек для обложки
    )

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    /**
     * Создать новый плейлист.
     */
    fun create(name: String): Playlist {
        val id = "pl_${System.currentTimeMillis()}"
        val playlist = Playlist(id, name, emptyList(), System.currentTimeMillis(), null)
        val list = _playlists.value.toMutableList()
        list.add(0, playlist)
        _playlists.value = list
        saveToPrefs()
        return playlist
    }

    /**
     * Переименовать плейлист.
     */
    fun rename(playlistId: String, newName: String) {
        val list = _playlists.value.toMutableList()
        val idx = list.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(name = newName)
            _playlists.value = list
            saveToPrefs()
        }
    }

    /**
     * Удалить плейлист.
     */
    fun delete(playlistId: String) {
        _playlists.value = _playlists.value.filter { it.id != playlistId }
        saveToPrefs()
    }

    /**
     * Добавить трек в плейлист.
     */
    fun addTrack(playlistId: String, trackId: String) {
        val list = _playlists.value.toMutableList()
        val idx = list.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            val pl = list[idx]
            if (trackId !in pl.trackIds) {
                val newTracks = pl.trackIds + trackId
                list[idx] = pl.copy(
                    trackIds = newTracks,
                    coverTrackId = pl.coverTrackId ?: trackId
                )
                _playlists.value = list
                saveToPrefs()
            }
        }
    }

    /**
     * Убрать трек из плейлиста.
     */
    fun removeTrack(playlistId: String, trackId: String) {
        val list = _playlists.value.toMutableList()
        val idx = list.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            val pl = list[idx]
            val newTracks = pl.trackIds.filter { it != trackId }
            list[idx] = pl.copy(
                trackIds = newTracks,
                coverTrackId = if (pl.coverTrackId == trackId) newTracks.firstOrNull() else pl.coverTrackId
            )
            _playlists.value = list
            saveToPrefs()
        }
    }

    /**
     * Переместить трек в плейлисте (drag & drop).
     */
    fun moveTrack(playlistId: String, fromIndex: Int, toIndex: Int) {
        val list = _playlists.value.toMutableList()
        val idx = list.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            val pl = list[idx]
            val tracks = pl.trackIds.toMutableList()
            if (fromIndex in tracks.indices && toIndex in tracks.indices) {
                val item = tracks.removeAt(fromIndex)
                tracks.add(toIndex, item)
                list[idx] = pl.copy(trackIds = tracks)
                _playlists.value = list
                saveToPrefs()
            }
        }
    }

    /**
     * Получить плейлист по ID.
     */
    fun getById(playlistId: String): Playlist? {
        return _playlists.value.find { it.id == playlistId }
    }

    /**
     * Получить треки плейлиста как List<Track>.
     */
    fun getPlaylistTracks(playlistId: String, allTracks: List<Track>): List<Track> {
        val pl = getById(playlistId) ?: return emptyList()
        val trackMap = allTracks.associateBy { it.id }
        return pl.trackIds.mapNotNull { trackMap[it] }
    }

    // ── Persistence ──

    private fun saveToPrefs() {
        val p = safePrefs() ?: return
        val arr = JSONArray()
        _playlists.value.forEach { pl ->
            arr.put(JSONObject().apply {
                put("id", pl.id)
                put("name", pl.name)
                put("created", pl.createdAt)
                put("cover", pl.coverTrackId ?: -1L)
                val trackArr = JSONArray()
                pl.trackIds.forEach { trackArr.put(it) }
                put("tracks", trackArr)
            })
        }
        p.edit().putString("data", arr.toString()).apply()
    }

    private fun loadFromPrefs() {
        val p = safePrefs() ?: return
        try {
            val str = p.getString("data", null) ?: return
            val arr = JSONArray(str)
            val list = mutableListOf<Playlist>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val trackArr = obj.getJSONArray("tracks")
                val trackIds = mutableListOf<String>()
                for (j in 0 until trackArr.length()) {
                    trackIds.add(trackArr.getString(j))
                }
                val coverId = obj.optString("cover", "")
                list.add(Playlist(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    trackIds = trackIds,
                    createdAt = obj.optLong("created", 0L),
                    coverTrackId = if (coverId.isEmpty()) null else coverId
                ))
            }
            _playlists.value = list
        } catch (_: Exception) {}
    }
}
