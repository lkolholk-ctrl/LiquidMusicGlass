package com.liquidmusicglass.data.local

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.liquidmusicglass.data.local.db.AlbumAgg
import com.liquidmusicglass.data.local.db.AppDatabase
import com.liquidmusicglass.data.local.db.ArtistAgg
import com.liquidmusicglass.data.local.db.LocalTrackEntity
import com.liquidmusicglass.engine.PlaybackContext
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Индексатор ЛОКАЛЬНОЙ медиатеки: один раз в фоне сканирует MediaStore (только
 * метаданные, файлы/теги НЕ парсим — система уже проиндексировала) и кладёт в Room
 * для быстрых Артисты/Альбомы/Треки + поиск. Переиндексирует при изменении набора
 * файлов (добавили/удалили). Ничего из аудио-движка/AutoMix/MediaSession не трогает.
 */
object LocalLibraryIndexer {

    enum class Status { IDLE, SCANNING, READY }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    private val _progress = MutableStateFlow(0)   // сколько треков проиндексировано (для прогресса)
    val progress: StateFlow<Int> = _progress

    /** Проверить актуальность индекса и при необходимости пересканировать. Безопасно звать многократно. */
    suspend fun ensureIndexed(context: Context) = withContext(Dispatchers.IO) {
        if (_status.value == Status.SCANNING) return@withContext
        val dao = AppDatabase.getInstance(context).localTracksDao()
        val currentIds = runCatching { queryMediaIds(context) }.getOrDefault(emptySet())
        val existingIds = runCatching { dao.allMediaIds().toSet() }.getOrDefault(emptySet())
        // Индекс актуален — выходим (быстрый путь при обычном старте).
        if (existingIds.isNotEmpty() && existingIds == currentIds) {
            _status.value = Status.READY
            return@withContext
        }
        reindex(context)
    }

    /** Полное пересканирование. */
    suspend fun reindex(context: Context) = withContext(Dispatchers.IO) {
        _status.value = Status.SCANNING
        _progress.value = 0
        val dao = AppDatabase.getInstance(context).localTracksDao()
        val items = runCatching { scanMediaStore(context) }.getOrDefault(emptyList())
        runCatching {
            dao.clear()
            if (items.isNotEmpty()) dao.upsertAll(items)
        }
        _status.value = Status.READY
    }

    private fun queryMediaIds(context: Context): Set<Long> {
        val ids = HashSet<Long>()
        val proj = arrayOf(MediaStore.Audio.Media._ID)
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null, null
        )?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            while (c.moveToNext()) ids.add(c.getLong(idC))
        }
        return ids
    }

    private fun scanMediaStore(context: Context): List<LocalTrackEntity> {
        val out = ArrayList<LocalTrackEntity>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null, null
        )?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackC = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val yearC = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val dateC = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            var n = 0
            while (c.moveToNext()) {
                val id = c.getLong(idC)
                val title = c.getString(titleC)?.takeIf { it.isNotBlank() } ?: "Unknown"
                val artistRaw = c.getString(artistC)
                val artist = if (artistRaw.isNullOrBlank() || artistRaw == "<unknown>") "Unknown Artist" else artistRaw
                val album = c.getString(albumC) ?: ""
                val albumId = c.getLong(albumIdC)
                val dur = c.getLong(durC)
                val trackRaw = if (trackC >= 0) c.getInt(trackC) else 0
                val track = if (trackRaw > 1000) trackRaw % 1000 else trackRaw  // диск*1000+трек
                val year = if (yearC >= 0) c.getInt(yearC) else 0
                val dateAdded = if (dateC >= 0) c.getLong(dateC) else 0L
                out.add(
                    LocalTrackEntity("local:$id", id, title, artist, album, albumId, dur, track, year, dateAdded)
                )
                _progress.value = ++n
            }
        }
        return out
    }
}

/**
 * Доступ к индексу для UI: поиск, конвертация в Track и запуск воспроизведения
 * через СУЩЕСТВУЮЩИЙ локальный путь (JUCE), как делает текущий Local Audio.
 */
object LocalLibraryStore {

    data class SearchResults(
        val artists: List<ArtistAgg>,
        val albums: List<AlbumAgg>,
        val tracks: List<LocalTrackEntity>
    )

    suspend fun search(context: Context, query: String): SearchResults = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext SearchResults(emptyList(), emptyList(), emptyList())
        val dao = AppDatabase.getInstance(context).localTracksDao()
        val like = "%" + q.replace("%", "").replace("_", "") + "%"
        SearchResults(
            artists = runCatching { dao.searchArtists(like) }.getOrDefault(emptyList()),
            albums = runCatching { dao.searchAlbums(like) }.getOrDefault(emptyList()),
            tracks = runCatching { dao.searchTracks(like) }.getOrDefault(emptyList())
        )
    }

    fun toTrack(e: LocalTrackEntity): Track = Track(
        id = e.id,
        title = e.title,
        artist = e.artist,
        albumName = e.albumName,
        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, e.mediaStoreId),
        durationMs = e.durationMs,
        albumId = e.albumId,
        source = "local"
    )

    /** После редактирования тегов — точечно обновить запись в индексе (Медиатека/поиск сразу актуальны). */
    suspend fun updateTags(
        context: Context, trackId: String,
        title: String, artist: String, album: String, trackNumber: Int, year: Int
    ) = withContext(Dispatchers.IO) {
        runCatching {
            AppDatabase.getInstance(context).localTracksDao()
                .updateTags(trackId, title, artist, album, trackNumber, year)
        }
    }

    /** Массовое: после записи общих полей в файлы — обновить только заполненные поля в индексе. */
    suspend fun updateTagsBulk(
        context: Context, trackId: String, artist: String, album: String, year: Int
    ) = withContext(Dispatchers.IO) {
        runCatching {
            AppDatabase.getInstance(context).localTracksDao()
                .updateTagsBulk(trackId, artist, album, year)
        }
    }

    /** Играть локальную выборку с указанной позиции — через существующий JUCE-путь. */
    fun play(context: Context, tracks: List<LocalTrackEntity>, startIndex: Int) {
        if (tracks.isEmpty()) return
        PlayerController.playLocalOnJuce(
            context = context,
            tracks = tracks.map { toTrack(it) },
            startIndex = startIndex.coerceIn(0, tracks.lastIndex),
            playbackContext = PlaybackContext.Playlist("local_library")
        )
    }
}
