package com.liquidmusicglass.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liquidmusicglass.data.local.db.FavoriteTrackEntity
import com.liquidmusicglass.data.local.db.LibraryRepository
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ViewModel for the Library screen.
 * Manages favorite tracks with offline-first architecture:
 * - Room DB is the source of truth (reactive Flow)
 * - Cloud sync happens in background
 * - Playback queue built from local favorites
 */
class LibraryViewModel(context: Context) : ViewModel() {

    private val repository = LibraryRepository.getInstance(context)

    private val _favorites = MutableStateFlow<List<FavoriteTrackEntity>>(emptyList())
    val favorites: StateFlow<List<FavoriteTrackEntity>> = _favorites.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    init {
        // Collect favorites from Room reactively
        viewModelScope.launch {
            repository.favoritesFlow.collectLatest { list ->
                _favorites.value = list
            }
        }

        // Collect favorite IDs reactively
        viewModelScope.launch {
            repository.favoriteIdsFlow.collectLatest { ids ->
                _favoriteIds.value = ids
            }
        }

        // Initial cloud sync
        syncWithCloud()
    }

    /**
     * Pull-to-refresh: sync with cloud and update local DB.
     */
    fun syncWithCloud() {
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            _errorMessage.value = null
            try {
                repository.syncWithCloud()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Sync failed"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * Play all favorites from the beginning.
     */
    fun playAll(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = repository.getAllFavoritesAsTracks()
            if (tracks.isEmpty()) return@launch

            PlayerController.playFromList(
                context = context,
                tracks = tracks,
                startIndex = 0,
                autoRefillType = "library",
                autoRefillId = "favorites",
                autoRefillName = "Favorites"
            )
        }
    }

    /**
     * Shuffle and play all favorites.
     */
    fun shuffleAndPlay(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = repository.getAllFavoritesAsTracks().shuffled()
            if (tracks.isEmpty()) return@launch

            PlayerController.playFromList(
                context = context,
                tracks = tracks,
                startIndex = 0,
                autoRefillType = "library",
                autoRefillId = "favorites",
                autoRefillName = "Favorites"
            )
        }
    }

    /**
     * Play a specific track from favorites, with the full favorites list as queue.
     */
    fun playTrack(context: Context, trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = repository.getAllFavoritesAsTracks()
            val startIndex = tracks.indexOfFirst { it.id == trackId }
            if (startIndex < 0) return@launch

            PlayerController.playFromList(
                context = context,
                tracks = tracks,
                startIndex = startIndex,
                autoRefillType = "library",
                autoRefillId = "favorites",
                autoRefillName = "Favorites"
            )
        }
    }

    /**
     * Toggle like/unlike for a track.
     */
    fun toggleFavorite(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleFavorite(track)
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _errorMessage.value = null
    }
}
