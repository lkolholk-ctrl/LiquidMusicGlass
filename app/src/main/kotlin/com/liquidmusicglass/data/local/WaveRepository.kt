package com.liquidmusicglass.data.local

import android.content.Context
import android.util.Log
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.wave.IcmWaveRepository
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
    private val playbackDao = db.playbackHistoryDao()

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
            val onboarding = com.liquidmusicglass.engine.AppSettings.onboardingGenres.value
            if (onboarding.isNotEmpty()) {
                Log.d(TAG, "No history, using onboarding genres: $onboarding")
                onboarding
            } else {
                Log.d(TAG, "No history, using defaults")
                listOf("Electronic", "Electro House", "Techno")
            }
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
            genre = track.genre,
            source = source,
            durationPlayedMs = durationPlayedMs
        )

        dao.insertListeningRecord(record)
        Log.d(TAG, "Logged: ${track.title} | genre=${track.genre} | source=$source | ${durationPlayedMs}ms")
    }

    // ─── Track Stats & Playback History (for Wave de-duplication) ───

    /**
     * Log a completed play (track finished or played > 85%).
     * Writes to both TrackStats and PlaybackHistory.
     */
    suspend fun logTrackPlayed(track: Track) = withContext(Dispatchers.IO) {
        playbackDao.incrementPlayCount(
            trackId = track.id,
            title = track.title,
            artistId = track.artist, // Using artist name as artistId for now
            timestamp = System.currentTimeMillis()
        )
        Log.d(TAG, "TrackStats: playCount++ for ${track.title}")
    }

    /**
     * Log a skipped track (user skipped early, < 30% played).
     */
    suspend fun logTrackSkipped(track: Track) = withContext(Dispatchers.IO) {
        playbackDao.incrementSkipCount(
            trackId = track.id,
            title = track.title,
            artistId = track.artist
        )
        Log.d(TAG, "TrackStats: skipCount++ for ${track.title}")
    }

    /**
     * Get recent track IDs for wave exclude list.
     * Returns the last [limit] played track IDs ordered by most recent first.
     */
    suspend fun getRecentTrackIdsForExclude(limit: Int = 50): List<String> = withContext(Dispatchers.IO) {
        playbackDao.getRecentTrackIds(limit)
    }

    /**
     * Get play count for a specific track.
     */
    suspend fun getTrackPlayCount(trackId: String): Int = withContext(Dispatchers.IO) {
        playbackDao.getPlayCountForTrack(trackId)
    }

    /**
     * Get total skip count for a specific track.
     */
    suspend fun getTrackSkipCount(trackId: String): Int = withContext(Dispatchers.IO) {
        val stat = playbackDao.getTrackStat(trackId)
        stat?.skippedCount ?: 0
    }

    // ─── Wave Queue Building ───

    /**
     * Builds a "My Wave" queue using the ICM /library/wave/next endpoint.
     *
     * Uses random favorite seeds, applies ban filters and skipRatio filters, and heuristic genre tagging.
     */
    /**
     * Мгновенный старт волны: ПЕРВЫЙ трек отдаётся как только пришёл (музыка
     * стартует через ОДИН сетевой запрос), остальная пачка добирается следом
     * и доклеивается в очередь. Вместо прежнего «строим волну 5×RTT молча».
     */
    suspend fun buildWaveQueueFast(
        seedTrackId: String? = null,
        exclude: Collection<String> = emptyList(),
        topUpCount: Int = 5,
        onFirst: suspend (List<Track>) -> Unit,
        onTopUp: suspend (List<Track>) -> Unit
    ) {
        val first = buildWaveQueue(count = 1, seedTrackId = seedTrackId, exclude = exclude)
        onFirst(first)
        if (first.isEmpty()) return
        val rest = buildWaveQueue(
            count = topUpCount,
            seedTrackId = seedTrackId,
            exclude = exclude + first.map { it.id }
        )
        if (rest.isNotEmpty()) onTopUp(rest)
    }

    suspend fun buildWaveQueue(
        count: Int = 5,
        seedTrackId: String? = null,
        exclude: Collection<String> = emptyList()
    ): List<Track> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Building wave queue (seed=${seedTrackId ?: "personal"}, exclude=${exclude.size})")

        val queue = mutableListOf<Track>()
        val excludeIds = mutableSetOf<String>()
        val callerExcludeIsEmpty = exclude.isEmpty()
        val effectiveSeedTrackId = seedTrackId?.takeIf { IcmWaveRepository.isAppleSeedTrackId(it) }
        if (seedTrackId != null && effectiveSeedTrackId == null) {
            Log.w(TAG, "Track station seed is not an Apple numeric id ($seedTrackId), falling back to personal wave")
        }

        // Anti-repeat: caller-supplied IDs (текущая очередь + уже игравшие в этой волне).
        excludeIds.addAll(exclude)
        // seed-трек никогда не должен попасть в станцию повторно
        seedTrackId?.let { excludeIds.add(it) }

        // Get recent track IDs to exclude from wave
        val recentIds = try {
            playbackDao.getRecentTrackIds(50)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get recent track IDs: ${e.message}")
            emptyList()
        }
        // Personal wave can use global listening history as anti-repeat. Track station
        // is a narrow Apple station; excluding unrelated recent history can dry it up.
        if (effectiveSeedTrackId == null) {
            excludeIds.addAll(recentIds)
        }

        // Личная волна теперь умеет batch-дозаправку. Используем её только для
        // хвоста очереди (когда caller уже дал exclude): первый трек остаётся на
        // /library/wave/start, а station-by-track остаётся на seed_track_id.
        if (effectiveSeedTrackId == null && count > 1 && !callerExcludeIsEmpty) {
            val batchQueue = try {
                val response = IcmWaveRepository.nextBatch(
                    limit = count,
                    // Личная волна уже ранжируется сервером по истории/лайкам.
                    // Жёсткий diversity (>=0.33) режет артистов до 2 треков на
                    // пачку и на узком вкусе может вернуть всего 1-2 трека.
                    diversity = 0.0,
                    excludeTrackIds = excludeIds.toList().takeLast(80),
                    playedTrackIds = recentIds.take(50)
                ).getOrNull()

                if (response?.status == "ok" && response.tracks.isNotEmpty()) {
                    val tracks = mutableListOf<Track>()
                    for (waveTrack in response.tracks) {
                        if (tracks.size >= count) break
                        val trackId = waveTrack.id
                        if (trackId in excludeIds) {
                            Log.d(TAG, "Batch returned known track $trackId, skipping")
                            continue
                        }

                        val stats = playbackDao.getTrackStat(trackId)
                        if (stats != null) {
                            val total = stats.playCount + stats.skippedCount
                            if (total >= 2) {
                                val skipRatio = stats.skippedCount.toFloat() / total.toFloat()
                                if (skipRatio > 0.70f) {
                                    Log.d(TAG, "Batch skip-filter: excluding $trackId (skipRatio=$skipRatio)")
                                    excludeIds.add(trackId)
                                    continue
                                }
                            }
                        }

                        val track = waveTrack.toTrack()
                        tracks.add(track)
                        excludeIds.add(trackId)
                    }
                    tracks
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Personal wave batch failed, falling back to sequential: ${e.message}")
                emptyList()
            }

            if (batchQueue.isNotEmpty()) {
                Log.d(TAG, "Personal wave batch added ${batchQueue.size} tracks")
                queue.addAll(batchQueue)
            }
        }

        var attempts = 0
        val maxAttempts = count * 6 // Fail-safe boundary limit
        var nullStreak = 0          // подряд «нет ответа» → сеть лежит, не молотим
        var knownStreak = 0         // подряд УЖЕ ИЗВЕСТНЫХ треков → станция пересохла

        while (queue.size < count && attempts < maxAttempts) {
            attempts++
            try {
                // Check if we are already rate limited before making the call
                val lastHttpCode = IcmRepository.getLastHttpCode()
                val lastErrCode = IcmRepository.getLastErrorCode()
                if (lastHttpCode == 429 || lastErrCode == "rate_limited" || lastErrCode == "ip_temporarily_blocked") {
                    Log.w(TAG, "Already rate limited (429/blocked). Skipping wave building to prevent IP bans.")
                    break
                }

                // Add a small 150ms delay between consecutive requests to avoid burst rate limiting
                if (attempts > 1) {
                    kotlinx.coroutines.delay(150)
                }

                // effectiveSeedTrackId == null → персональная волна (подстраивается под юзера сервером:
                // лайки / completion / skip-streak). Иначе — станция вокруг seed-трека (мудовая плитка).
                val recentSkipsVal = com.liquidmusicglass.engine.PlayerController.consecutiveSkips

                // exclude капим ПОСЛЕДНИМИ 80 id (LinkedHashSet держит порядок
                // вставки → свежие в конце): на длинной сессии полный набор
                // (до 550 id) раздувал GET-запрос до километрового query-string
                // с риском 414. Сервер и так ведёт свою playback_history по
                // нашим wave/playback-событиям — древние эксклюды избыточны.
                val shouldUseWaveStart = effectiveSeedTrackId == null &&
                    attempts == 1 &&
                    queue.isEmpty() &&
                    callerExcludeIsEmpty

                var response = when {
                    effectiveSeedTrackId != null -> {
                        IcmWaveRepository.nextTrackStation(
                            seedTrackId = effectiveSeedTrackId,
                            exclude = excludeIds.toList().takeLast(80)
                        ).getOrNull()
                    }
                    shouldUseWaveStart -> IcmWaveRepository.startPersonalWave().getOrNull()
                    else -> null
                }

                if (response == null && effectiveSeedTrackId == null) {
                    response = IcmRepository.getWaveNext(
                        seedTrackId = null,
                        exclude = excludeIds.toList().takeLast(80).takeIf { it.isNotEmpty() },
                        recentSkips = recentSkipsVal,
                        genre = null
                    )
                }

                // Никакого «случайного лайка» как seed (это и была «херня»): по доке
                // пустая персональная волна = у сервера нет seed-артистов/лайков, и
                // правильная реакция — ОНБОРДИНГ (его триггерит ViewModel по пустому
                // результату), а не подмена рандомной станцией.

                if (response == null || response.status != "ok") {
                    val code = IcmRepository.getLastHttpCode()
                    val errorCode = IcmRepository.getLastErrorCode()
                    if (code == 429 || errorCode == "rate_limited" || errorCode == "ip_temporarily_blocked") {
                        Log.w(TAG, "Rate limit hit (429/blocked) during getWaveNext. Aborting queue building.")
                        break
                    }
                    // Пустая станция (нет кандидатов) — прекращаем, чтоб не крутить вхолостую.
                    if (response?.status == "empty") break
                    // 3 «нет ответа» подряд = сеть лежит (оффлайн/обрыв): выходим,
                    // иначе каждая дозаправка молотила бы до 60 холостых запросов.
                    if (response == null && ++nullStreak >= 3) {
                        Log.w(TAG, "No response 3x in a row — network looks down, aborting wave build.")
                        break
                    }
                    Log.w(TAG, "Wave response status: ${response?.status ?: "null"}")
                    continue
                }
                nullStreak = 0

                val waveTrack = response.track ?: run {
                    Log.w(TAG, "Wave track is null")
                    continue
                }

                val trackId = waveTrack.id

                // Клиентский анти-повтор: сервер МОЖЕТ вернуть трек из нашего
                // exclude (пул похожих у seed-станции выжат — сервер начинает
                // ходить по кругу). Раньше такой трек молча добавлялся в очередь
                // → «15 песен и поехало по кругу». Теперь: дубль пропускаем, а
                // 5 знакомых подряд = станция пересохла → обрываем сборку. Дальше
                // caller решает: ослабить exclude, дрейфовать seed или перейти в
                // личную волну.
                if (trackId in excludeIds) {
                    knownStreak++
                    Log.d(TAG, "Server returned known track $trackId (streak=$knownStreak)")
                    if (knownStreak >= 5) {
                        Log.w(TAG, "Station dried up: 5 known tracks in a row, aborting build")
                        break
                    }
                    continue
                }
                knownStreak = 0

                // Единственный локальный фильтр — ПЕРСОНАЛЬНЫЙ: если юзер стабильно скипает
                // этот трек (skipRatio > 70% за ≥2 показа), не предлагаем снова. Никаких
                // жанровых банов — волна подстраивается под то, что юзер реально слушает.
                val stats = playbackDao.getTrackStat(trackId)
                if (stats != null) {
                    val total = stats.playCount + stats.skippedCount
                    if (total >= 2) {
                        val skipRatio = stats.skippedCount.toFloat() / total.toFloat()
                        if (skipRatio > 0.70f) {
                            Log.d(TAG, "Skip-filter: excluding $trackId (skipRatio=$skipRatio)")
                            excludeIds.add(trackId)
                            continue
                        }
                    }
                }

                // Добавляем трек как есть (uri = byicloud.online/track/<id>). НЕ резолвим
                // стрим-URL заранее: при воспроизведении StreamingDataSource всё равно
                // резолвит свежий URL по id (resolveStreamUrlSync). Ранний getStreamUrl —
                // это лишний сетевой round-trip на каждый трек (удваивает время сборки
                // волны и «запекает» подписанный URL, который к моменту проигрывания
                // может уже протухнуть). Нестримящиеся треки авто-скипаются плеером.
                val track = waveTrack.toTrack()
                queue.add(track)
                excludeIds.add(trackId)
                Log.d(TAG, "Added wave track: ${track.title} by ${track.artist}")

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching wave track", e)
            }
        }

        Log.d(TAG, "Final wave queue: ${queue.size} tracks (attempts: $attempts)")
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
            genre = track.genre,
            streamUrl = track.uri.toString(),
            coverUrl = track.coverUrl,
            durationMs = track.durationMs,
            isFavorite = source == "FAVORITES" || track.genre == "Tech House" || track.genre == "House",
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
            coverUrl = coverUrl,
            genre = genre
        )
    }

    private suspend fun getTrackGenre(trackId: String): String? {
        dao.getTrackById(trackId)?.genre?.let { return it }
        return null
    }
}
