package com.liquidmusicglass.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Radio Browser API — бесплатный доступ к 30000+ радиостанций.
 * Без ключа, без лимитов.
 */
object RadioApi {

    private const val BASE = "https://de1.api.radio-browser.info/json"

    data class RadioStation(
        val id: String,
        val name: String,
        val streamUrl: String,
        val country: String,
        val tags: String,
        val codec: String,
        val bitrate: Int,
        val favicon: String,
        val votes: Int,
        val isOnline: Boolean
    )

    /**
     * Поиск по имени.
     */
    suspend fun searchByName(query: String, limit: Int = 30): List<RadioStation> =
        fetch("$BASE/stations/byname/${enc(query)}?limit=$limit&order=votes&reverse=true&hidebroken=true")

    /**
     * Поиск по жанру/тегу.
     */
    suspend fun searchByTag(tag: String, limit: Int = 30): List<RadioStation> =
        fetch("$BASE/stations/bytag/${enc(tag)}?limit=$limit&order=votes&reverse=true&hidebroken=true")

    /**
     * Станции по стране.
     */
    suspend fun searchByCountry(country: String, limit: Int = 30): List<RadioStation> =
        fetch("$BASE/stations/bycountry/${enc(country)}?limit=$limit&order=votes&reverse=true&hidebroken=true")

    /**
     * Топ станции (по голосам).
     */
    suspend fun topStations(limit: Int = 30): List<RadioStation> =
        fetch("$BASE/stations/topvote/$limit?hidebroken=true")

    /**
     * Популярные теги (жанры).
     */
    suspend fun topTags(limit: Int = 30): List<String> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$BASE/tags?order=stationcount&reverse=true&limit=$limit")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "LiquidMusicGlass/1.0")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val arr = JSONArray(response)
            val tags = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val name = arr.getJSONObject(i).optString("name", "")
                if (name.isNotBlank() && name.length < 25) tags.add(name)
            }
            tags
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetch(url: String): List<RadioStation> = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "LiquidMusicGlass/1.0")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            parseStations(response)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseStations(json: String): List<RadioStation> {
        val arr = JSONArray(json)
        val list = mutableListOf<RadioStation>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val url = obj.optString("url_resolved", obj.optString("url", ""))
            if (url.isBlank()) continue
            list.add(
                RadioStation(
                    id = obj.optString("stationuuid", ""),
                    name = obj.optString("name", "").trim(),
                    streamUrl = url,
                    country = obj.optString("country", ""),
                    tags = obj.optString("tags", ""),
                    codec = obj.optString("codec", ""),
                    bitrate = obj.optInt("bitrate", 0),
                    favicon = obj.optString("favicon", ""),
                    votes = obj.optInt("votes", 0),
                    isOnline = obj.optBoolean("lastcheckok", true)
                )
            )
        }
        return list
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
