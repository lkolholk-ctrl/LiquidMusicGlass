package com.liquidmusicglass.data.local

import android.content.Context
import android.util.Log
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.data.local.db.AppDatabase
import com.liquidmusicglass.data.local.db.CachedTrack
import com.liquidmusicglass.data.local.db.GenreCount
import com.liquidmusicglass.data.local.db.ListeningHistory
import com.liquidmusicglass.engine.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Репозиторий для "Моей волны".
 *
 * Отвечает за:
 * - Сбор аналитики прослушиваний (история → топ-жанры)
 * - Фильтрацию треков по белому списку жанров
 * - Формирование очереди "Моей волны" из кеша + API
 */
class WaveRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.waveDao()

    companion object {
        private const val TAG = "WaveRepository"
        /** Минимальное время прослушивания для записи в историю (30 сек) */
        const val MIN_LISTEN_TIME_MS = 30_000L
        /** Сколько дней назад смотрим историю для топ-жанров */
        const val GENRE_ANALYSIS_DAYS = 30
        /** Максимум треков в очереди волны */
        const val WAVE_QUEUE_SIZE = 20

        @Volatile
        private var instance: WaveRepository? = null

        fun getInstance(context: Context): WaveRepository {
            return instance ?: synchronized(this) {
                instance ?: WaveRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    // ─── Analytics: Top Genres ───

    /**
     * Возвращает топ-жанры пользователя за последние [GENRE_ANALYSIS_DAYS] дней.
     * Если история пуста — возвращает дефолтные жанры.
     */
    suspend fun getTopGenres(limit: Int = 5): List<String> = withContext(Dispatchers.IO) {
        val sinceMs = System.currentTimeMillis() - (GENRE_ANALYSIS_DAYS * 24 * 60 * 60 * 1000L)
        val topGenres = dao.getTopGenres(sinceMs, limit)

        if (topGenres.isNotEmpty()) {
            Log.d(TAG, "Top genres: ${topGenres.map { "${it.genre}=${it.count}" }}")
            topGenres.map { it.genre }
        } else {
            Log.d(TAG, "No history, using defaults")
            listOf("Electronic", "Electro House", "Techno")
        }
    }

    /**
     * Поток топ-жанров (для наблюдения из UI).
     */
    fun getTopGenresFlow(limit: Int = 5): Flow<List<String>> {
        val sinceMs = System.currentTimeMillis() - (GENRE_ANALYSIS_DAYS * 24 * 60 * 60 * 1000L)
        // Room не поддерживает Flow для raw queries с GROUP BY, поэтому polling
        return kotlinx.coroutines.flow.flow {
            while (true) {
                val genres = dao.getTopGenres(sinceMs, limit).map { it.genre }
                emit(genres)
                kotlinx.coroutines.delay(30_000) // обновляем каждые 30 сек
            }
        }
    }

    // ─── Listening History ───

    /**
     * Записывает факт прослушивания трека.
     * Вызывать когда трек играл дольше [MIN_LISTEN_TIME_MS].
     */
    suspend fun logListening(
        track: Track,
        durationPlayedMs: Long,
        source: String
    ) = withContext(Dispatchers.IO) {
        if (durationPlayedMs < MIN_LISTEN_TIME_MS) {
            Log.d(TAG, "Skip logging: ${track.title} played only ${durationPlayedMs}ms")
            return@withContext
        }

        val record = ListeningHistory(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            genre = track.genre, // TODO: добавить genre в Track
            source = source,
            durationPlayedMs = durationPlayedMs
        )

        dao.insertListeningRecord(record)
        Log.d(TAG, "Logged: ${track.title} | genre=${track.genre} | source=$source | ${durationPlayedMs}ms")
    }

    // ─── Wave Queue Building ───

    /**
     * Строит очередь "Моей волны" строго по белому списку жанров.
     *
     * Алгоритм:
     * 1. Получаем топ-жанры из истории
     * 2. Ищем треки в кеше по этим жанрам
     * 3. Дополняем из API с фильтром по жанрам
     * 4. Отбраковываем треки с жанрами вне белого списка
     */
    suspend fun buildWaveQueue(): List<Track> = withContext(Dispatchers.IO) {
        val whiteList = getTopGenres()
        Log.d(TAG, "Building wave with whitelist: $whiteList")

        val queue = mutableListOf<Track>()

        // 1. Берём из кеша
        val cached = dao.getTracksByGenres(whiteList, WAVE_QUEUE_SIZE / 2)
        queue.addAll(cached.map { it.toEngineTrack() })
        Log.d(TAG, "From cache: ${cached.size} tracks")

        // 2. Дополняем из API по жанрам
        val needed = WAVE_QUEUE_SIZE - queue.size
        if (needed > 0) {
            try {
                // Для каждого топ-жанра ищем треки
                for (genre in whiteList) {
                    if (queue.size >= WAVE_QUEUE_SIZE) break
                    val genreTracks = IcmRepository.searchTracksByGenre(genre, limit = needed)
                    val filtered = genreTracks.filter { track ->
                        // Жёсткая фильтрация: только жанры из белого списка
                        val trackGenre = track.genre ?: getTrackGenre(track.id)
                        val allowed = trackGenre != null && whiteList.any { 
                            trackGenre.contains(it, ignoreCase = true) 
                        }
                        if (!allowed) {
                            Log.d(TAG, "REJECTED: ${track.title} | genre=$trackGenre | not in whitelist")
                        }
                        allowed && queue.none { it.id == track.id } // без дубликатов
                    }
                    queue.addAll(filtered)
                    Log.d(TAG, "From API [$genre]: ${filtered.size} tracks")
                }
            } catch (e: Exception) {
                Log.e(TAG, "API error building wave", e)
            }
        }

        // 3. Если всё равно мало — берём из кеша без фильтра (fallback)
        if (queue.size < WAVE_QUEUE_SIZE / 2) {
            val fallback = dao.getTracksByGenres(whiteList, WAVE_QUEUE_SIZE)
                .filter { cached -> queue.none { it.id == cached.id } }
            queue.addAll(fallback.map { it.toEngineTrack() })
        }

        Log.d(TAG, "Final wave queue: ${queue.size} tracks")
        queue
    }

    /**
     * Проверяет, допустим ли жанр трека для "Моей волны".
     */
    suspend fun isGenreAllowed(genre: String?): Boolean {
        if (genre.isNullOrBlank()) return false
        val whiteList = getTopGenres()
        return whiteList.any { genre.contains(it, ignoreCase = true) }
    }

    // ─── Cache Management ───

    suspend fun cacheTrack(track: Track, source: String) = withContext(Dispatchers.IO) {
        dao.insertTrack(CachedTrack(
            id = track.id,
            title = track.title,
            artist = track.artist,
            genre = track.genre, // TODO: добавить genre в Track
            streamUrl = track.uri.toString(),
            coverUrl = track.coverUrl,
            durationMs = track.durationMs,
            isFavorite = source == "FAVORITES",
            isDownloaded = source == "DOWNLOADED",
            source = source
        ))
    }

    suspend fun cleanupOldData() = withContext(Dispatchers.IO) {
        val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
        val ninetyDaysAgo = System.currentTimeMillis() - (90 * 24 * 60 * 60 * 1000L)

        dao.deleteOldHistory(thirtyDaysAgo)
        dao.deleteOldTracks(ninetyDaysAgo)
        Log.d(TAG, "Cleaned up old data")
    }

    // ─── Private ───

    private fun CachedTrack.toEngineTrack(): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            albumName = "",
            uri = android.net.Uri.parse(streamUrl ?: ""),
            durationMs = durationMs,
            albumId = -1L,
            coverUrl = coverUrl
        )
    }

    private suspend fun getTrackGenre(trackId: String): String? {
        // Сначала смотрим в кеше
        dao.getTrackById(trackId)?.genre?.let { return it }
        // TODO: запрос к API для получения жанра
        return null
    }
}
