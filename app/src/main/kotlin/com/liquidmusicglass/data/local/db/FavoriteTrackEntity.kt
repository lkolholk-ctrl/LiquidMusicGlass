package com.liquidmusicglass.data.local.db

/**
 * Entity for storing liked/favorite tracks locally.
 * Fields mirror IcmLibraryTrack for seamless mapping.
 */
data class FavoriteTrackEntity(
    val id: Long = 0,
    val trackId: String,
    val title: String,
    val artistName: String? = null,
    val albumTitle: String? = null,
    val durationMs: Long = 0,
    val genre: String? = null,
    val imageUrl: String? = null,
    val streamUrl: String? = null,
    val artistId: String? = null,
    val collectionId: String? = null,
    val isExplicit: Boolean = false,
    val source: String? = null,
    /** Timestamp when the track was liked locally */
    val likedAt: Long = System.currentTimeMillis(),
    /** true = synced with cloud, false = pending sync */
    val isSynced: Boolean = false,
    /** true = pending deletion on cloud */
    val pendingDelete: Boolean = false
)
