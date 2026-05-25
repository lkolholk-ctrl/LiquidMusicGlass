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
 * Uses WaveRepository for "Моя волна" analytics and queue building.
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

            _error.value = lastException?.message ?: "Failed to load home content"
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
     */
    fun refresh() {
        _homeContent.value = null
        IcmRepository.clearWaveExclude()
        HomeCacheManager.clear()
        loadHomeContent()
    }

    /**
     * Get a specific block by type.
     */
    fun getBlockByType(type: String): IcmHomeBlock? {
        return _homeContent.value?.blocks?.find { it.type == type }
    }

    // ─── Wave (Моя Волна) ───

    /**
     * Загружает топ-жанры пользователя из истории прослушиваний.
     */
    fun loadTopGenres(context: Context) {
        viewModelScope.launch {
            try {
                val repo = WaveRepository(context)
                val genres = repo.getTopGenres(limit = 5)
                _topGenres.value = genres
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Failed to load top genres", e)
            }
        }
    }

    /**
     * Строит очередь "Моей волны" с фильтрацией по топ-жанрам.
     */
    fun buildWaveQueue(context: Context) {
        if (_isBuildingWave.value) return
        _isBuildingWave.value = true

        viewModelScope.launch {
            try {
                val repo = WaveRepository(context)
                val tracks = repo.buildWaveQueue()
                _waveTracks.value = tracks

                if (tracks.isNotEmpty()) {
                    // Запускаем воспроизведение
                    PlayerController.playFromList(
                        context = context,
                        tracks = tracks,
                        startIndex = 0,
                        autoRefillType = "WAVE"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Failed to build wave", e)
                _error.value = e.message
            } finally {
                _isBuildingWave.value = false
            }
        }
    }

    /**
     * Останавливает "Мою волну".
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
