package com.liquidmusicglass.data.wave

import android.util.Log
import com.liquidmusicglass.engine.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Клиентская страховка от ВИДЕОКЛИПОВ в аудио-выдаче.
 *
 * Проблема: ICM неполно проставляет isClip — часть Apple music-video приходит в
 * /search и /wave как обычные треки, а /track на них НЕ отдаёт 404, поэтому и
 * DeadTrackRegistry их не ловит (один и тот же битый клип лез по 3-4 раза).
 * Чисто по данным ICM отличить их нельзя.
 *
 * Решение: айди Apple-каталога — числовые. Проверяем их через ПУБЛИЧНЫЙ iTunes
 * Lookup (без ключа, без авторизации): kind == "music-video" → это клип, режем.
 *
 * - Батч: один запрос `?id=1,2,3,…` на всю пачку (до 150 айди).
 * - Кеш на сессию: клиповость неизменна, повторно не спрашиваем.
 * - Fail-open: сеть упала / айди не резолвнулся в сторфронте → трек ОСТАВЛЯЕМ
 *   (лучше пропустить сомнительный, чем молча выкинуть живой трек).
 * - Не-числовые айди (VK/кастом) не трогаем — их в Apple-каталоге нет.
 */
object AppleCatalogFilter {
    private const val TAG = "AppleClipFilter"
    private const val LOOKUP = "https://itunes.apple.com/lookup"
    // US — самый широкий каталог → максимум резолва. kind (song/music-video) от
    // сторфронта не зависит, поэтому одного US достаточно, чтобы поймать клип.
    private const val STOREFRONT = "US"
    private const val CHUNK = 150

    // id -> true, если Apple знает его как music-video (клип). Кеш на сессию.
    private val isVideo = ConcurrentHashMap<String, Boolean>()

    private fun isAppleId(id: String): Boolean = id.isNotBlank() && id.all { it.isDigit() }

    /**
     * Возвращает список без Apple-видеоклипов. Не-Apple (не числовые) айди и всё,
     * что не подтверждено как клип, остаётся на месте.
     */
    suspend fun stripVideoClips(tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return tracks
        val ids = tracks.map { it.id }.filter { isAppleId(it) }.distinct()
        if (ids.isEmpty()) return tracks

        val unknown = ids.filterNot { isVideo.containsKey(it) }
        if (unknown.isNotEmpty()) resolve(unknown)

        val result = tracks.filterNot { isAppleId(it.id) && isVideo[it.id] == true }
        val removed = tracks.size - result.size
        if (removed > 0) {
            Log.d(TAG, "stripped $removed music-video clip(s) from ${tracks.size}")
        }
        return result
    }

    private suspend fun resolve(ids: List<String>) = withContext(Dispatchers.IO) {
        for (chunk in ids.chunked(CHUNK)) {
            try {
                // Айди — только цифры, запятая в query URL-безопасна: без кодирования.
                val csv = chunk.joinToString(",")
                val url = "$LOOKUP?id=$csv&country=$STOREFRONT"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
                conn.disconnect()
                if (body == null) continue

                val results = JSONObject(body).optJSONArray("results") ?: continue
                for (i in 0 until results.length()) {
                    val o = results.getJSONObject(i)
                    if (o.optString("kind") == "music-video") {
                        val id = o.optLong("trackId").takeIf { it != 0L }?.toString() ?: continue
                        isVideo[id] = true
                    }
                }
                // Всё, что резолвилось и НЕ music-video (или не вернулось вовсе в этом
                // сторфронте) — считаем аудио и кешируем, чтобы не спрашивать снова.
                for (id in chunk) isVideo.putIfAbsent(id, false)
            } catch (e: Exception) {
                // Fail-open: не кешируем (сеть может вернуться), выдачу не блокируем.
                Log.w(TAG, "iTunes lookup failed: ${e.message}")
            }
        }
    }
}
