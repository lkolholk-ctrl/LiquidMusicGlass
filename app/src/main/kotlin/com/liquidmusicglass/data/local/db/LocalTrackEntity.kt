package com.liquidmusicglass.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Индекс ЛОКАЛЬНЫХ треков (кэш метаданных из MediaStore) для быстрой навигации
 * Артисты/Альбомы/Треки + поиск. Заполняется один раз в фоне (LocalLibraryIndexer),
 * обновляется при изменении медиатеки. Сами теги/файлы не парсим — MediaStore уже
 * проиндексирован системой, поэтому скан быстрый; обложки грузит лениво Coil.
 */
@Entity(
    tableName = "local_tracks",
    indices = [Index("artist"), Index("albumId"), Index("title")]
)
data class LocalTrackEntity(
    @PrimaryKey val id: String,        // "local:{mediaStoreId}"
    val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val albumName: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int,
    val year: Int,
    val dateAddedSec: Long
)

/** Агрегат артиста (для списка «Артисты»). */
data class ArtistAgg(
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val anyAlbumId: Long               // для превью-обложки
)

/** Агрегат альбома (для списка «Альбомы»). */
data class AlbumAgg(
    val albumId: Long,
    val name: String,
    val artist: String,
    val trackCount: Int,
    val year: Int
)

@Dao
interface LocalTracksDao {

    // ── индексация ──
    @Query("SELECT COUNT(*) FROM local_tracks")
    suspend fun count(): Int

    @Query("SELECT mediaStoreId FROM local_tracks")
    suspend fun allMediaIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LocalTrackEntity>)

    @Query("DELETE FROM local_tracks WHERE mediaStoreId NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<Long>)

    @Query("DELETE FROM local_tracks")
    suspend fun clear()

    /** Точечное обновление тегов одного трека после редактирования (без полного скана). */
    @Query("UPDATE local_tracks SET title = :title, artist = :artist, albumName = :album, trackNumber = :track, year = :year WHERE id = :id")
    suspend fun updateTags(id: String, title: String, artist: String, album: String, track: Int, year: Int)

    /** Массовое: перезаписать только заполненные поля (пустые — не трогаем). */
    @Query("""UPDATE local_tracks SET
                artist = CASE WHEN :artist != '' THEN :artist ELSE artist END,
                albumName = CASE WHEN :album != '' THEN :album ELSE albumName END,
                year = CASE WHEN :year > 0 THEN :year ELSE year END
              WHERE id = :id""")
    suspend fun updateTagsBulk(id: String, artist: String, album: String, year: Int)

    // ── треки (сортировки) ──
    @Query("SELECT * FROM local_tracks ORDER BY title COLLATE NOCASE")
    fun tracksByTitle(): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks ORDER BY artist COLLATE NOCASE, albumName COLLATE NOCASE, trackNumber")
    fun tracksByArtist(): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks ORDER BY albumName COLLATE NOCASE, trackNumber")
    fun tracksByAlbum(): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks ORDER BY dateAddedSec DESC")
    fun tracksByDateAdded(): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks ORDER BY durationMs DESC")
    fun tracksByDuration(): Flow<List<LocalTrackEntity>>

    // ── артисты / альбомы (агрегаты) ──
    @Query("""SELECT artist AS name, COUNT(*) AS trackCount,
                     COUNT(DISTINCT albumId) AS albumCount, MIN(albumId) AS anyAlbumId
              FROM local_tracks GROUP BY artist COLLATE NOCASE
              ORDER BY artist COLLATE NOCASE""")
    fun artists(): Flow<List<ArtistAgg>>

    @Query("""SELECT albumId, albumName AS name, artist,
                     COUNT(*) AS trackCount, MAX(year) AS year
              FROM local_tracks GROUP BY albumId
              ORDER BY albumName COLLATE NOCASE""")
    fun albums(): Flow<List<AlbumAgg>>

    @Query("SELECT * FROM local_tracks WHERE artist = :artist ORDER BY albumName COLLATE NOCASE, trackNumber, title COLLATE NOCASE")
    fun tracksOfArtist(artist: String): Flow<List<LocalTrackEntity>>

    @Query("""SELECT albumId, albumName AS name, artist,
                     COUNT(*) AS trackCount, MAX(year) AS year
              FROM local_tracks WHERE artist = :artist GROUP BY albumId
              ORDER BY year, albumName COLLATE NOCASE""")
    fun albumsOfArtist(artist: String): Flow<List<AlbumAgg>>

    @Query("SELECT * FROM local_tracks WHERE albumId = :albumId ORDER BY trackNumber, title COLLATE NOCASE")
    fun tracksOfAlbum(albumId: Long): Flow<List<LocalTrackEntity>>

    // ── поиск (LIKE, регистронезависимо; q должен быть вида "%text%") ──
    @Query("SELECT * FROM local_tracks WHERE title LIKE :q COLLATE NOCASE ORDER BY title COLLATE NOCASE LIMIT 80")
    suspend fun searchTracks(q: String): List<LocalTrackEntity>

    @Query("""SELECT artist AS name, COUNT(*) AS trackCount,
                     COUNT(DISTINCT albumId) AS albumCount, MIN(albumId) AS anyAlbumId
              FROM local_tracks WHERE artist LIKE :q COLLATE NOCASE
              GROUP BY artist COLLATE NOCASE ORDER BY artist COLLATE NOCASE LIMIT 40""")
    suspend fun searchArtists(q: String): List<ArtistAgg>

    @Query("""SELECT albumId, albumName AS name, artist,
                     COUNT(*) AS trackCount, MAX(year) AS year
              FROM local_tracks WHERE albumName LIKE :q COLLATE NOCASE
              GROUP BY albumId ORDER BY albumName COLLATE NOCASE LIMIT 40""")
    suspend fun searchAlbums(q: String): List<AlbumAgg>
}
