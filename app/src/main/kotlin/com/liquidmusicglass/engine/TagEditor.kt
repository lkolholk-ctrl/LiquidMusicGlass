package com.liquidmusicglass.engine

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Редактирование тегов ЛОКАЛЬНЫХ аудиофайлов (mp3/flac/m4a/ogg) через JAudiotagger.
 *
 * Безопасность файла: работаем на ВРЕМЕННОЙ копии в cache (оригинал не трогаем, пока
 * новая версия не готова), пишем обратно через ContentResolver "rwt" (усечение →
 * чистая замена). Любая ошибка → файл оригинала не повреждён (записываем только
 * полностью готовую копию). Запрос разрешения на запись (scoped storage) — отдельно.
 *
 * Не трогает движок/AutoMix/MediaSession/навигацию — только метаданные файла + точечное
 * обновление Room-индекса (это делает вызывающий код после успеха).
 */
object TagEditor {

    data class Tags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArtist: String = "",
        val trackNumber: String = "",
        val year: String = "",
        val genre: String = ""
    )

    sealed class WriteResult {
        object Ok : WriteResult()
        /** Нужно разрешение пользователя на изменение файла (scoped storage). */
        data class NeedsPermission(val intentSender: IntentSender) : WriteResult()
        object Unsupported : WriteResult()
        data class Error(val message: String) : WriteResult()
    }

    private val SUPPORTED = setOf("mp3", "flac", "m4a", "mp4", "ogg", "oga", "wav", "aac")

    fun extOf(name: String?): String =
        name?.substringAfterLast('.', "")?.lowercase().orEmpty()

    fun isSupported(name: String?): Boolean = extOf(name) in SUPPORTED

    /** Прочитать текущие теги. null — файл не читается; пустые поля — тегов нет. */
    suspend fun read(context: Context, uri: Uri, name: String?): Tags? = withContext(Dispatchers.IO) {
        val ext = extOf(name)
        if (ext !in SUPPORTED) return@withContext null
        val tmp = copyToTemp(context, uri, ext) ?: return@withContext null
        try {
            quietLogging()
            val af = AudioFileIO.read(tmp)
            val tag = af.tag ?: return@withContext Tags()
            fun g(k: FieldKey) = runCatching { tag.getFirst(k) }.getOrDefault("").orEmpty()
            Tags(
                title = g(FieldKey.TITLE), artist = g(FieldKey.ARTIST), album = g(FieldKey.ALBUM),
                albumArtist = g(FieldKey.ALBUM_ARTIST), trackNumber = g(FieldKey.TRACK),
                year = g(FieldKey.YEAR), genre = g(FieldKey.GENRE)
            )
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    /** Записать теги в файл. На scoped storage может вернуть NeedsPermission — тогда
     *  вызывающий запускает intentSender и повторяет write() после согласия юзера. */
    suspend fun write(context: Context, uri: Uri, name: String?, tags: Tags): WriteResult =
        withContext(Dispatchers.IO) {
            val ext = extOf(name)
            if (ext !in SUPPORTED) return@withContext WriteResult.Unsupported

            // 1. Готовим новую версию на временной копии (оригинал пока не трогаем).
            val tmp = copyToTemp(context, uri, ext) ?: return@withContext WriteResult.Error("Не удалось прочитать файл")
            try {
                quietLogging()
                val af = AudioFileIO.read(tmp)
                val tag = af.tagOrCreateAndSetDefault
                fun set(k: FieldKey, v: String) = runCatching {
                    if (v.isBlank()) tag.deleteField(k) else tag.setField(k, v)
                }
                set(FieldKey.TITLE, tags.title)
                set(FieldKey.ARTIST, tags.artist)
                set(FieldKey.ALBUM, tags.album)
                set(FieldKey.ALBUM_ARTIST, tags.albumArtist)
                set(FieldKey.TRACK, tags.trackNumber)
                set(FieldKey.YEAR, tags.year)
                set(FieldKey.GENRE, tags.genre)
                af.commit()
            } catch (e: Exception) {
                tmp.delete()
                return@withContext WriteResult.Error("Не удалось записать теги: ${e.message}")
            }

            // 2. Заливаем готовую копию обратно в оригинал ("rwt" → усечение, чистая замена).
            try {
                context.contentResolver.openFileDescriptor(uri, "rwt")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    }
                } ?: run { tmp.delete(); return@withContext WriteResult.Error("Нет доступа к файлу") }
            } catch (e: RecoverableSecurityException) {
                tmp.delete()
                return@withContext WriteResult.NeedsPermission(e.userAction.actionIntent.intentSender)
            } catch (e: SecurityException) {
                tmp.delete()
                return@withContext WriteResult.Error("Нет разрешения на запись файла")
            } catch (e: Exception) {
                tmp.delete()
                return@withContext WriteResult.Error("Ошибка сохранения: ${e.message}")
            }
            tmp.delete()

            // 3. Обновляем метаданные в MediaStore (для системы/уведомления) — best-effort.
            runCatching {
                val cv = ContentValues().apply {
                    put(MediaStore.Audio.Media.TITLE, tags.title)
                    put(MediaStore.Audio.Media.ARTIST, tags.artist)
                    put(MediaStore.Audio.Media.ALBUM, tags.album)
                    tags.trackNumber.toIntOrNull()?.let { put(MediaStore.Audio.Media.TRACK, it) }
                    tags.year.toIntOrNull()?.let { put(MediaStore.Audio.Media.YEAR, it) }
                }
                context.contentResolver.update(uri, cv, null, null)
            }
            WriteResult.Ok
        }

    /** API 30+: PendingIntent на изменение файла(ов). API 29 — null (там через
     *  RecoverableSecurityException из write()). */
    fun createWriteRequest(context: Context, uris: List<Uri>): IntentSender? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            runCatching { MediaStore.createWriteRequest(context.contentResolver, uris).intentSender }.getOrNull()
        else null

    /** Имя файла (с расширением) из MediaStore — для определения формата. */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun copyToTemp(context: Context, uri: Uri, ext: String): File? {
        return try {
            val tmp = File(context.cacheDir, "tagedit_${System.nanoTime()}.$ext")
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            tmp
        } catch (e: Exception) {
            null
        }
    }

    private fun quietLogging() {
        runCatching { Logger.getLogger("org.jaudiotagger").level = Level.OFF }
    }
}
