package com.liquidmusicglass.data.playlistimport

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin ViewModel wrapper around [PlaylistImportManager].
 *
 * Kept for backward compatibility with existing UI code that expects
 * a ViewModel. All actual state and coroutine logic lives in the
 * singleton [PlaylistImportManager] which survives screen lifecycle changes.
 */
class PlaylistImportViewModel : ViewModel() {

    val importState: StateFlow<ImportState> = PlaylistImportManager.importState

    fun importPlaylist(url: String, context: Context, playlistName: String? = null) {
        PlaylistImportManager.importPlaylist(url, context, playlistName)
    }

    fun cancelImport() {
        PlaylistImportManager.cancelImport()
    }

    fun dismiss() {
        PlaylistImportManager.dismiss()
    }

    fun getMatchedTracks(): List<com.liquidmusicglass.engine.Track>? {
        return PlaylistImportManager.getMatchedTracks()
    }

    fun getSavedPlaylistId(): String? {
        return PlaylistImportManager.getSavedPlaylistId()
    }
}
