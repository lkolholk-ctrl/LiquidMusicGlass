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
import com.liquidmusicglass.data.wave.negativeWaveCandidate
import com.liquidmusicglass.data.wave.toWaveCandidate
import com.liquidmusicglass.engine.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // Фоновая работа репозитория (прогрев wave-сессии). Синглтон — живёт с процессом.
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Сериализует создание серверной сессии: фоновый прогрев и первый рефилл
    // не должны открыть две сессии параллельно.
    private val personalSessionMutex = Mutex()

    companion object {
        private const val TAG = "WaveRepository"
        /** Минимальное время прослушивания для записи в историю (30 сек) */
        const val MIN_LISTEN_TIME_MS = 30_000L
        /** Сколько дней назад смотрим историю для топ-жанров */
        const val GENRE_ANALYSIS_DAYS = 30
        /** Пачка дозаправки волны. EndlessPlaybackEngine добивает буфер заранее. */
        const val WAVE_QUEUE_SIZE = 30
        /**
         * Быстрый старт: маленькая стартовая пачка, которую отдаёт ОДИН
         * one-shot запрос POST /wave/next — музыка начинается без ожидания
         * session/start + тяжёлого батча на 50 треков. Остальное добирает
         * EndlessPlaybackEngine через прогретую фоном сессию.
         */
        const val FAST_START_COUNT = 10
        private const val FAST_START_REQUEST_LIMIT = 15
        /**
         * По доке diversity ≥0.33 → максимум 2 трека артиста в пачке. Держим
         * серверную выдачу согласованной с клиентским WaveCandidateFilter
         * (3 на окно из 12), чтобы не выбрасывать кандидатов зря.
         */
        private const val PERSONAL_WAVE_DIVERSITY = 0.35
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

        // Персональная волна больше НЕ ходит в старый one-by-one
        // /library/wave/next. Новый source of truth — расширенная session wave:
        // /wave/session/start + /wave/session/{id}/next.
        if (effectiveSeedTrackId == null && count > 0) {
            return@withContext fetchPersonalWaveBatch(
                targetCount = count,
                excludeIds = excludeIds,
                recentIds = recentIds,
                resetSession = callerExcludeIsEmpty
            )
        }
        val stationSeedId = effectiveSeedTrackId ?: return@withContext emptyList()

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

                // exclude капим ПОСЛЕДНИМИ 80 id (LinkedHashSet держит порядок
                // вставки → свежие в конце): на длинной сессии полный набор
                // (до 550 id) раздувал GET-запрос до километрового query-string
                // с риском 414. Сервер и так ведёт свою playback_history по
                // нашим wave/playback-событиям — древние эксклюды избыточны.
                val response = IcmWaveRepository.nextTrackStation(
                    seedTrackId = stationSeedId,
                    exclude = excludeIds.toList().takeLast(80)
                ).getOrNull()

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
            is WaveMode.Personal -> fetchPersonalWaveBatch(
                targetCount = count,
                excludeIds = exclude.toSet(),
                recentIds = getRecentTrackIdsSafely(50),
                resetSession = exclude.isEmpty()
            )
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

            // Пул сессии выжат (сервер авто-исключает уже отданное → рано или
            // поздно "empty"). Волна ДОЛЖНА быть бесконечной: зацикливаем пул.
            if (response?.status == "empty" ||
                (response?.status == "ok" && response.tracks.isEmpty())
            ) {
                return loopPersonalWavePool(
                    requestLimit = requestLimit,
                    negativeArtistKeys = negativeArtistKeys,
                    apiExcludeArtistIds = apiExcludeArtistIds,
                    targetCount = targetCount,
                    fallbackState = sessionState
                )
            }

            if (response?.status != "ok" || response.tracks.isEmpty()) {
                Log.w(TAG, "Personal wave batch status: ${response?.status ?: "null"}")
                return emptyList()
            }

            acceptPersonalWaveTracks(
                tracks = response.tracks,
                sessionState = sessionState,
                responseSessionId = response.sessionId,
                targetCount = targetCount,
                label = "Personal wave batch"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Personal wave batch failed, falling back to sequential: ${e.message}")
            emptyList()
        }
    }

    /**
     * Пул личной волны кончился (status "empty") — зацикливаем, чтобы волна
     * была бесконечной:
     * 1) свежая сессия с МАЛЕНЬКИМ окном exclude (сброс накопленных исключений
     *    возвращает треки в оборот; клиентский фильтр не даёт повтора подряд);
     * 2) аварийно — one-shot POST /wave/next без сессии с минимальным exclude.
     */
    private suspend fun loopPersonalWavePool(
        requestLimit: Int,
        negativeArtistKeys: List<String>,
        apiExcludeArtistIds: List<String>,
        targetCount: Int,
        fallbackState: WaveSessionState
    ): List<Track> {
        Log.w(TAG, "Personal wave pool drained — looping with a fresh session")
        val recentWindow = getRecentTrackIdsSafely(30)
        val loopedBase = preparePersonalWaveState(
            resetSession = true,
            excludeIds = emptySet(),
            recentIds = recentWindow,
            negativeArtistKeys = negativeArtistKeys
        )
        val looped = ensurePersonalWaveSession(loopedBase.withSessionId(null), forceNew = true)
        if (looped != null) {
            val retry = requestPersonalSessionBatch(looped, requestLimit, apiExcludeArtistIds).getOrNull()
            if (retry?.status == "ok" && retry.tracks.isNotEmpty()) {
                return acceptPersonalWaveTracks(
                    retry.tracks, looped, retry.sessionId, targetCount, "Personal wave loop"
                )
            }
        }
        val oneShot = IcmWaveRepository.nextBatch(
            limit = requestLimit,
            diversity = PERSONAL_WAVE_DIVERSITY,
            excludeTrackIds = recentWindow.takeLast(30),
            excludeArtistIds = apiExcludeArtistIds.take(50)
        ).getOrNull()
        if (oneShot?.status == "ok" && oneShot.tracks.isNotEmpty()) {
            return acceptPersonalWaveTracks(
                oneShot.tracks, looped ?: fallbackState, oneShot.sessionId, targetCount, "Personal wave one-shot loop"
            )
        }
        return emptyList()
    }

    /**
     * Быстрый старт личной волны: музыка должна пойти с ОДНОГО сетевого
     * запроса. Порядок:
     * 1) one-shot POST /wave/next (без сессии) — маленькая пачка сразу;
     * 2) фолбэк: обычный session-путь (/wave/session/start + next);
     * 3) аварийный фолбэк: легаси /library/wave/start + /library/wave/next,
     *    чтобы волна жила даже при недоступном сторе расширенных сессий.
     * Серверная сессия для дальнейших дозаправок прогревается параллельно.
     */
    suspend fun startPersonalWave(count: Int = FAST_START_COUNT): List<Track> = withContext(Dispatchers.IO) {
        val recentIds = getRecentTrackIdsSafely(50)
        val negativeArtistKeys = getLocallyRejectedArtistKeys()
        val apiExcludeArtistIds = negativeArtistKeys.filter { key -> key.all { it.isDigit() } }
        val state = preparePersonalWaveState(
            resetSession = true,
            excludeIds = emptySet(),
            recentIds = recentIds,
            negativeArtistKeys = negativeArtistKeys
        )
        warmUpPersonalWaveSession()

        try {
            val oneShot = IcmWaveRepository.nextBatch(
                limit = FAST_START_REQUEST_LIMIT,
                diversity = PERSONAL_WAVE_DIVERSITY,
                excludeTrackIds = state.excludeIds.takeLast(80),
                excludeArtistIds = apiExcludeArtistIds.take(50),
                playedTrackIds = state.playedIds.takeLast(120)
            ).getOrNull()
            if (oneShot?.status == "ok" && oneShot.tracks.isNotEmpty()) {
                val accepted = acceptPersonalWaveTracks(
                    tracks = oneShot.tracks,
                    sessionState = state,
                    responseSessionId = null,
                    targetCount = count,
                    label = "Personal wave fast start"
                )
                if (accepted.isNotEmpty()) return@withContext accepted
            } else {
                Log.w(TAG, "Personal wave fast start status: ${oneShot?.status ?: "null"}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Personal wave fast start failed: ${e.message}")
        }

        // One-shot не дал треков → session-путь (state уже подготовлен выше).
        val viaSession = fetchPersonalWaveBatch(
            targetCount = count,
            excludeIds = emptySet(),
            recentIds = recentIds,
            resetSession = false
        )
        if (viaSession.isNotEmpty()) return@withContext viaSession

        fetchLegacyPersonalWave(count.coerceAtMost(5))
    }

    /**
     * Общий приём батча личной волны: стата скипов из Room,
     * WaveCandidateFilter, обновление in-memory состояния.
     */
    private suspend fun acceptPersonalWaveTracks(
        tracks: List<com.liquidmusicglass.api.icm.IcmWaveTrack>,
        sessionState: WaveSessionState,
        responseSessionId: String?,
        targetCount: Int,
        label: String
    ): List<Track> {
        val statsByTrackId = mutableMapOf<String, WaveCandidateFilter.TrackStats>()
        for (track in tracks) {
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
        val effectiveSessionId = responseSessionId ?: sessionState.sessionId
        val filtered = filter.filter(
            candidates = tracks.map { it.toWaveCandidate() },
            state = sessionState.withSessionId(effectiveSessionId),
            statsByTrackId = statsByTrackId,
            limit = targetCount
        )
        updatePersonalWaveState(filtered.nextState.withSessionId(effectiveSessionId))

        if (filtered.rejected.isNotEmpty()) {
            Log.d(TAG, "$label filtered ${filtered.rejected.size}/${tracks.size}")
        }

        val tracksById = tracks.associateBy { it.id }
        val result = filtered.accepted.mapNotNull { candidate ->
            tracksById[candidate.id]?.toTrack()
        }
        // DIAG (wave-diag build): видно в «Copy ICM logs». server=сколько отдал ICM,
        // filterAccepted=сколько прошло WaveCandidateFilter, mapped=сколько стало Track.
        com.liquidmusicglass.api.icm.IcmApiFileLogger.log(
            "D", "Wave",
            "$label: server=${tracks.size} filterAccepted=${filtered.accepted.size} mapped=${result.size}"
        )
        return result
    }

    /**
     * Аварийный путь личной волны: /library/wave/start + /library/wave/next
     * по одному треку. Используется, только когда расширенная волна недоступна
     * (например, 503 wave_session_store_unavailable) — музыка всё равно идёт.
     */
    private suspend fun fetchLegacyPersonalWave(count: Int): List<Track> {
        val tracks = mutableListOf<Track>()
        val exclude = mutableListOf<String>()
        try {
            val first = IcmWaveRepository.startWave().getOrNull()
            if (first?.status == "ok") {
                first.track?.let {
                    tracks += it.toTrack()
                    exclude += it.id
                }
            }
            while (tracks.size < count) {
                kotlinx.coroutines.delay(150)
                val next = IcmRepository.getWaveNext(
                    seedTrackId = null,
                    exclude = exclude.takeIf { it.isNotEmpty() }
                ) ?: break
                if (next.status != "ok") break
                val track = next.track ?: break
                tracks += track.toTrack()
                exclude += track.id
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy personal wave fallback failed: ${e.message}")
        }
        if (tracks.isNotEmpty()) {
            Log.w(TAG, "Personal wave served by legacy fallback: ${tracks.size} tracks")
            synchronized(personalWaveStateLock) {
                personalWaveState = personalWaveState.markQueued(
                    tracks.map { it.toWaveCandidate() }
                )
            }
        }
        return tracks
    }

    /**
     * Прогревает серверную wave-сессию фоном: старт волны её не ждёт, а
     * первая дозаправка получает готовый session_id без лишнего round-trip.
     */
    private fun warmUpPersonalWaveSession() {
        repoScope.launch {
            try {
                val state = synchronized(personalWaveStateLock) { personalWaveState }
                ensurePersonalWaveSession(state)
            } catch (e: Exception) {
                Log.w(TAG, "Personal wave session warm-up failed: ${e.message}")
            }
        }
    }

    /**
     * Мгновенный локальный отклик на «реже»/дизлайк: сервер учтёт сигнал со
     * своим decay, а клиентские состояния волны сразу перестают предлагать
     * этот трек/артиста (свежие батчи режутся WaveCandidateFilter).
     */
    fun noteNegativeFeedback(feedbackType: String, value: String) {
        val candidate = negativeWaveCandidate(feedbackType, value) ?: return
        synchronized(personalWaveStateLock) {
            personalWaveState = personalWaveState.markNegativeFeedback(candidate)
        }
        synchronized(modeWaveStateLock) {
            val next = modeWaveStates.mapValues { it.value.markNegativeFeedback(candidate) }
            modeWaveStates.clear()
            modeWaveStates.putAll(next)
        }
    }

    private suspend fun fetchGenreOrMoodWaveBatch(
        mode: WaveMode,
        targetCount: Int,
        excludeIds: Set<String>
    ): List<Track> {
        val term = when (mode) {
            is WaveMode.Genre -> mode.genre
            is WaveMode.Mood -> mode.mood
            else -> ""
        }.trim()
        if (term.length < 2) {
            Log.w(TAG, "Mode wave ${mode.waveStateKey()} skipped: invalid term")
            return emptyList()
        }

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

        val responseResult = when (mode) {
            is WaveMode.Genre -> IcmWaveRepository.genreBatch(
                genre = term,
                limit = (targetCount * 2).coerceIn(1, 50),
                diversity = mode.diversity,
                source = mode.source,
                region = mode.region,
                excludeTrackIds = state.excludeIds.takeLast(200),
                excludeArtistIds = apiExcludeArtistIds.take(50),
                playedTrackIds = state.playedIds.takeLast(120)
            )
            is WaveMode.Mood -> IcmWaveRepository.moodBatch(
                mood = term,
                limit = (targetCount * 2).coerceIn(1, 50),
                diversity = mode.diversity,
                source = mode.source,
                region = mode.region,
                excludeTrackIds = state.excludeIds.takeLast(200),
                excludeArtistIds = apiExcludeArtistIds.take(50),
                playedTrackIds = state.playedIds.takeLast(120)
            )
            else -> Result.failure(IllegalArgumentException("Unsupported wave mode: $mode"))
        }
        responseResult.exceptionOrNull()?.let { error ->
            Log.w(TAG, "Mode wave ${mode.waveStateKey()} failed: ${error.message}")
        }
        val response = responseResult.getOrNull()

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

        return personalSessionMutex.withLock {
            // Пока ждали лок, сессию мог открыть параллельный прогрев/рефилл.
            if (!forceNew) {
                val fresh = synchronized(personalWaveStateLock) { personalWaveState }
                val freshSessionId = fresh.activeSessionId()
                if (freshSessionId != null) {
                    return@withLock state
                        .withMode(fresh.mode)
                        .withSessionId(freshSessionId)
                }
            }

            val started = IcmWaveRepository.startSession(
                source = "apple",
                diversity = PERSONAL_WAVE_DIVERSITY
            ).getOrElse { error ->
                Log.w(TAG, "Failed to start personal wave session: ${error.message}")
                return@withLock null
            }

            val now = System.currentTimeMillis()
            val expiresAtMs = started.expiresIn?.let { now + it.coerceAtLeast(1) * 1000L }
            val sessionMode = WaveMode.Session(
                sessionId = started.sessionId,
                source = started.source ?: "apple",
                region = started.region,
                diversity = started.diversity ?: PERSONAL_WAVE_DIVERSITY,
                expiresAtMs = expiresAtMs
            )
            // Сессию подшиваем к АКТУАЛЬНОМУ состоянию: пока шёл session/start,
            // параллельный fast-start мог дописать exclude/played.
            val nextState = synchronized(personalWaveStateLock) {
                personalWaveState = personalWaveState
                    .withSessionId(started.sessionId)
                    .withMode(sessionMode)
                personalWaveState
            }
            Log.d(TAG, "Started personal wave session ${started.sessionId}")
            nextState
        }
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
            excludeTrackIds = sessionState.excludeIds.takeLast(80),
            excludeArtistIds = apiExcludeArtistIds.take(50),
            playedTrackIds = sessionState.playedIds.takeLast(120)
        )
    }

    private fun updatePersonalWaveState(state: WaveSessionState) {
        synchronized(personalWaveStateLock) {
            val current = personalWaveState
            // Не теряем сессию, прогретую параллельно: снапшот без session_id
            // не должен затирать свежесозданную сессию.
            personalWaveState = if (state.sessionId == null && current.sessionId != null) {
                state.withSessionId(current.sessionId).withMode(current.mode)
            } else {
                state
            }
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
        if (apiError.code == 503 || serverCode == "wave_session_store_unavailable") return false
        return apiError.code in setOf(404, 410) ||
            serverCode == "wave_session_not_found" ||
            serverCode == "wave_session_belongs_to_another_user"
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
