package com.liquidmusicglass.data.local

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Экспорт скачанных треков в ПУБЛИЧНУЮ папку Загрузок
 * (`Download/LiquidMusicGlass/…`) через MediaStore.Downloads.
 *
 * Почему MediaStore: scoped storage (minSdk 29, без MANAGE_EXTERNAL_STORAGE) —
 * прямой File-доступ к /storage/emulated/0/Download запрещён. MediaStore же
 * не требует НИКАКИХ разрешений для собственных файлов и работает на 29+.
 *
 * `localPath` в downloaded_tracks теперь может быть ДВУХ видов:
 *   - `content://media/...` — новый путь (публичные Загрузки);
 *   - `/data/user/0/.../files/downloads/...` — легаси (приватная папка).
 * Все потребители обязаны ходить через [toPlayableUri]/[exists]/[delete]/
 * [sizeBytes] — они понимают оба вида.
 */
object PublicDownloads {

    private const val RELATIVE_DIR = "Download/LiquidMusicGlass"

    fun mimeFor(ext: String): String = when (ext.lowercase().removePrefix(".")) {
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/x-wav"
        else -> "audio/mpeg"
    }

    /** Имя файла без запрещённых в FAT/MediaStore символов. */
    fun sanitizeName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().take(120)

    /**
     * Скопировать готовый [src] в публичные Загрузки под именем [displayName]
     * (с расширением). Вернёт content://-uri строкой или null при неудаче —
     * вызывающий тогда падает обратно на приватную папку (лучше приватный
     * файл, чем потерянная загрузка). Коллизии имён MediaStore решает сам
     * (добавляет « (1)»). IS_PENDING прячет недокачанный файл от галерей.
     */
    fun exportAudio(context: Context, src: File, displayName: String, ext: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizeName(displayName) + ext)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(ext))
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: error("no output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        }.getOrElse {
            // Недокачанную запись подчищаем — иначе в Загрузках висел бы
            // невидимый pending-огрызок до чистки системой.
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun isContentPath(path: String): Boolean = path.startsWith("content://")

    /** Uri для воспроизведения: content:// как есть, файл — через fromFile. */
    fun toPlayableUri(path: String): Uri =
        if (isContentPath(path)) Uri.parse(path) else Uri.fromFile(File(path))

    fun exists(context: Context, path: String): Boolean =
        if (isContentPath(path)) {
            runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { } != null
            }.getOrDefault(false)
        } else {
            File(path).exists()
        }

    fun sizeBytes(context: Context, path: String): Long =
        if (isContentPath(path)) {
            runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                    ?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)
        } else {
            runCatching { File(path).length() }.getOrDefault(0L)
        }

    /** Удалить файл трека (оба вида пути). Молча, best-effort. */
    fun delete(context: Context, path: String) {
        if (isContentPath(path)) {
            runCatching { context.contentResolver.delete(Uri.parse(path), null, null) }
        } else {
            runCatching { File(path).takeIf { it.exists() }?.delete() }
        }
    }

    /** Для красивого имени файла: «Артист - Название». */
    fun displayName(artist: String?, title: String): String {
        val a = artist?.trim().orEmpty()
        return if (a.isBlank()) title.trim() else "$a - ${title.trim()}"
    }
}
