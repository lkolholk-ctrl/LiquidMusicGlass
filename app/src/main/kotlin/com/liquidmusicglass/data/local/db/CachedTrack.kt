package com.liquidmusicglass.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локальный трек: избранное / скачанное / кешированное.
 * Используется как источник для "Моей волны" и офлайн-воспроизведения.
 */
@Entity(
    tableName = "cached_tracks",
    indices = [
        Index(value = ["genre"]),
        Index(value = ["isFavorite"]),
        Index(value = ["isDownloaded"]),
        Index(value = ["source"])
    ]
)
data class CachedTrack(
    @PrimaryKey
    val id: String,
    val title: String,
    val artist: String,
    val genre: String? = null,
    val streamUrl: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    val isDownloaded: Boolean = false,
    val isFavorite: Boolean = false,
    val source: String = "UNKNOWN", // "FAVORITES", "DOWNLOADED", "SEARCH", "WAVE"
    val cachedAt: Long = System.currentTimeMillis()
)
