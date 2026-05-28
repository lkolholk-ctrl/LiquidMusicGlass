package com.liquidmusicglass.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Rolling log of every playback event.
 * Each row = one play attempt (completed or skipped).
 *
 * Used to:
 * - Build the exclude list for wave recommendations (recent track IDs)
 * - Calculate per-track play/skip counts (aggregated into TrackStatsEntity)
 * - Power "Recently Played" UI sections
 */
@Entity(
    tableName = "playback_history",
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["timestamp"])
    ]
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: String,
    /** Unix timestamp (ms) when this playback event occurred */
    val timestamp: Long = 0,
    /** Duration actually played in milliseconds (0 if skipped immediately) */
    val playedMs: Long = 0,
    /** Total track duration in milliseconds at time of playback */
    val totalDurationMs: Long = 0,
    /** 1 = user explicitly skipped or track was auto-skipped before 30s, 0 = completed */
    val wasSkipped: Int = 0,
    /** Source context: "wave", "search", "album", "playlist", "favorites", "downloads" */
    val source: String = "unknown"
)
