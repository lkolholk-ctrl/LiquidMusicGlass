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
}

internal fun String?.normalizedKey(): String? =
    this?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

internal fun String?.normalizedIdKey(): String? =
    this?.trim()
        ?.takeIf { it.isNotBlank() }

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
