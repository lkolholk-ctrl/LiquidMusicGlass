package com.liquidmusicglass.data.yandex

import android.content.Context
import com.liquidmusicglass.data.local.db.DownloadedTrackEntity
import com.liquidmusicglass.data.local.db.FavoriteTrackDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Offline-скачивание треков Яндекс.Музыки в ту же полку Downloads, что и ICM.
 *
 * trackId в БД: `ym_<yandexTrackId>` — не пересекается с ICM-id.
 * Не требует ICM Premium: доступ идёт по личному OAuth Яндекса.
 */
object YandexDownloadManager {

    const val ID_PREFIX = "ym_"

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

    /**
     * Скачать [track] через текущий токен [YandexAuthRepository] →
     * `filesDir/downloads/ym_….mp3` + запись в downloaded_tracks.
     */
    fun download(
        context: Context,
        track: YandexMusicClient.Track,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val client = YandexAuthRepository.clientOrNull()
        if (client == null) {
            onComplete(false)
            return
        }

        val sid = storageId(track.bareTrackId)
        if (isDownloading(sid)) return

        val db = FavoriteTrackDatabase.getInstance(context)
        if (db.isDownloaded(sid)) {
            onComplete(true)
            return
        }

        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            setProgress(sid, 0.05f)
            val ok = runCatching {
                perform(app, client, track, sid, db)
            }.getOrDefault(false)
            setProgress(sid, null)
            onComplete(ok)
        }
    }

    private fun perform(
        context: Context,
        client: YandexMusicClient,
        track: YandexMusicClient.Track,
        sid: String,
        db: FavoriteTrackDatabase,
    ): Boolean {
        setProgress(sid, 0.1f)
        val (bytes, info) = client.downloadTrackBytes(track.bareTrackId)
        setProgress(sid, 0.75f)

        val ext = when {
            info.codec.equals("aac", ignoreCase = true) -> ".m4a"
            info.codec.equals("mp3", ignoreCase = true) -> ".mp3"
            else -> ".mp3"
        }
        val quality = "${info.bitrateInKbps}kbps"

        val dir = File(context.filesDir, "downloads").apply { mkdirs() }
        val audioFile = File(dir, "$sid$ext")
        val tmp = File(dir, "$sid.temp")
        try {
            tmp.writeBytes(bytes)
            if (audioFile.exists()) audioFile.delete()
            if (!tmp.renameTo(audioFile)) {
                tmp.copyTo(audioFile, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
            tmp.delete()
            return false
        }

        setProgress(sid, 0.9f)

        var localCover: String? = null
        val coverUrl = track.coverUrl
        if (!coverUrl.isNullOrBlank()) {
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
                localPath = audioFile.absolutePath,
                localCoverPath = localCover,
                quality = "yandex-$quality",
            )
        )
        setProgress(sid, 1f)
        return true
    }

    private fun setProgress(id: String, value: Float?) {
        val m = _progress.value.toMutableMap()
        if (value == null) m.remove(id) else m[id] = value
        _progress.value = m
    }

}
