package com.liquidmusicglass.data.wave

/**
 * Client-side memory for a wave run. The server can keep learning from
 * feedback, but this state remains the app's source of truth for anti-repeat,
 * local dislikes, and artist cooldowns.
 */
data class WaveSessionState(
    val mode: WaveMode = WaveMode.Personal(),
    val sessionId: String? = null,
    val excludeIds: List<String> = emptyList(),
    val playedIds: List<String> = emptyList(),
    val recentArtistKeys: List<String> = emptyList(),
    val negativeTrackIds: List<String> = emptyList(),
    val negativeArtistKeys: List<String> = emptyList(),
    val playCounts: Map<String, Int> = emptyMap(),
    val skipCounts: Map<String, Int> = emptyMap(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val excludeSet: Set<String> by lazy { excludeIds.mapNotNull { it.normalizedIdKey() }.toSet() }
    val playedSet: Set<String> by lazy { playedIds.mapNotNull { it.normalizedIdKey() }.toSet() }
    val negativeTrackSet: Set<String> by lazy { negativeTrackIds.mapNotNull { it.normalizedIdKey() }.toSet() }
    val negativeArtistSet: Set<String> by lazy { negativeArtistKeys.mapNotNull { it.normalizedKey() }.toSet() }

    fun withMode(mode: WaveMode): WaveSessionState =
        copy(mode = mode, updatedAtMs = System.currentTimeMillis())

    fun withSessionId(sessionId: String?): WaveSessionState =
        copy(sessionId = sessionId?.takeIf { it.isNotBlank() }, updatedAtMs = System.currentTimeMillis())

    fun markQueued(candidate: WaveCandidate): WaveSessionState {
        val id = candidate.normalizedId
        if (id.isBlank()) return this
        return copy(
            excludeIds = excludeIds.appendDistinctCapped(id, MAX_EXCLUDE_IDS),
            recentArtistKeys = candidate.artistKey
                ?.let { recentArtistKeys.appendCapped(it, MAX_RECENT_ARTISTS) }
                ?: recentArtistKeys,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun markQueued(candidates: Iterable<WaveCandidate>): WaveSessionState =
        candidates.fold(this) { state, candidate -> state.markQueued(candidate) }

    fun markPlayed(candidate: WaveCandidate): WaveSessionState {
        val id = candidate.normalizedId
        if (id.isBlank()) return this
        return copy(
            playedIds = playedIds.appendDistinctCapped(id, MAX_PLAYED_IDS),
            excludeIds = excludeIds.appendDistinctCapped(id, MAX_EXCLUDE_IDS),
            recentArtistKeys = candidate.artistKey
                ?.let { recentArtistKeys.appendCapped(it, MAX_RECENT_ARTISTS) }
                ?: recentArtistKeys,
            playCounts = playCounts.incrementCapped(id, MAX_TRACK_STATS),
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun markSkipped(candidate: WaveCandidate): WaveSessionState {
        val id = candidate.normalizedId
        if (id.isBlank()) return this
        return copy(
            playedIds = playedIds.appendDistinctCapped(id, MAX_PLAYED_IDS),
            excludeIds = excludeIds.appendDistinctCapped(id, MAX_EXCLUDE_IDS),
            recentArtistKeys = candidate.artistKey
                ?.let { recentArtistKeys.appendCapped(it, MAX_RECENT_ARTISTS) }
                ?: recentArtistKeys,
            skipCounts = skipCounts.incrementCapped(id, MAX_TRACK_STATS),
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun markNegativeFeedback(candidate: WaveCandidate): WaveSessionState {
        val id = candidate.normalizedId
        return copy(
            negativeTrackIds = if (id.isNotBlank()) {
                negativeTrackIds.appendDistinctCapped(id, MAX_NEGATIVE_IDS)
            } else {
                negativeTrackIds
            },
            negativeArtistKeys = candidate.artistKey
                ?.let { negativeArtistKeys.appendDistinctCapped(it, MAX_NEGATIVE_IDS) }
                ?: negativeArtistKeys,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    companion object {
        const val MAX_EXCLUDE_IDS = 600
        const val MAX_PLAYED_IDS = 600
        const val MAX_RECENT_ARTISTS = 40
        const val MAX_NEGATIVE_IDS = 200
        const val MAX_TRACK_STATS = 600
    }
}

private fun List<String>.appendDistinctCapped(value: String, maxSize: Int): List<String> {
    val clean = value.normalizedIdKey() ?: return this
    return (this.filterNot { it == clean } + clean).takeLast(maxSize)
}

private fun List<String>.appendCapped(value: String, maxSize: Int): List<String> {
    val clean = value.normalizedKey() ?: return this
    return (this + clean).takeLast(maxSize)
}

private fun Map<String, Int>.incrementCapped(key: String, maxSize: Int): Map<String, Int> {
    val clean = key.normalizedIdKey() ?: return this
    val next = LinkedHashMap<String, Int>(this)
    next[clean] = (next[clean] ?: 0) + 1
    while (next.size > maxSize) {
        val first = next.keys.firstOrNull() ?: break
        next.remove(first)
    }
    return next
}
