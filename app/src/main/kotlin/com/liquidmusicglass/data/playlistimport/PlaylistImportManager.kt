package com.liquidmusicglass.data.playlistimport

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton manager for background playlist import.
 *
 * Delegates actual work to [PlaylistImportService] (a Foreground Service)
 * so the import survives app minimization, screen lock, and tab switches.
 * The service posts native Android notifications with progress bars.
 *
 * The [importState] StateFlow is kept for UI observation but the primary
 * progress surface is now the system notification.
 */
object PlaylistImportManager {

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    /** Источник переносимого плейлиста (для UI: показать иконку/прогресс на
     *  нужной карточке сервиса). yandex/spotify/apple или null. */
    private val _importingSource = MutableStateFlow<String?>(null)
    val importingSource: StateFlow<String?> = _importingSource.asStateFlow()

    /** Сервис пушит сюда прогресс/финал — UI наблюдает в приложении, не только
     *  в системной шторке. Зовётся из PlaylistImportService. */
    fun publishState(state: ImportState) {
        _importState.value = state
        if (state is ImportState.Success || state is ImportState.Error) {
            _importingSource.value = null
        }
    }

    fun setImportingSource(source: String?) {
        _importingSource.value = source
    }

    /**
     * Start importing a playlist from URL.
     * Launches a Foreground Service — the import runs independently of the UI.
     */
    fun importPlaylist(url: String, context: Context, playlistName: String? = null) {
        appContext = context.applicationContext
        _importState.value = ImportState.Loading(0, 0, LoadingPhase.RESOLVING)
        PlaylistImportService.start(context, url, playlistName)
    }

    // Для cancelImport (P1, аудит): без контекста in-app отмена лишь сбрасывала
    // UI-стейт, а сервис продолжал молотить. При отклонённом POST_NOTIFICATIONS
    // FGS-нотификация невидима — отменить импорт было вообще нечем.
    @Volatile private var appContext: Context? = null

    /**
     * Cancel the current import.
     */
    fun cancelImport() {
        // Реально отменяем сервис (раньше — только сброс UI-стейта; P1, аудит).
        appContext?.let { runCatching { PlaylistImportService.cancel(it) } }
        _importState.value = ImportState.Idle
    }

    /**
     * Dismiss Success/Error state and return to Idle.
     */
    fun dismiss() {
        _importState.value = ImportState.Idle
    }

    /**
     * Get matched tracks as playable Track objects.
     */
    fun getMatchedTracks(): List<com.liquidmusicglass.engine.Track>? {
        val result = (_importState.value as? ImportState.Success)?.let {
            val playlist = com.liquidmusicglass.engine.PlaylistManager.getById(it.playlistId)
            playlist?.tracks?.map { pt ->
                com.liquidmusicglass.engine.Track(
                    id = pt.id,
                    title = pt.title,
                    artist = pt.artist,
                    albumName = "",
                    uri = android.net.Uri.parse("https://byicloud.online/track/${pt.id}"),
                    durationMs = pt.durationMs,
                    albumId = pt.id.hashCode().toLong(),
                    coverUrl = pt.coverUrl
                )
            }
        }
        return result
    }

    /**
     * Get the ID of the locally saved playlist.
     */
    fun getSavedPlaylistId(): String? {
        return (_importState.value as? ImportState.Success)?.playlistId
    }
}
