package com.liquidmusicglass.engine

import android.net.Uri

/**
 * Универсальная модель трека.
 * id: String — поддерживает и локальные треки (MediaStore Long as String),
 * и онлайн-треки из ICM Partner API (String IDs).
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val albumName: String,
    val uri: Uri,
    val durationMs: Long,
    val albumId: Long,
    /** URL обложки из ICM API (Apple Music covers). Приоритет над albumArtUri. */
    val coverUrl: String? = null,
    /** Список артистов трека (для фитов/коллабораций). Первый — основной. */
    val artists: List<com.liquidmusicglass.api.icm.IcmMiniArtist> = emptyList(),
    val isExplicit: Boolean = false,
    val isCustom: Boolean = false,
    /** Music source: "apple", "vk", "wave", etc. Used for stream quality selection. */
    val source: String? = null,
    /** Жанр трека для аналитики "Моей волны". */
    val genre: String? = null
) {
    /** Uri обложки альбома из MediaStore (для локальных треков). */
    val albumArtUri: Uri
        get() = Uri.parse("content://media/external/audio/albumart/$albumId")

    /** Является ли трек онлайн-треком, требующим разрешения стрим-URL. */
    val isOnlineTrack: Boolean
        get() = uri.toString().startsWith("https://byicloud.online")
            || uri.toString().startsWith("http://byicloud.online")
            || isYouTubeTrack

    /** Является ли треком из YouTube Music. */
    val isYouTubeTrack: Boolean
        get() = source == "youtube" || uri.toString().contains("googlevideo.com") || uri.toString().contains("youtube.com")

    /** URI для отображения обложки (coverUrl имеет приоритет).
     *  Автоматически оборачивает локальные пути в file:// URI. */
    val displayArtUri: Uri
        get() = coverUrl?.let {
            if (it.startsWith("/")) {
                Uri.fromFile(java.io.File(it))
            } else {
                Uri.parse(it)
            }
        } ?: albumArtUri
}
