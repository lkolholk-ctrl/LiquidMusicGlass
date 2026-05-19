package com.liquidmusicglass.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmHomeBlock
import com.liquidmusicglass.api.icm.IcmHomeResponse
import com.liquidmusicglass.api.icm.IcmRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Home (Listen Now) screen.
 * Loads dynamic content blocks from the backend:
 * - Banners (featured tracks)
 * - New Releases (albums)
 * - Charts (top tracks)
 * - Recommendations (wave tracks, if linked)
 */
class HomeViewModel : ViewModel() {

    private val _homeContent = MutableStateFlow<IcmHomeResponse?>(null)
    val homeContent: StateFlow<IcmHomeResponse?> = _homeContent

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * Load all home content blocks.
     * Called once when the screen is first displayed.
     */
    fun loadHomeContent() {
        if (_isLoading.value) return
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val response = IcmRepository.loadHomeContent()
                _homeContent.value = response
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load home content"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Refresh home content (pull-to-refresh).
     */
    fun refresh() {
        _homeContent.value = null
        loadHomeContent()
    }

    /**
     * Get a specific block by type.
     */
    fun getBlockByType(type: String): IcmHomeBlock? {
        return _homeContent.value?.blocks?.find { it.type == type }
    }

    /**
     * Check if user has premium subscription.
     */
    val isPremium: Boolean
        get() = IcmAuthRepository.isPremium.value
}
