package com.liquidmusicglass.data.local

import android.content.Context
import android.util.Log
import com.liquidmusicglass.api.icm.IcmApiException
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.wave.IcmWaveBatchResponse
import com.liquidmusicglass.api.icm.wave.IcmWaveRepository
import com.liquidmusicglass.data.local.db.AppDatabase
import com.liquidmusicglass.data.local.db.CachedTrack
import com.liquidmusicglass.data.local.db.GenreCount
import com.liquidmusicglass.data.local.db.ListeningHistory
import com.liquidmusicglass.data.wave.WaveCandidateFilter
import com.liquidmusicglass.data.wave.WaveMode
import com.liquidmusicglass.data.wave.WaveSessionState
import com.liquidmusicglass.data.wave.toWaveCandidate
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
    private val personalWaveStateLock = Any()
    private var personalWaveState = WaveSessionState()
    private val modeWaveStateLock = Any()
    private val modeWaveStates = LinkedHashMap<String, WaveSessionState>()

    companion object {
        private const val TAG = "WaveRepository"
        /** Минимальное время прослушивания для записи в историю (30 сек) */
        const val MIN_LISTEN_TIME_MS = 30_000L
        /** Сколько дней назад смотрим историю для топ-жанров */
        const val GENRE_ANALYSIS_DAYS = 30
        /** Максимум треков в очереди волны */
        const val WAVE_QUEUE_SIZE = 20
        private const val PERSONAL_WAVE_DIVERSITY = 0.0
        private const val SESSION_EXPIRY_MARGIN_MS = 60_000L

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
            artistId = track.primaryArtistStatKey(),
            timestamp = System.currentTimeMillis()
        )
        markWaveStatePlayed(track)
        Log.d(TAG, "TrackStats: playCount++ for ${track.title}")
    }

    /**
     * Log a skipped track (user skipped early, < 30% played).
     */
    suspend fun logTrackSkipped(track: Track) = withContext(Dispatchers.IO) {
        playbackDao.incrementSkipCount(
            trackId = track.id,
            title = track.title,
            artistId = track.primaryArtistStatKey()
        )
        markWaveStateSkipped(track)
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
        if (seedTrackId == null) {
            val tracks = buildWaveQueue(
                count = (topUpCount + 1).coerceAtLeast(1),
                seedTrackId = null,
                exclude = exclude
            )
            val first = tracks.take(1)
            onFirst(first)
            val rest = tracks.drop(1)
            if (rest.isNotEmpty()) onTopUp(rest)
            return
        }

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

        // Stage 2: personal wave starts from the expanded batch endpoint.
        // The old one-by-one /library/wave/* path below stays as a fallback.
        if (effectiveSeedTrackId == null && count > 0) {
            val batchQueue = fetchPersonalWaveBatch(
                targetCount = count,
                excludeIds = excludeIds,
                recentIds = recentIds,
                resetSession = callerExcludeIsEmpty
            )
            if (batchQueue.isNotEmpty()) {
                Log.d(TAG, "Personal wave batch added ${batchQueue.size} tracks")
                queue.addAll(batchQueue)
                excludeIds.addAll(batchQueue.map { it.id })
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

    suspend fun buildWaveModeQueue(
        mode: WaveMode,
        count: Int = WAVE_QUEUE_SIZE,
        exclude: Collection<String> = emptyList()
    ): List<Track> = withContext(Dispatchers.IO) {
        when (mode) {
            is WaveMode.Personal -> buildWaveQueue(count = count, seedTrackId = null, exclude = exclude)
            is WaveMode.TrackStation -> buildWaveQueue(
                count = count,
                seedTrackId = mode.seedTrackId,
                exclude = exclude
            )
            is WaveMode.Session -> fetchPersonalWaveBatch(
                targetCount = count,
                excludeIds = exclude.toSet(),
                recentIds = getRecentTrackIdsSafely(50),
                resetSession = false
            )
            is WaveMode.Genre -> fetchGenreOrMoodWaveBatch(
                mode = mode,
                targetCount = count,
                excludeIds = exclude.toSet()
            )
            is WaveMode.Mood -> fetchGenreOrMoodWaveBatch(
                mode = mode,
                targetCount = count,
                excludeIds = exclude.toSet()
            )
        }
    }

    private suspend fun fetchPersonalWaveBatch(
        targetCount: Int,
        excludeIds: Set<String>,
        recentIds: List<String>,
        resetSession: Boolean
    ): List<Track> {
        val requestLimit = (targetCount * 2)
            .coerceAtLeast(targetCount + 5)
            .coerceIn(1, 50)
        val negativeArtistKeys = getLocallyRejectedArtistKeys()
        val apiExcludeArtistIds = negativeArtistKeys
            .filter { key -> key.all { it.isDigit() } }
        val initialState = preparePersonalWaveState(
            resetSession = resetSession,
            excludeIds = excludeIds,
            recentIds = recentIds,
            negativeArtistKeys = negativeArtistKeys
        )

        return try {
            var sessionState = ensurePersonalWaveSession(initialState) ?: return emptyList()
            var responseResult = requestPersonalSessionBatch(
                sessionState = sessionState,
                requestLimit = requestLimit,
                apiExcludeArtistIds = apiExcludeArtistIds
            )
            if (responseResult.isFailure && shouldRecreateWaveSession(responseResult.exceptionOrNull())) {
                Log.w(TAG, "Personal wave session expired/invalid, recreating")
                sessionState = ensurePersonalWaveSession(
                    state = sessionState.withSessionId(null),
                    forceNew = true
                ) ?: return emptyList()
                responseResult = requestPersonalSessionBatch(
                    sessionState = sessionState,
                    requestLimit = requestLimit,
                    apiExcludeArtistIds = apiExcludeArtistIds
                )
            }

            val response = responseResult.getOrNull()

            if (response?.status != "ok" || response.tracks.isEmpty()) {
                Log.w(TAG, "Personal wave batch status: ${response?.status ?: "null"}")
                return emptyList()
            }

            val statsByTrackId = mutableMapOf<String, WaveCandidateFilter.TrackStats>()
            for (track in response.tracks) {
                val stats = playbackDao.getTrackStat(track.id) ?: continue
                statsByTrackId[track.id] = WaveCandidateFilter.TrackStats(
                    playCount = stats.playCount,
                    skipCount = stats.skippedCount
                )
            }

            val filter = WaveCandidateFilter(
                WaveCandidateFilter.Policy(
                    // Expanded personal wave is Apple-only per API docs. Leaving
                    // sources open avoids dropping legacy responses that omit it.
                    allowedSources = emptySet(),
                    maxTracksPerArtistWindow = 3,
                    artistWindowSize = 12
                )
            )
            val filtered = filter.filter(
                candidates = response.tracks.map { it.toWaveCandidate() },
                state = sessionState.withSessionId(response.sessionId ?: sessionState.sessionId),
                statsByTrackId = statsByTrackId,
                limit = targetCount
            )
            updatePersonalWaveState(
                filtered.nextState.withSessionId(response.sessionId ?: sessionState.sessionId)
            )

            if (filtered.rejected.isNotEmpty()) {
                Log.d(
                    TAG,
                    "Personal wave batch filtered ${filtered.rejected.size}/${response.tracks.size}"
                )
            }

            val tracksById = response.tracks.associateBy { it.id }
            filtered.accepted.mapNotNull { candidate ->
                tracksById[candidate.id]?.toTrack()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Personal wave batch failed, falling back to sequential: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchGenreOrMoodWaveBatch(
        mode: WaveMode,
        targetCount: Int,
        excludeIds: Set<String>
    ): List<Track> {
        val key = mode.waveStateKey()
        val recentIds = getRecentTrackIdsSafely(50)
        val negativeArtistKeys = getLocallyRejectedArtistKeys()
        val apiExcludeArtistIds = negativeArtistKeys
            .filter { artistKey -> artistKey.all { it.isDigit() } }
        val state = prepareModeWaveState(
            key = key,
            mode = mode,
            excludeIds = excludeIds,
            recentIds = recentIds,
            negativeArtistKeys = negativeArtistKeys
        )

        val response = when (mode) {
            is WaveMode.Genre -> IcmWaveRepository.genreBatch(
                genre = mode.genre,
                limit = (targetCount * 2).coerceIn(1, 50),
                diversity = mode.diversity,
                source = mode.source,
                region = mode.region,
                excludeTrackIds = state.excludeIds.takeLast(200),
                excludeArtistIds = apiExcludeArtistIds.take(50),
                playedTrackIds = state.playedIds.takeLast(120)
            ).getOrNull()
            is WaveMode.Mood -> IcmWaveRepository.moodBatch(
                mood = mode.mood,
                limit = (targetCount * 2).coerceIn(1, 50),
                diversity = mode.diversity,
                source = mode.source,
                region = mode.region,
                excludeTrackIds = state.excludeIds.takeLast(200),
                excludeArtistIds = apiExcludeArtistIds.take(50),
                playedTrackIds = state.playedIds.takeLast(120)
            ).getOrNull()
            else -> null
        }

        if (response?.status != "ok" || response.tracks.isEmpty()) {
            Log.w(TAG, "Mode wave ${mode.waveStateKey()} status: ${response?.status ?: "null"}")
            return emptyList()
        }

        val statsByTrackId = mutableMapOf<String, WaveCandidateFilter.TrackStats>()
        for (track in response.tracks) {
            val stats = playbackDao.getTrackStat(track.id) ?: continue
            statsByTrackId[track.id] = WaveCandidateFilter.TrackStats(
                playCount = stats.playCount,
                skipCount = stats.skippedCount
            )
        }

        val filter = WaveCandidateFilter(
            WaveCandidateFilter.Policy(
                allowedSources = emptySet(),
                maxTracksPerArtistWindow = 3,
                artistWindowSize = 12
            )
        )
        val filtered = filter.filter(
            candidates = response.tracks.map { it.toWaveCandidate() },
            state = state,
            statsByTrackId = statsByTrackId,
            limit = targetCount
        )
        updateModeWaveState(key, filtered.nextState)

        if (filtered.rejected.isNotEmpty()) {
            Log.d(TAG, "Mode wave ${mode.waveStateKey()} filtered ${filtered.rejected.size}/${response.tracks.size}")
        }

        val tracksById = response.tracks.associateBy { it.id }
        return filtered.accepted.mapNotNull { candidate ->
            tracksById[candidate.id]?.toTrack()
        }
    }

    private fun preparePersonalWaveState(
        resetSession: Boolean,
        excludeIds: Set<String>,
        recentIds: List<String>,
        negativeArtistKeys: List<String>
    ): WaveSessionState {
        return synchronized(personalWaveStateLock) {
            val base = if (resetSession) WaveSessionState() else personalWaveState
            base.copy(
                mode = if (resetSession) WaveMode.Personal() else base.mode,
                sessionId = if (resetSession) null else base.sessionId,
                excludeIds = (base.excludeIds + excludeIds)
                    .mapNotNull { it.trim().takeIf { value -> value.isNotBlank() } }
                    .distinct()
                    .takeLast(WaveSessionState.MAX_EXCLUDE_IDS),
                playedIds = (base.playedIds + recentIds)
                    .mapNotNull { it.trim().takeIf { value -> value.isNotBlank() } }
                    .distinct()
                    .takeLast(WaveSessionState.MAX_PLAYED_IDS),
                negativeArtistKeys = negativeArtistKeys,
                updatedAtMs = System.currentTimeMillis()
            ).also { personalWaveState = it }
        }
    }

    private fun prepareModeWaveState(
        key: String,
        mode: WaveMode,
        excludeIds: Set<String>,
        recentIds: List<String>,
        negativeArtistKeys: List<String>
    ): WaveSessionState {
        return synchronized(modeWaveStateLock) {
            val base = modeWaveStates[key] ?: WaveSessionState(mode = mode)
            base.copy(
                mode = mode,
                excludeIds = (base.excludeIds + excludeIds)
                    .mapNotNull { it.trim().takeIf { value -> value.isNotBlank() } }
                    .distinct()
                    .takeLast(WaveSessionState.MAX_EXCLUDE_IDS),
                playedIds = (base.playedIds + recentIds)
                    .mapNotNull { it.trim().takeIf { value -> value.isNotBlank() } }
                    .distinct()
                    .takeLast(WaveSessionState.MAX_PLAYED_IDS),
                negativeArtistKeys = negativeArtistKeys,
                updatedAtMs = System.currentTimeMillis()
            ).also {
                modeWaveStates[key] = it
                trimModeWaveStates()
            }
        }
    }

    private suspend fun ensurePersonalWaveSession(
        state: WaveSessionState,
        forceNew: Boolean = false
    ): WaveSessionState? {
        if (!forceNew) {
            val activeSessionId = state.activeSessionId()
            if (activeSessionId != null) return state.withSessionId(activeSessionId)
        }

        val started = IcmWaveRepository.startSession(
            source = "apple",
            diversity = PERSONAL_WAVE_DIVERSITY
        ).getOrElse { error ->
            Log.w(TAG, "Failed to start personal wave session: ${error.message}")
            return null
        }

        val now = System.currentTimeMillis()
        val expiresAtMs = started.expiresIn?.let { now + it.coerceAtLeast(1) * 1000L }
        val nextState = state
            .withSessionId(started.sessionId)
            .withMode(
                WaveMode.Session(
                    sessionId = started.sessionId,
                    source = started.source ?: "apple",
                    region = started.region,
                    diversity = started.diversity ?: PERSONAL_WAVE_DIVERSITY,
                    expiresAtMs = expiresAtMs
                )
            )
        updatePersonalWaveState(nextState)
        Log.d(TAG, "Started personal wave session ${started.sessionId}")
        return nextState
    }

    private suspend fun requestPersonalSessionBatch(
        sessionState: WaveSessionState,
        requestLimit: Int,
        apiExcludeArtistIds: List<String>
    ): Result<IcmWaveBatchResponse> {
        val sessionId = sessionState.sessionId
            ?: return Result.failure(IllegalStateException("Personal wave session id is missing"))
        return IcmWaveRepository.nextSessionBatch(
            sessionId = sessionId,
            limit = requestLimit,
            diversity = PERSONAL_WAVE_DIVERSITY,
            excludeTrackIds = sessionState.excludeIds.takeLast(200),
            excludeArtistIds = apiExcludeArtistIds.take(50),
            playedTrackIds = sessionState.playedIds.takeLast(120)
        )
    }

    private fun updatePersonalWaveState(state: WaveSessionState) {
        synchronized(personalWaveStateLock) {
            personalWaveState = state
        }
    }

    private fun updateModeWaveState(key: String, state: WaveSessionState) {
        synchronized(modeWaveStateLock) {
            modeWaveStates[key] = state
            trimModeWaveStates()
        }
    }

    private fun trimModeWaveStates() {
        while (modeWaveStates.size > 16) {
            val oldest = modeWaveStates.minByOrNull { it.value.updatedAtMs }?.key ?: break
            modeWaveStates.remove(oldest)
        }
    }

    private fun WaveSessionState.activeSessionId(): String? {
        val sessionMode = mode as? WaveMode.Session
        val expiresAtMs = sessionMode?.expiresAtMs
        if (expiresAtMs != null && expiresAtMs <= System.currentTimeMillis() + SESSION_EXPIRY_MARGIN_MS) {
            return null
        }
        return sessionId?.takeIf { it.isNotBlank() }
    }

    private fun shouldRecreateWaveSession(error: Throwable?): Boolean {
        val apiError = error as? IcmApiException ?: return false
        val serverCode = apiError.errorCode.orEmpty()
        return apiError.code in setOf(400, 404, 410) ||
            serverCode.contains("session", ignoreCase = true)
    }

    private suspend fun getLocallyRejectedArtistKeys(limit: Int = 30): List<String> {
        return try {
            playbackDao.getMostSkipped(limit)
                .asSequence()
                .filter { it.skippedCount >= 2 && it.skippedCount > it.playCount }
                .mapNotNull { it.artistId?.trim()?.lowercase()?.takeIf { key -> key.isNotBlank() } }
                .distinct()
                .toList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read local skipped artists: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getRecentTrackIdsSafely(limit: Int): List<String> {
        return try {
            playbackDao.getRecentTrackIds(limit)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get recent track IDs: ${e.message}")
            emptyList()
        }
    }

    private fun markWaveStatePlayed(track: Track) {
        val candidate = track.toWaveCandidate()
        synchronized(personalWaveStateLock) {
            personalWaveState = personalWaveState.markPlayed(candidate)
        }
        synchronized(modeWaveStateLock) {
            val next = modeWaveStates.mapValues { it.value.markPlayed(candidate) }
            modeWaveStates.clear()
            modeWaveStates.putAll(next)
        }
    }

    private fun markWaveStateSkipped(track: Track) {
        val candidate = track.toWaveCandidate()
        synchronized(personalWaveStateLock) {
            personalWaveState = personalWaveState.markSkipped(candidate)
        }
        synchronized(modeWaveStateLock) {
            val next = modeWaveStates.mapValues { it.value.markSkipped(candidate) }
            modeWaveStates.clear()
            modeWaveStates.putAll(next)
        }
    }

    private fun WaveMode.waveStateKey(): String {
        return when (this) {
            is WaveMode.Personal -> "personal:${region.orEmpty()}"
            is WaveMode.TrackStation -> "track:$seedTrackId:${region.orEmpty()}"
            is WaveMode.Genre -> "genre:${genre.lowercase()}:$source:${region.orEmpty()}"
            is WaveMode.Mood -> "mood:${mood.lowercase()}:$source:${region.orEmpty()}"
            is WaveMode.Session -> "session:$sessionId"
        }
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

    private fun Track.primaryArtistStatKey(): String =
        artists.firstOrNull()?.id?.takeIf { it.isNotBlank() } ?: artist
}
