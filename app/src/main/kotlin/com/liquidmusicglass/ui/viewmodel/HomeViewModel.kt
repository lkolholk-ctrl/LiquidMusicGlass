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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Home (Listen Now) screen.
 * 
 * Offline-first: loads from cache immediately, then refreshes from API in background.
 * Uses WaveRepository for "My Wave" analytics and queue building.
 */
class HomeViewModel : ViewModel() {

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

    /**
     * Load home content — offline first.
     */
    fun loadHomeContent() {
        if (_isLoading.value) return
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            // Step 1: Load from cache immediately
            val cached = HomeCacheManager.load()
            if (cached != null && _homeContent.value == null) {
                _homeContent.value = cached
            }

            // Step 2: Fetch fresh data from API
            var lastException: Exception? = null
            repeat(3) { attempt ->
                try {
                    val response = IcmRepository.loadHomeContent()
                    _homeContent.value = response
                    _error.value = null
                    HomeCacheManager.save(response)
                    _isLoading.value = false
                    return@launch
                } catch (e: Exception) {
                    lastException = e
                    if (attempt < 2) {
                        delay(1000L * (attempt + 1))
                    }
                }
            }

            _error.value = com.liquidmusicglass.api.icm.icmUserMessage(lastException)
            if (_homeContent.value == null) {
                _homeContent.value = IcmHomeResponse(blocks = emptyList())
            }
            _isLoading.value = false
        }

        loadCharts()
    }

    /**
     * Load charts (top charts from Apple Music).
     */
    fun loadCharts() {
        if (_isLoadingCharts.value) return
        _isLoadingCharts.value = true

        viewModelScope.launch {
            try {
                val charts = IcmRepository.loadCharts()
                _charts.value = charts
            } catch (_: Exception) {
                // Silently fail — charts are decorative
            } finally {
                _isLoadingCharts.value = false
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
        loadHomeContent()
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
        viewModelScope.launch {
            try {
                val repo = WaveRepository.getInstance(context)
                val genres = repo.getTopGenres(limit = 5)
                _topGenres.value = genres
            } catch (_: Exception) {
                // Silently fail — genres are optional
            }
        }
    }

    /**
     * Builds the expanded personal wave. Fast start: a small one-shot batch
     * starts the music after a single request; EndlessPlaybackEngine tops the
     * queue up through the session that WaveRepository warms up in parallel.
     */
    fun buildWaveQueue(context: Context) {
        if (_isBuildingWave.value) return
        if (!isLinked()) { _needsLink.value = true; return }
        _isBuildingWave.value = true

        viewModelScope.launch {
            try {
                val repo = WaveRepository.getInstance(context)
                val tracks = repo.startPersonalWave()

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
                    // Пусто → по доке: у сервера нет seed-артистов/лайков. Если онбординг
                    // ещё не пройден — показываем выбор артистов (персонализация стартует
                    // только после него). Иначе просто молчим.
                    val onboarded = try {
                        IcmRepository.getWaveOnboarding()?.completed ?: false
                    } catch (_: Exception) {
                        false
                    }
                    if (!onboarded) _needsOnboarding.value = true
                }
            } catch (_: Exception) {
                _error.value = "Failed to build wave"
            } finally {
                _isBuildingWave.value = false
            }
        }
    }

    /**
     * Builds an expanded mood wave through /wave/mood/{mood}.
     */
    fun buildMoodWave(context: Context, query: String, name: String? = null) {
        if (_isBuildingWave.value) return
        if (!isLinked()) { _needsLink.value = true; return }
        _isBuildingWave.value = true

        viewModelScope.launch {
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
            } catch (_: Exception) {
                _error.value = "Failed to build wave"
            } finally {
                _isBuildingWave.value = false
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
}
