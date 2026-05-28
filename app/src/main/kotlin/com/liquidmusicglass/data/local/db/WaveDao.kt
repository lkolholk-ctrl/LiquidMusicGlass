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
    fun insertTrack(track: CachedTrack): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTracks(tracks: List<CachedTrack>): List<Long>

    @Query("SELECT * FROM cached_tracks WHERE id = :trackId LIMIT 1")
    fun getTrackById(trackId: String): CachedTrack?

    @Query("SELECT * FROM cached_tracks WHERE genre IN (:genres) ORDER BY cachedAt DESC LIMIT :limit")
    fun getTracksByGenres(genres: List<String>, limit: Int = 50): List<CachedTrack>

    @Query("SELECT * FROM cached_tracks WHERE isFavorite = 1 ORDER BY cachedAt DESC")
    fun getFavoriteTracksFlow(): Flow<List<CachedTrack>>

    @Query("SELECT id FROM cached_tracks WHERE isFavorite = 1 OR source = 'FAVORITES' ORDER BY RANDOM() LIMIT 1")
    fun getRandomFavoriteTrackId(): String?

    @Query("SELECT * FROM cached_tracks WHERE isDownloaded = 1 ORDER BY cachedAt DESC")
    fun getDownloadedTracksFlow(): Flow<List<CachedTrack>>

    @Query("DELETE FROM cached_tracks WHERE id = :trackId")
    fun deleteTrack(trackId: String): Int

    @Query("DELETE FROM cached_tracks WHERE cachedAt < :olderThanMs")
    fun deleteOldTracks(olderThanMs: Long): Int

    // ─── Listening History ───

    @Insert
    fun insertListeningRecord(record: ListeningHistory): Long

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
    fun getTopGenres(sinceMs: Long, limit: Int = 10): List<GenreCount>

    @Query("""
        SELECT genre, COUNT(*) as count 
        FROM listening_history 
        WHERE genre IS NOT NULL 
          AND genre != '' 
        GROUP BY genre 
        ORDER BY count DESC 
        LIMIT :limit
    """)
    fun getTopGenresAllTime(limit: Int = 10): List<GenreCount>

    @Query("SELECT * FROM listening_history WHERE trackId = :trackId ORDER BY timestamp DESC LIMIT 1")
    fun getLastListen(trackId: String): ListeningHistory?

    @Query("SELECT COUNT(*) FROM listening_history WHERE timestamp > :sinceMs")
    fun getRecentListenCount(sinceMs: Long): Int

    @Query("DELETE FROM listening_history WHERE timestamp < :olderThanMs")
    fun deleteOldHistory(olderThanMs: Long): Int

    // ─── Analytics ───

    @Query("SELECT COUNT(DISTINCT trackId) FROM listening_history WHERE timestamp > :sinceMs")
    fun getUniqueTracksCount(sinceMs: Long): Int

    @Query("SELECT SUM(durationPlayedMs) FROM listening_history WHERE timestamp > :sinceMs")
    fun getTotalListenTimeMs(sinceMs: Long): Long?
}
