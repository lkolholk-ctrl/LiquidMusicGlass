package com.liquidmusicglass.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * DAO for smart track de-duplication and playback analytics.
 *
 * All write operations are @Transaction-safe.
 * All exclude-list queries are sorted DESC by timestamp for "most recent first".
 */
@Dao
interface PlaybackHistoryDao {

    // ═══════════════════════════════════════════════════════════
    //  TrackStatsEntity — upsert & increment
    // ═══════════════════════════════════════════════════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackStat(stats: TrackStatsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackStats(stats: List<TrackStatsEntity>)

    @Query("UPDATE track_stats SET playCount = playCount + 1, lastPlayedTimestamp = :timestamp WHERE trackId = :trackId")
    suspend fun incrementPlayCountRaw(trackId: String, timestamp: Long)

    @Query("UPDATE track_stats SET skippedCount = skippedCount + 1 WHERE trackId = :trackId")
    suspend fun incrementSkipCountRaw(trackId: String)

    @Query("SELECT * FROM track_stats WHERE trackId = :trackId LIMIT 1")
    suspend fun getTrackStat(trackId: String): TrackStatsEntity?

    @Query("SELECT * FROM track_stats ORDER BY lastPlayedTimestamp DESC LIMIT :limit")
    suspend fun getAllTrackStats(limit: Int = 500): List<TrackStatsEntity>

    @Query("SELECT * FROM track_stats WHERE playCount > 0 ORDER BY playCount DESC LIMIT :limit")
    suspend fun getMostPlayed(limit: Int = 50): List<TrackStatsEntity>

    @Query("SELECT * FROM track_stats WHERE skippedCount > 0 ORDER BY skippedCount DESC LIMIT :limit")
    suspend fun getMostSkipped(limit: Int = 50): List<TrackStatsEntity>

    // ═══════════════════════════════════════════════════════════
    //  PlaybackHistoryEntity — insert & query
    // ═══════════════════════════════════════════════════════════

    @Insert
    suspend fun insertHistoryEntry(entry: PlaybackHistoryEntity): Long

    /**
     * Get the last [limit] played track IDs, ordered by timestamp DESC.
     * This is the primary exclude-list for wave recommendations.
     */
    @Query("""
        SELECT trackId FROM playback_history
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getRecentTrackIds(limit: Int): List<String>

    @Query("""
        SELECT * FROM playback_history
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getRecentHistory(limit: Int = 100): List<PlaybackHistoryEntity>

    @Query("SELECT COUNT(*) FROM playback_history WHERE trackId = :trackId")
    suspend fun getPlayCountForTrack(trackId: String): Int

    // ═══════════════════════════════════════════════════════════
    //  Cleanup
    // ═══════════════════════════════════════════════════════════

    @Query("DELETE FROM playback_history WHERE timestamp < :olderThanMs")
    suspend fun deleteOldHistory(olderThanMs: Long): Int

    @Query("DELETE FROM track_stats WHERE lastPlayedTimestamp < :olderThanMs AND playCount = 0")
    suspend fun deleteStaleStats(olderThanMs: Long): Int

    @Query("DELETE FROM playback_history")
    suspend fun clearAllHistory(): Int

    @Query("DELETE FROM track_stats")
    suspend fun clearAllStats(): Int

    // ═══════════════════════════════════════════════════════════
    //  Convenience: log a complete playback event in one TX
    // ═══════════════════════════════════════════════════════════

    @Transaction
    suspend fun logPlaybackEvent(
        trackId: String,
        title: String,
        artistId: String? = null,
        playedMs: Long,
        totalDurationMs: Long,
        wasSkipped: Boolean,
        source: String
    ) {
        val now = System.currentTimeMillis()

        // 1. Write to rolling history
        insertHistoryEntry(
            PlaybackHistoryEntity(
                trackId = trackId,
                timestamp = now,
                playedMs = playedMs,
                totalDurationMs = totalDurationMs,
                wasSkipped = if (wasSkipped) 1 else 0,
                source = source
            )
        )

        // 2. Update aggregate stats
        val existing = getTrackStat(trackId)
        if (existing != null) {
            if (wasSkipped) {
                incrementSkipCountRaw(trackId)
            } else {
                incrementPlayCountRaw(trackId, now)
            }
        } else {
            insertTrackStat(
                TrackStatsEntity(
                    trackId = trackId,
                    title = title,
                    artistId = artistId ?: "",
                    playCount = if (wasSkipped) 0 else 1,
                    skippedCount = if (wasSkipped) 1 else 0,
                    lastPlayedTimestamp = if (wasSkipped) 0L else now
                )
            )
        }
    }

    @Transaction
    suspend fun incrementPlayCount(trackId: String, title: String, artistId: String?, timestamp: Long) {
        val existing = getTrackStat(trackId)
        if (existing != null) {
            incrementPlayCountRaw(trackId, timestamp)
        } else {
            insertTrackStat(
                TrackStatsEntity(
                    trackId = trackId,
                    title = title,
                    artistId = artistId ?: "",
                    playCount = 1,
                    skippedCount = 0,
                    lastPlayedTimestamp = timestamp
                )
            )
        }
    }

    @Transaction
    suspend fun incrementSkipCount(trackId: String, title: String, artistId: String?) {
        val existing = getTrackStat(trackId)
        if (existing != null) {
            incrementSkipCountRaw(trackId)
        } else {
            insertTrackStat(
                TrackStatsEntity(
                    trackId = trackId,
                    title = title,
                    artistId = artistId ?: "",
                    playCount = 0,
                    skippedCount = 1,
                    lastPlayedTimestamp = 0L
                )
            )
        }
    }

    @Query("""
        SELECT artistId FROM track_stats
        WHERE playCount > 0 AND artistId IS NOT NULL AND artistId != ''
        GROUP BY artistId
        ORDER BY SUM(playCount) DESC
        LIMIT :limit
    """)
    suspend fun getTopArtists(limit: Int = 5): List<String>

    @Query("""
        SELECT genre FROM listening_history
        WHERE genre IS NOT NULL AND genre != ''
        GROUP BY genre
        ORDER BY COUNT(*) DESC
        LIMIT :limit
    """)
    suspend fun getTopGenres(limit: Int = 3): List<String>
}
