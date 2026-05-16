package com.liquidmusicglass.engine

import android.net.Uri

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val albumName: String,
    val uri: Uri,
    val durationMs: Long,
    val albumId: Long
) {
    val albumArtUri: Uri
        get() = Uri.parse("content://media/external/audio/albumart/$albumId")
}
