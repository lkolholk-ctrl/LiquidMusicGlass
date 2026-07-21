package com.liquidmusicglass.data.yandex

import android.content.Context
import com.liquidmusicglass.data.local.db.DownloadedTrackEntity
import com.liquidmusicglass.data.local.db.FavoriteTrackDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline-скачивание треков Яндекс.Музыки в ту же полку Downloads, что и ICM.
 *
 * trackId в БД: `ym_<yandexTrackId>` — не пересекается с ICM-id.
 * Не требует ICM Premium: доступ идёт по личному OAuth Яндекса.
 *
 * Аудио стримится сразу в файл (без буферизации целиком в памяти), прогресс —
 * реальный, по байтам. Активную загрузку можно отменить через [cancel].
 */
object YandexDownloadManager {

    const val ID_PREFIX = "ym_"

    enum class Outcome { DONE, FAILED, CANCELLED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    fun storageId(yandexTrackId: String): String =
        ID_PREFIX + yandexTrackId.substringBefore(":")

    fun isYandexId(trackId: String): Boolean = trackId.startsWith(ID_PREFIX)

    fun isDownloading(storageId: String): Boolean = _progress.value.containsKey(storageId)

    fun isDownloaded(context: Context, yandexTrackId: String): Boolean {
        val id = storageId(yandexTrackId)
        return FavoriteTrackDatabase.getInstance(context).isDownloaded(id)
    }

    /** Отменить активную загрузку: файл-недокачка удаляется, ретраев нет. */
    fun cancel(storageId: String) {
        cancelFlags[storageId]?.set(true)
        jobs[storageId]?.cancel()
    }

    /**
     * Записать лайк/снятие лайка Y-трека обратно в аккаунт Яндекса.
     * [ymTrackId] — engine-id вида `ym_<id>`. Тихо игнорирует, если нет
     * токена/uid. Блокирующий вызов — звать с IO.
     */
    fun writeLike(ymTrackId: String, liked: Boolean) {
        val client = YandexAuthRepository.clientOrNull() ?: return
        val uid = YandexAuthRepository.uidOrNull() ?: return
        val bare = ymTrackId.removePrefix(ID_PREFIX)
        runCatching {
            if (liked) client.likeTrack(uid, bare) else client.unlikeTrack(uid, bare)
        }
    }

    /**
     * Скачать [track] через текущий токен [YandexAuthRepository] → публичные
     * Загрузки (`Download/LiquidMusicGlass/Артист - Название.…`, MediaStore)
     * + запись в downloaded_tracks (localPath = content://-uri; при недоступном
     * MediaStore — приватный `filesDir/downloads/` как фолбэк).
     */
    fun download(
        context: Context,
        track: YandexMusicClient.Track,
        onComplete: (Outcome) -> Unit = {},
    ) {
        val client = YandexAuthRepository.clientOrNull()
        if (client == null) {
            onComplete(Outcome.FAILED)
            return
        }

        val sid = storageId(track.bareTrackId)
        if (isDownloading(sid)) return

        val db = FavoriteTrackDatabase.getInstance(context)
        if (db.isDownloaded(sid)) {
            onComplete(Outcome.DONE)
            return
        }

        val app = context.applicationContext
        val cancelled = AtomicBoolean(false)
        cancelFlags[sid] = cancelled
        setProgress(sid, 0.02f)

        val job = scope.launch {
            val outcome = runCatching {
                perform(app, client, track, sid, db, cancelled)
            }.getOrDefault(Outcome.FAILED)
            onComplete(if (cancelled.get()) Outcome.CANCELLED else outcome)
        }
        jobs[sid] = job
        job.invokeOnCompletion {
            jobs.remove(sid)
            cancelFlags.remove(sid)
            setProgress(sid, null)
        }
    }

    private fun perform(
        context: Context,
        client: YandexMusicClient,
        track: YandexMusicClient.Track,
        sid: String,
        db: FavoriteTrackDatabase,
        cancelled: AtomicBoolean,
    ): Outcome {
        val dir = File(context.filesDir, "downloads").apply { mkdirs() }
        val tmp = File(dir, "$sid.temp")

        // Аудио: стриминг сразу в temp-файл; байтовый прогресс → 2..90%.
        val info = try {
            client.downloadTrackToFileStreaming(
                trackId = track.bareTrackId,
                dest = tmp,
                quality = YandexAuthRepository.effectiveQuality(),
                onProgress = { p -> setProgress(sid, 0.02f + p * 0.88f) },
                shouldAbort = { cancelled.get() },
            )
        } catch (e: Exception) {
            tmp.delete()
            return if (e is YandexDownloadCancelledException) Outcome.CANCELLED else Outcome.FAILED
        }

        val ext = when {
            info.codec.equals("flac", ignoreCase = true) -> ".flac"
            info.codec.equals("aac", ignoreCase = true) -> ".m4a"
            else -> ".mp3"
        }
        val quality = if (info.codec.equals("flac", ignoreCase = true)) {
            "flac"
        } else {
            "${info.bitrateInKbps}kbps"
        }

        // Публичные Загрузки (Download/LiquidMusicGlass, видно в проводнике) —
        // основной путь. MediaStore не дался → приватный файл как раньше
        // (лучше приватная копия, чем потерянная загрузка).
        val publicUri = com.liquidmusicglass.data.local.PublicDownloads.exportAudio(
            context, tmp,
            com.liquidmusicglass.data.local.PublicDownloads
                .displayName(track.artistsLine, track.title).ifBlank { sid },
            ext,
        )
        val storedPath: String
        if (publicUri != null) {
            storedPath = publicUri
            tmp.delete()
        } else {
            val audioFile = File(dir, "$sid$ext")
            try {
                if (audioFile.exists()) audioFile.delete()
                if (!tmp.renameTo(audioFile)) {
                    tmp.copyTo(audioFile, overwrite = true)
                    tmp.delete()
                }
            } catch (_: Exception) {
                tmp.delete()
                return Outcome.FAILED
            }
            storedPath = audioFile.absolutePath
        }

        setProgress(sid, 0.95f)

        var localCover: String? = null
        val coverUrl = track.coverUrl
        if (!coverUrl.isNullOrBlank() && !cancelled.get()) {
            val coverBytes = client.downloadCoverBytes(coverUrl)
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                val coverDir = File(dir, ".covers").apply { mkdirs() }
                val coverFile = File(coverDir, "$sid.jpg")
                runCatching {
                    coverFile.writeBytes(coverBytes)
                    localCover = coverFile.absolutePath
                }
            }
        }

        db.insertDownloaded(
            DownloadedTrackEntity(
                trackId = sid,
                title = track.title,
                artistName = track.artistsLine.ifBlank { "Unknown" },
                albumTitle = track.albumTitle,
                durationMs = track.durationMs,
                imageUrl = coverUrl,
                localPath = storedPath,
                localCoverPath = localCover,
                quality = "yandex-$quality",
            )
        )
        setProgress(sid, 1f)
        return Outcome.DONE
    }

    /** Атомарное обновление map прогресса — параллельные загрузки не теряют апдейты друг друга. */
    private fun setProgress(id: String, value: Float?) {
        _progress.update { current ->
            if (value == null) current - id else current + (id to value)
        }
    }
}
