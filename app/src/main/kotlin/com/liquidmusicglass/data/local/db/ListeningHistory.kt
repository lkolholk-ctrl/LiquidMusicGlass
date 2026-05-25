package com.liquidmusicglass.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Запись о прослушивании трека.
 * Пишется только если трек играл дольше 30 секунд.
 * Используется для аналитики жанров и формирования "Моей волны".
 */
@Entity(
    tableName = "listening_history",
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["genre"]),
        Index(value = ["timestamp"]),
        Index(value = ["source"])
    ]
)
data class ListeningHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: String,
    val title: String,
    val artist: String,
    val genre: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "UNKNOWN", // "FAVORITES", "DOWNLOADED", "SEARCH", "WAVE"
    val durationPlayedMs: Long = 0 // сколько реально слушали
)
