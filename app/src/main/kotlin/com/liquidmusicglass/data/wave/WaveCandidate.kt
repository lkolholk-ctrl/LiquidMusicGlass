package com.liquidmusicglass.data.wave

import com.liquidmusicglass.api.icm.IcmWaveTrack
import com.liquidmusicglass.engine.Track

/**
 * Lightweight candidate model for server wave recommendations before they are
 * accepted into the playback queue.
 */
data class WaveCandidate(
    val id: String,
    val title: String? = null,
    val artistName: String? = null,
    val artistId: String? = null,
    val source: String? = null,
    val genre: String? = null
) {
    val normalizedId: String = id.normalizedIdKey().orEmpty()

    val artistKey: String?
        get() = artistId.normalizedKey() ?: artistName.normalizedKey()

    val artistKeys: Set<String>
        get() = setOfNotNull(artistId.normalizedKey(), artistName.normalizedKey())
}

internal fun String?.normalizedKey(): String? =
    this?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

internal fun String?.normalizedIdKey(): String? =
    this?.trim()
        ?.takeIf { it.isNotBlank() }

/**
 * Маппинг негативного фидбека волны («реже»/дизлайк) в кандидата для
 * локального бана. Сервер учитывает сигнал с 30-дневным decay, а клиент
 * через это перестаёт предлагать трек/артиста сразу. Позитивные и жанровые
 * сигналы локально не баним — их обрабатывает только сервер.
 */
fun negativeWaveCandidate(feedbackType: String, value: String): WaveCandidate? {
    val clean = value.trim()
    if (clean.isEmpty()) return null
    return when (feedbackType.trim().lowercase()) {
        "less_track", "dislike-track", "less-like-this" -> WaveCandidate(id = clean)
        "less_artist", "dislike-artist" -> WaveCandidate(id = "", artistId = clean)
        else -> null
    }
}

fun IcmWaveTrack.toWaveCandidate(): WaveCandidate =
    WaveCandidate(
        id = id,
        title = title,
        artistName = artist,
        artistId = artistId,
        source = source
    )

fun Track.toWaveCandidate(): WaveCandidate =
    WaveCandidate(
        id = id,
        title = title,
        artistName = artist,
        artistId = artists.firstOrNull()?.id,
        source = source,
        genre = genre
    )
