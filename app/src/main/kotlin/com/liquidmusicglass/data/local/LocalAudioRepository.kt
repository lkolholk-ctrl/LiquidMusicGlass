package com.liquidmusicglass.data.local

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.liquidmusicglass.engine.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 7b — сканер локальной музыки через MediaStore.Audio.
 *
 * Возвращает список локальных треков как универсальную модель [Track] с
 * content:// URI (MediaStore), которую уже умеют и Media3-воспроизведение
 * (FileDataSource), и JUCE-декодер (через ContentResolver/fd). Никакой сети —
 * вся библиотека доступна с диска, идеальна для честного A/B модели и hand-off.
 *
 * Требует разрешения READ_MEDIA_AUDIO (Android 13+) / READ_EXTERNAL_STORAGE.
 */
object LocalAudioRepository {

    /** Минимальная длительность — отсекаем рингтоны/уведомления/совсем короткое. */
    private const val MIN_DURATION_MS = 20_000L

    suspend fun load(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION
        )
        // Только музыка (не рингтоны/будильники/нотификации).
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        runCatching {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (c.moveToNext()) {
                    val durationMs = c.getLong(durCol)
                    if (durationMs < MIN_DURATION_MS) continue
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val artistRaw = c.getString(artistCol)
                    val artist = if (artistRaw.isNullOrBlank() || artistRaw == "<unknown>")
                        "Unknown Artist" else artistRaw
                    out.add(
                        Track(
                            id = "local:$id",
                            title = c.getString(titleCol) ?: "Unknown",
                            artist = artist,
                            albumName = c.getString(albumCol) ?: "",
                            uri = uri,
                            durationMs = durationMs,
                            albumId = c.getLong(albumIdCol),
                            source = "local"
                        )
                    )
                }
            }
        }
        out
    }
}
