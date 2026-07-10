package com.liquidmusicglass.data.yandex

import android.net.Uri
import com.liquidmusicglass.engine.Track

/**
 * Маппинг трека ЯМ в универсальную модель плеера — для стриминга без
 * скачивания. id = `ym_<id>`, поэтому:
 *  - StreamingDataSource сперва проверит оффлайн-файл `downloads/ym_….mp3|.m4a`
 *    (скачанное играется без сети);
 *  - иначе PlayerController зарезолвит свежую прямую ссылку через
 *    [YandexMusicClient.getDownloadInfo] (ym-ветка резолвера).
 */
fun YandexMusicClient.Track.toEngineTrack(): Track {
    val sid = YandexDownloadManager.storageId(bareTrackId)
    return Track(
        id = sid,
        title = title,
        artist = artistsLine.ifBlank { "Unknown" },
        albumName = albumTitle ?: "",
        // Не-http схема => isOnlineTrack=true => buildMediaItem завернёт в
        // liquid://track?id=ym_… и резолв пойдёт по id на лету.
        uri = Uri.parse("yandex://track/$sid"),
        durationMs = durationMs,
        albumId = -1L,
        coverUrl = coverUrl,
        source = "yandex"
    )
}
