package com.liquidmusicglass.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmChart
import com.liquidmusicglass.api.icm.IcmHomeBlock
import com.liquidmusicglass.api.icm.IcmHomeResponse
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.data.local.HomeCacheManager
import com.liquidmusicglass.data.local.WaveRepository
import com.liquidmusicglass.data.wave.WaveMode
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ViewModel for the Home (Listen Now) screen.
 * 
 * Offline-first: loads from cache immediately, then refreshes from API in background.
 * Uses WaveRepository for "My Wave" analytics and queue building.
 */
class HomeViewModel : ViewModel() {

    private var homeLoadJob: Job? = null
    private var chartsLoadJob: Job? = null
    private var genresLoadJob: Job? = null
    private var waveLoadJob: Job? = null

    init {
        Log.d("HomeViewModel", "HomeViewModel created")
    }

    // ─── Home Content ───

    private val _homeContent = MutableStateFlow<IcmHomeResponse?>(null)
    val homeContent: StateFlow<IcmHomeResponse?> = _homeContent

    private val _charts = MutableStateFlow<List<IcmChart>>(emptyList())
    val charts: StateFlow<List<IcmChart>> = _charts

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isLoadingCharts = MutableStateFlow(false)
    val isLoadingCharts: StateFlow<Boolean> = _isLoadingCharts

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // ─── Wave State ───

    private val _waveTracks = MutableStateFlow<List<Track>>(emptyList())
    val waveTracks: StateFlow<List<Track>> = _waveTracks

    private val _isBuildingWave = MutableStateFlow(false)
    val isBuildingWave: StateFlow<Boolean> = _isBuildingWave

    // Пустая персональная волна → по доке нужен онбординг (выбор seed-артистов).
    private val _needsOnboarding = MutableStateFlow(false)
    val needsOnboarding: StateFlow<Boolean> = _needsOnboarding

    fun clearOnboardingFlag() { _needsOnboarding.value = false }

    // Персонализация волны работает только при залинкованном TG-аккаунте
    // (partner_user_id). Без него сервер отдаёт общую выдачу — это и есть «отсебятина».
    private val _needsLink = MutableStateFlow(false)
    val needsLink: StateFlow<Boolean> = _needsLink

    fun clearLinkFlag() { _needsLink.value = false }

    private fun isLinked(): Boolean =
        !IcmAuthRepository.partnerUserId.value.isNullOrBlank()

    private val _topGenres = MutableStateFlow<List<String>>(emptyList())
    val topGenres: StateFlow<List<String>> = _topGenres

    private fun activeLoadCount(): Int = listOf(homeLoadJob, chartsLoadJob, genresLoadJob, waveLoadJob).count { it?.isActive == true }

    fun cancelHomeLoad() {
        if (homeLoadJob?.isActive == true) Log.d("HomeViewModel", "homeLoadJob cancelled; active=${activeLoadCount()}")
        homeLoadJob?.cancel()
        homeLoadJob = null
        _isLoading.value = false
    }

    fun cancelChartsLoad() {
        if (chartsLoadJob?.isActive == true) Log.d("HomeViewModel", "chartsLoadJob cancelled; active=${activeLoadCount()}")
        chartsLoadJob?.cancel()
        chartsLoadJob = null
        _isLoadingCharts.value = false
    }

    /**
     * Load home content — offline first.
     */
    fun loadHomeContent(force: Boolean = false) {
        if (!force && homeLoadJob?.isActive == true) {
            Log.d("HomeViewModel", "loadHomeContent ignored: already active; active=${activeLoadCount()}")
            return
        }
        homeLoadJob?.cancel()
        _isLoading.value = true
        _error.value = null
        homeLoadJob = viewModelScope.launch {
            Log.d("HomeViewModel", "homeLoadJob started; active=${activeLoadCount()}")
            try {
                val cached = HomeCacheManager.load()
                if (cached != null && _homeContent.value == null) _homeContent.value = cached

                var lastException: Exception? = null
                repeat(3) { attempt ->
                    try {
                        val response = IcmRepository.loadHomeContent()
                        _homeContent.value = response
                        _error.value = null
                        HomeCacheManager.save(response)
                        // The home response already contains its charts block. Do not issue
                        // another chart search unless that block is genuinely absent.
                        if (response.blocks.none { it.type == "charts" }) loadCharts()
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastException = e
                        if (attempt < 2) delay(1000L * (attempt + 1))
                    }
                }
                _error.value = com.liquidmusicglass.api.icm.icmUserMessage(lastException)
                if (_homeContent.value == null) _homeContent.value = IcmHomeResponse(blocks = emptyList())
            } catch (e: CancellationException) {
                Log.d("HomeViewModel", "homeLoadJob cancelled")
                throw e
            } finally {
                if (homeLoadJob === coroutineContext[Job]) {
                    _isLoading.value = false
                    homeLoadJob = null
                    Log.d("HomeViewModel", "homeLoadJob finished; active=${activeLoadCount()}")
                }
            }
        }
    }

    /**
     * Load charts (top charts from Apple Music).
     */
    fun loadCharts() {
        if (chartsLoadJob?.isActive == true) return
        _isLoadingCharts.value = true
        chartsLoadJob = viewModelScope.launch {
            Log.d("HomeViewModel", "chartsLoadJob started; active=${activeLoadCount()}")
            try {
                val charts = IcmRepository.loadCharts()
                _charts.value = charts
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silently fail — charts are decorative
            } finally {
                if (chartsLoadJob === coroutineContext[Job]) {
                    _isLoadingCharts.value = false
                    chartsLoadJob = null
                    Log.d("HomeViewModel", "chartsLoadJob finished; active=${activeLoadCount()}")
                }
            }
        }
    }

    /**
     * Refresh home content (pull-to-refresh).
     * Clears the in-memory wave exclude set so recommendations can include
     * previously-played tracks again.
     */
    fun refresh() {
        _homeContent.value = null
        HomeCacheManager.clear()
        loadHomeContent(force = true)
    }

    /**
     * Get a specific block by type.
     */
    fun getBlockByType(type: String): IcmHomeBlock? {
        return _homeContent.value?.blocks?.find { it.type == type }
    }

    // ─── Wave (My Wave) ───

    /**
     * Loads the user's top genres from their playback history.
     */
    fun loadTopGenres(context: Context) {
        if (genresLoadJob?.isActive == true) return
        genresLoadJob = viewModelScope.launch {
            try {
                val repo = WaveRepository.getInstance(context)
                val genres = repo.getTopGenres(limit = 5)
                _topGenres.value = genres
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silently fail — genres are optional
            } finally {
                if (genresLoadJob === coroutineContext[Job]) genresLoadJob = null
            }
        }
    }

    /**
     * Builds the expanded personal wave. Fast start: a small one-shot batch
     * starts the music after a single request; EndlessPlaybackEngine tops the
     * queue up through the session that WaveRepository warms up in parallel.
     */
    fun buildWaveQueue(context: Context) {
        if (waveLoadJob?.isActive == true) return
        if (!isLinked()) { _needsLink.value = true; return }
        _isBuildingWave.value = true

        waveLoadJob = viewModelScope.launch {
            try {
                val repo = WaveRepository.getInstance(context)
                val tracks = repo.startPersonalWave()
                com.liquidmusicglass.api.icm.IcmApiFileLogger.log(
                    "D", "Wave", "buildWaveQueue: startPersonalWave -> ${tracks.size} tracks"
                )

                if (tracks.isNotEmpty()) {
                    _waveTracks.value = tracks
                    PlayerController.playFromList(
                        context = context,
                        tracks = tracks,
                        startIndex = 0,
                        autoRefillType = "WAVE"
                    )
                    _isBuildingWave.value = false
                    PlayerController.ensureWaveRefill()
                } else {
                    // Персональная волна ICM пуста НА СЕРВЕРЕ: он Apple-only и у
                    // аккаунта нет apple-сидов (лайки Y/кастомные), поэтому на все
                    // /wave/* приходит status:"empty", tracks:[]. Чтобы «Моя волна»
                    // не молчала и не откатывалась — падаем в бесконечную ЖАНРОВУЮ
                    // волну по топ-жанрам юзера: /wave/genre/{genre} это Apple-каталог,
                    // не зависит от персональных сидов. GENRE-дозаправка (см.
                    // EndlessPlaybackEngine) держит её бесконечной.
                    val fallback = buildGenreFallbackQueue(repo)
                    if (fallback != null) {
                        val (genres, genreTracks) = fallback
                        com.liquidmusicglass.api.icm.IcmApiFileLogger.log(
                            "D", "Wave",
                            "buildWaveQueue: personal empty -> search genre fallback $genres -> ${genreTracks.size} tracks"
                        )
                        _waveTracks.value = genreTracks
                        // autoRefillType=SEARCH + seedPool=жанры → EndlessPlaybackEngine
                        // дозаправляет волну поиском по этим жанрам (см. SEARCH-ветку),
                        // минуя висящий /wave/genre. Волна остаётся бесконечной.
                        PlayerController.playFromList(
                            context = context,
                            tracks = genreTracks,
                            startIndex = 0,
                            autoRefillType = "SEARCH",
                            autoRefillId = genres.firstOrNull(),
                            autoRefillName = genres.firstOrNull(),
                            seedPool = genres
                        )
                        _isBuildingWave.value = false
                        PlayerController.ensureWaveRefill()
                    } else {
                        // Даже жанровая волна пуста → по доке нужен онбординг (выбор
                        // seed-артистов). Если он ещё не пройден — показываем выбор.
                        val onboarded = try {
                            IcmRepository.getWaveOnboarding()?.completed ?: false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        if (!onboarded) _needsOnboarding.value = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _error.value = "Failed to build wave"
            } finally {
                if (waveLoadJob === coroutineContext[Job]) {
                    _isBuildingWave.value = false
                    waveLoadJob = null
                }
            }
        }
    }

    /**
     * Fallback для пустой персональной волны. Берёт топ-жанры юзера и набирает
     * очередь ПОИСКОМ (buildGenreSearchQueue), а не через /wave/genre — тот
     * висит на сервере (12с таймаут, треков нет). Возвращает список жанров (для
     * SEARCH-дозаправки через seedPool) и стартовую пачку. getTopGenres сам
     * отдаёт дефолты (Electronic/Techno…), если истории нет — так что волна
     * заведётся даже у нового юзера.
     */
    private suspend fun buildGenreFallbackQueue(
        repo: WaveRepository
    ): Pair<List<String>, List<Track>>? {
        val genres = try {
            repo.getTopGenres(limit = 5)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (genres.isEmpty()) return null
        val tracks = try {
            repo.buildGenreSearchQueue(
                genres = genres,
                count = WaveRepository.WAVE_QUEUE_SIZE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        return if (tracks.isNotEmpty()) genres to tracks else null
    }

    /**
     * Builds an expanded mood wave through /wave/mood/{mood}.
     */
    fun buildMoodWave(context: Context, query: String, name: String? = null) {
        if (waveLoadJob?.isActive == true) return
        if (!isLinked()) { _needsLink.value = true; return }
        _isBuildingWave.value = true

        waveLoadJob = viewModelScope.launch {
            try {
                val repo = WaveRepository.getInstance(context)
                // Стартуем с маленькой пачки (1 быстрый запрос) — до полного
                // буфера очередь добивает EndlessPlaybackEngine.
                val tracks = repo.buildWaveModeQueue(
                    mode = WaveMode.Mood(
                        mood = query,
                        displayName = name,
                        source = "apple",
                        diversity = 0.5
                    ),
                    count = WaveRepository.FAST_START_COUNT
                )
                if (tracks.isNotEmpty()) {
                    _waveTracks.value = tracks
                    PlayerController.playFromList(
                        context = context,
                        tracks = tracks,
                        startIndex = 0,
                        autoRefillType = "MOOD",
                        autoRefillId = query,
                        autoRefillName = name
                    )
                    _isBuildingWave.value = false
                    PlayerController.ensureWaveRefill()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _error.value = "Failed to build wave"
            } finally {
                if (waveLoadJob === coroutineContext[Job]) {
                    _isBuildingWave.value = false
                    waveLoadJob = null
                }
            }
        }
    }

    /**
     * Stops the "My Wave" playback.
     */
    fun stopWave() {
        _waveTracks.value = emptyList()
    }

    /**
     * Check if user has premium subscription.
     */
    val isPremium: Boolean
        get() = IcmAuthRepository.isPremium.value

    override fun onCleared() {
        Log.d("HomeViewModel", "HomeViewModel onCleared")
        super.onCleared()
    }
}
