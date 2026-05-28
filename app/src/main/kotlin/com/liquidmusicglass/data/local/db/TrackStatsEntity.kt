package com.liquidmusicglass.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-track statistics for "My Wave" smart de-duplication.
 * Keeps aggregate counts and last-played timestamp for each unique track.
 *
 * Used to:
 * - Exclude recently-played tracks from wave recommendations
 * - Detect skip patterns (high skipCount → avoid in future)
 * - Prioritize tracks with high playCount
 */
@Entity(
    tableName = "track_stats",
    indices = [
        Index(value = ["artistId"]),
        Index(value = ["playCount"]),
        Index(value = ["skippedCount"]),
        Index(value = ["lastPlayedTimestamp"])
    ]
)
data class TrackStatsEntity(
    @PrimaryKey
    val trackId: String,
    val title: String,
    val artistId: String? = null,
    /** How many times the user listened to this track for >30s */
    val playCount: Int = 0,
    /** How many times the user skipped this track early (<30s or explicit skip) */
    val skippedCount: Int = 0,
    /** Unix timestamp (ms) of the most recent full listen */
    val lastPlayedTimestamp: Long = 0L
)
