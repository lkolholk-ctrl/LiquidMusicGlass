package com.liquidmusicglass.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.IcmSearchItem
import com.liquidmusicglass.api.icm.IcmSearchSource
import com.liquidmusicglass.api.icm.IcmWaveOnboardingArtist
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel for the Search screen.
 * Manages search query with debounce, search results, and genre categories.
 */
@OptIn(FlowPreview::class)
class SearchViewModel : ViewModel() {

    // ─── Search Query ───
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    // ─── Search Results ───
    private val _searchResults = MutableStateFlow<List<IcmSearchItem>>(emptyList())
    val searchResults: StateFlow<List<IcmSearchItem>> = _searchResults

    // ─── Categories (Popular Artists from wave onboarding) ───
    private val _categories = MutableStateFlow<List<IcmWaveOnboardingArtist>>(emptyList())
    val categories: StateFlow<List<IcmWaveOnboardingArtist>> = _categories

    // ─── Loading / Error ───
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // ─── Search Source ───
    private val _selectedSource = MutableStateFlow(IcmSearchSource.APPLE)
    val selectedSource: StateFlow<String> = _selectedSource

    // ─── Is Search Active (query not empty) ───
    val isSearchActive: Boolean
        get() = _query.value.isNotBlank()

    init {
        // Setup debounced search: 300ms after user stops typing
        _query
            .debounce(300)
            .filter { it.isNotBlank() }
            .distinctUntilChanged()
            .onEach { performSearch(it) }
            .launchIn(viewModelScope)
    }

    /**
     * Update search query. Triggers debounced search automatically.
     */
    fun setQuery(newQuery: String) {
        _query.value = newQuery
        if (newQuery.isBlank()) {
            _searchResults.value = emptyList()
            _error.value = null
        }
    }

    /**
     * Clear search query and results.
     */
    fun clearQuery() {
        _query.value = ""
        _searchResults.value = emptyList()
        _error.value = null
    }

    /**
     * Set search source (Apple, VK, All).
     */
    fun setSource(source: String) {
        _selectedSource.value = source
        // Re-trigger search if query is not empty
        if (_query.value.isNotBlank()) {
            performSearch(_query.value)
        }
    }

    /**
     * Load categories (popular artists) for the idle state.
     */
    fun loadCategories() {
        viewModelScope.launch {
            try {
                val artists = IcmRepository.getWavePopularArtists()
                _categories.value = artists
            } catch (e: Exception) {
                // Silently fail — categories are decorative
                _categories.value = emptyList()
            }
        }
    }

    /**
     * Perform search immediately (bypass debounce).
     */
    fun searchNow() {
        val q = _query.value
        if (q.isNotBlank()) {
            performSearch(q)
        }
    }

    private fun performSearch(q: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = IcmRepository.searchAll(q, source = _selectedSource.value)
                _searchResults.value = result?.items ?: emptyList()
                if (result == null) {
                    _error.value = IcmRepository.lastError.value ?: "Search failed"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Search error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Check if user has premium for max quality streaming.
     */
    val isPremium: Boolean
        get() = IcmAuthRepository.isPremium.value
}
