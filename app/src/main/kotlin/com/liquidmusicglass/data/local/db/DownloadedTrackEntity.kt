package com.liquidmusicglass.data.local.db

/**
 * Entity for storing downloaded/offline tracks locally.
 * Fields mirror IcmLibraryTrack and FavoriteTrackEntity for seamless mapping,
 * adding localPath to track the downloaded audio file on the device.
 */
data class DownloadedTrackEntity(
    val id: Long = 0,
    val trackId: String,
    val title: String,
    val artistName: String? = null,
    val albumTitle: String? = null,
    val durationMs: Long = 0,
    val imageUrl: String? = null,
    val localPath: String,
    val quality: String? = null,
    val downloadedAt: Long = System.currentTimeMillis()
)
