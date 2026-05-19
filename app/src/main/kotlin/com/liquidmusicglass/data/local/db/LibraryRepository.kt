package com.liquidmusicglass.data.local.db

import android.content.Context
import android.net.Uri
import com.liquidmusicglass.api.icm.IcmApi
import com.liquidmusicglass.api.icm.IcmLibraryTrack
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Repository for the Library (liked tracks).
 * Provides two-way sync between local SQLite DB and ICM cloud API.
 *
 * Architecture:
 * - Local SQLite DB is the source of truth for UI (reactive Flow)
 * - Cloud sync happens in background on IO dispatcher
 * - Like/unlike are optimistic: local DB updates immediately, then cloud sync fires
 */
class LibraryRepository private constructor(context: Context) {

    private val db = FavoriteTrackDatabase.getInstance(context)
    private val api = IcmApi.getInstance()

    /** Reactive flow of all favorite tracks — drives Compose UI */
    val favoritesFlow: Flow<List<FavoriteTrackEntity>> = db.favoritesFlow

    /** Reactive flow of favorite track IDs — drives heart icon states */
    val favoriteIdsFlow: Flow<Set<String>> = db.favoriteIdsFlow

    /** Get single favorite status reactively */
    fun isFavoriteFlow(trackId: String): Flow<Boolean> = db.isFavoriteFlow(trackId)

    /** Get all favorites as Track objects for playback */
    suspend fun getAllFavoritesAsTracks(): List<Track> = withContext(Dispatchers.IO) {
        db.getAllFavorites().map { it.toTrack() }
    }

    /** Get count of favorites */
    suspend fun getFavoriteCount(): Int = withContext(Dispatchers.IO) {
        db.getCount()
    }

    init {
        // Sync PlayerController's in-memory favorite IDs with DB on init
        CoroutineScope(Dispatchers.IO).launch {
            val ids = db.getFavoriteTrackIds()
            PlayerController.setFavoriteIds(ids)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Two-way sync with cloud
    // ═══════════════════════════════════════════════════════════

    /**
     * Full sync: pull cloud likes, merge with local state, push pending changes.
     * Call on app launch or when user pulls to refresh.
     */
    suspend fun syncWithCloud(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. Pull cloud likes
            val cloudLikes = mutableListOf<IcmLibraryTrack>()
            var offset = 0
            val limit = 50
            while (true) {
                val response = IcmRepository.getLibraryLikes(
                    source = "all",
                    limit = limit,
                    offset = offset
                )
                val items = response?.items ?: break
                if (items.isEmpty()) break
                cloudLikes.addAll(items)
                if (items.size < limit) break
                offset += items.size
            }

            val cloudIds = cloudLikes.map { it.id }.toSet()
            val localEntities = db.getAllFavorites()
            val localActiveIds = localEntities.filter { !it.pendingDelete }.map { it.trackId }.toSet()
            val localPendingDeleteIds = localEntities.filter { it.pendingDelete }.map { it.trackId }.toSet()

            // 2. Add cloud likes that are not in local DB (or were pending delete)
            for (cloudTrack in cloudLikes) {
                val existing = db.getByTrackId(cloudTrack.id)
                if (existing == null || existing.pendingDelete) {
                    db.insert(
                        FavoriteTrackEntity(
                            trackId = cloudTrack.id,
                            title = cloudTrack.title,
                            artistName = cloudTrack.artist,
                            albumTitle = null,
                            durationMs = cloudTrack.durationMs,
                            imageUrl = cloudTrack.cover,
                            artistId = cloudTrack.artistId,
                            collectionId = cloudTrack.collectionId,
                            isExplicit = cloudTrack.isExplicit,
                            source = cloudTrack.source,
                            isSynced = true,
                            pendingDelete = false
                        )
                    )
                } else if (!existing.isSynced) {
                    // Local was pending insert, now confirmed by cloud
                    db.markSynced(cloudTrack.id)
                }
            }

            // 3. Remove local likes that were removed on cloud
            // (but not those that are pending delete locally — they need cloud sync first)
            for (localId in localActiveIds) {
                if (localId !in cloudIds) {
                    db.deleteByTrackId(localId)
                }
            }

            // 4. Clear any pending deletes that are already gone from cloud
            for (pendingId in localPendingDeleteIds) {
                if (pendingId !in cloudIds) {
                    db.deleteByTrackId(pendingId)
                }
            }

            // 5. Push pending local changes to cloud
            // Note: ICM API currently does not expose POST/DELETE /library/likes endpoints
            // in the public docs. When they become available, uncomment the code below.
            // For now, the local state is authoritative and cloud sync is pull-only.

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Like a track: immediate local insert, then background cloud sync.
     */
    suspend fun likeTrack(track: Track): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val existing = db.getByTrackId(track.id)
            if (existing != null) {
                if (existing.pendingDelete) {
                    // Was pending delete, restore it
                    db.insert(existing.copy(pendingDelete = false, isSynced = false))
                }
                // Already liked
                return@withContext Result.success(Unit)
            }

            db.insert(
                FavoriteTrackEntity(
                    trackId = track.id,
                    title = track.title,
                    artistName = track.artist,
                    albumTitle = track.albumName.takeIf { it.isNotBlank() },
                    durationMs = track.durationMs,
                    imageUrl = track.coverUrl,
                    artistId = track.artists.firstOrNull()?.id,
                    collectionId = track.albumName.takeIf { it.isNotBlank() },
                    isSynced = false,
                    pendingDelete = false
                )
            )

            // Update PlayerController favorite IDs for reactive UI
            updatePlayerControllerFavorites()

            // TODO: POST /library/likes/{trackId} when API endpoint is available
            // For now, local state is authoritative
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unlike a track: immediate local delete (soft), then background cloud sync.
     */
    suspend fun unlikeTrack(trackId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val existing = db.getByTrackId(trackId) ?: return@withContext Result.success(Unit)

            // Soft delete: mark pendingDelete, actual removal after cloud sync
            db.update(existing.copy(pendingDelete = true, isSynced = false))

            // Update PlayerController favorite IDs for reactive UI
            updatePlayerControllerFavorites()

            // TODO: DELETE /library/likes/{trackId} when API endpoint is available
            // For now, hard delete locally since cloud doesn't support it yet
            db.deleteByTrackId(trackId)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Toggle like status for a track.
     */
    suspend fun toggleFavorite(track: Track): Boolean = withContext(Dispatchers.IO) {
        val isCurrentlyLiked = db.isFavorite(track.id)
        if (isCurrentlyLiked) {
            unlikeTrack(track.id)
            false
        } else {
            likeTrack(track)
            true
        }
    }

    /**
     * Toggle like by track ID only (used from PlayerController when Track object not available).
     */
    suspend fun toggleFavoriteById(trackId: String): Boolean = withContext(Dispatchers.IO) {
        val isCurrentlyLiked = db.isFavorite(trackId)
        if (isCurrentlyLiked) {
            unlikeTrack(trackId)
            false
        } else {
            // Need to fetch track metadata to insert
            // Try to get from current queue first
            val trackFromQueue = PlayerController.queueFlow.value.firstOrNull { it.id == trackId }
            if (trackFromQueue != null) {
                likeTrack(trackFromQueue)
            } else {
                // Minimal insert with just ID — will be enriched on next sync
                db.insert(
                    FavoriteTrackEntity(
                        trackId = trackId,
                        title = "", // Will be enriched on sync
                        artistName = null,
                        isSynced = false
                    )
                )
                updatePlayerControllerFavorites()
            }
            true
        }
    }

    /**
     * Convert local entity to Track for playback.
     */
    private fun FavoriteTrackEntity.toTrack(): Track {
        return Track(
            id = trackId,
            title = title,
            artist = artistName ?: "Unknown Artist",
            albumName = albumTitle ?: "",
            uri = Uri.parse("https://byicloud.online/track/$trackId"),
            durationMs = durationMs,
            albumId = collectionId?.hashCode()?.toLong() ?: trackId.hashCode().toLong(),
            coverUrl = imageUrl,
            artists = emptyList()
        )
    }

    /**
     * Sync PlayerController's in-memory favorite IDs with DB state.
     */
    private suspend fun updatePlayerControllerFavorites() {
        val ids = db.getFavoriteTrackIds()
        PlayerController.setFavoriteIds(ids)
    }

    companion object {
        @Volatile
        private var INSTANCE: LibraryRepository? = null

        fun getInstance(context: Context): LibraryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LibraryRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
