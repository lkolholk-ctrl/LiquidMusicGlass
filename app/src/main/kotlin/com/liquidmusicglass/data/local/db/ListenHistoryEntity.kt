package com.liquidmusicglass.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Реальная история прослушивания для UI «История прослушивания».
 *
 * Это РАСТУЩИЙ список с отображаемыми полями (название/артист/обложка), поэтому
 * живёт в Room (а не в Preferences DataStore). Уникален по [trackId] — повторное
 * прослушивание обновляет [playedAt] (как «недавние» у Apple Music), список
 * сортируется по [playedAt] убыв.
 */
@Entity(
    tableName = "listen_history",
    indices = [Index(value = ["playedAt"])]
)
data class ListenHistoryEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val coverUrl: String? = null,
    val durationMs: Long = 0,
    /** Unix-время (мс) последнего прослушивания. */
    val playedAt: Long = 0
)
