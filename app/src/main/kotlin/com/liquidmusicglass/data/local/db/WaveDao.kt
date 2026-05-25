package com.liquidmusicglass.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO для управления локальными треками, историей прослушиваний
 * и аналитики жанров для "Моей волны".
 */
@Dao
interface WaveDao {

    // ─── Cached Tracks ───

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: CachedTrack)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<CachedTrack>)

    @Query("SELECT * FROM cached_tracks WHERE id = :trackId LIMIT 1")
    suspend fun getTrackById(trackId: String): CachedTrack?

    @Query("SELECT * FROM cached_tracks WHERE genre IN (:genres) ORDER BY cachedAt DESC LIMIT :limit")
    suspend fun getTracksByGenres(genres: List<String>, limit: Int = 50): List<CachedTrack>

    @Query("SELECT * FROM cached_tracks WHERE isFavorite = 1 ORDER BY cachedAt DESC")
    fun getFavoriteTracksFlow(): Flow<List<CachedTrack>>

    @Query("SELECT * FROM cached_tracks WHERE isDownloaded = 1 ORDER BY cachedAt DESC")
    fun getDownloadedTracksFlow(): Flow<List<CachedTrack>>

    @Query("DELETE FROM cached_tracks WHERE id = :trackId")
    suspend fun deleteTrack(trackId: String)

    @Query("DELETE FROM cached_tracks WHERE cachedAt < :olderThanMs")
    suspend fun deleteOldTracks(olderThanMs: Long)

    // ─── Listening History ───

    @Insert
    suspend fun insertListeningRecord(record: ListeningHistory): Long

    @Query("""
        SELECT genre, COUNT(*) as count 
        FROM listening_history 
        WHERE timestamp > :sinceMs 
          AND genre IS NOT NULL 
          AND genre != '' 
        GROUP BY genre 
        ORDER BY count DESC 
        LIMIT :limit
    """)
    suspend fun getTopGenres(sinceMs: Long, limit: Int = 10): List<GenreCount>

    @Query("""
        SELECT genre, COUNT(*) as count 
        FROM listening_history 
        WHERE genre IS NOT NULL 
          AND genre != '' 
        GROUP BY genre 
        ORDER BY count DESC 
        LIMIT :limit
    """)
    suspend fun getTopGenresAllTime(limit: Int = 10): List<GenreCount>

    @Query("SELECT * FROM listening_history WHERE trackId = :trackId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastListen(trackId: String): ListeningHistory?

    @Query("SELECT COUNT(*) FROM listening_history WHERE timestamp > :sinceMs")
    suspend fun getRecentListenCount(sinceMs: Long): Int

    @Query("DELETE FROM listening_history WHERE timestamp < :olderThanMs")
    suspend fun deleteOldHistory(olderThanMs: Long)

    // ─── Analytics ───

    @Query("SELECT COUNT(DISTINCT trackId) FROM listening_history WHERE timestamp > :sinceMs")
    suspend fun getUniqueTracksCount(sinceMs: Long): Int

    @Query("SELECT SUM(durationPlayedMs) FROM listening_history WHERE timestamp > :sinceMs")
    suspend fun getTotalListenTimeMs(sinceMs: Long): Long?
}
