package com.liquidmusicglass.data.wave

/**
 * Local guardrail for the expanded wave API. The server returns candidates; the
 * app decides what can enter the real playback queue.
 */
class WaveCandidateFilter(
    private val policy: Policy = Policy()
) {
    data class Policy(
        val allowedSources: Set<String> = emptySet(),
        val maxTracksPerArtistWindow: Int = 2,
        val artistWindowSize: Int = 12,
        val skipRatioThreshold: Double = 0.70,
        val minSkipSamples: Int = 2
    ) {
        init {
            require(maxTracksPerArtistWindow >= 1) { "maxTracksPerArtistWindow must be >= 1" }
            require(artistWindowSize >= 1) { "artistWindowSize must be >= 1" }
            require(skipRatioThreshold in 0.0..1.0) { "skipRatioThreshold must be in 0..1" }
            require(minSkipSamples >= 1) { "minSkipSamples must be >= 1" }
        }

        val normalizedSources: Set<String> = allowedSources.mapNotNull { it.normalizedKey() }.toSet()
    }

    data class TrackStats(
        val playCount: Int = 0,
        val skipCount: Int = 0
    ) {
        val total: Int get() = playCount + skipCount
        val skipRatio: Double get() = if (total == 0) 0.0 else skipCount.toDouble() / total.toDouble()
    }

    enum class RejectReason {
        BlankId,
        DuplicateTrack,
        NegativeTrack,
        NegativeArtist,
        SourceBlocked,
        SkipRatio,
        ArtistCooldown
    }

    data class Decision(
        val candidate: WaveCandidate,
        val accepted: Boolean,
        val reason: RejectReason? = null
    )

    data class Result(
        val accepted: List<WaveCandidate>,
        val rejected: List<Decision>,
        val nextState: WaveSessionState
    )

    fun filter(
        candidates: Iterable<WaveCandidate>,
        state: WaveSessionState,
        statsByTrackId: Map<String, TrackStats> = emptyMap(),
        limit: Int = Int.MAX_VALUE
    ): Result {
        if (limit <= 0) {
            return Result(accepted = emptyList(), rejected = emptyList(), nextState = state)
        }

        val accepted = mutableListOf<WaveCandidate>()
        val rejected = mutableListOf<Decision>()
        val seenTrackIds = LinkedHashSet<String>().apply {
            addAll(state.excludeSet)
            addAll(state.playedSet)
        }
        val recentArtistKeys = state.recentArtistKeys.takeLast(policy.artistWindowSize).toMutableList()
        var nextState = state

        for (candidate in candidates) {
            if (accepted.size >= limit) break

            val reason = rejectReason(candidate, nextState, seenTrackIds, recentArtistKeys, statsByTrackId)
            if (reason != null) {
                rejected += Decision(candidate, accepted = false, reason = reason)
                continue
            }

            accepted += candidate
            seenTrackIds += candidate.normalizedId
            candidate.artistKey?.let { recentArtistKeys += it }
            nextState = nextState.markQueued(candidate)
        }

        return Result(
            accepted = accepted,
            rejected = rejected,
            nextState = nextState
        )
    }

    private fun rejectReason(
        candidate: WaveCandidate,
        state: WaveSessionState,
        seenTrackIds: Set<String>,
        recentArtistKeys: List<String>,
        statsByTrackId: Map<String, TrackStats>
    ): RejectReason? {
        val id = candidate.normalizedId
        if (id.isBlank()) return RejectReason.BlankId
        if (id in seenTrackIds) return RejectReason.DuplicateTrack
        if (id in state.negativeTrackSet) return RejectReason.NegativeTrack

        val source = candidate.source.normalizedKey()
        if (policy.normalizedSources.isNotEmpty() && source !in policy.normalizedSources) {
            return RejectReason.SourceBlocked
        }

        val artistKey = candidate.artistKey
        if (artistKey != null) {
            if (candidate.artistKeys.any { it in state.negativeArtistSet }) return RejectReason.NegativeArtist
            if (recentArtistKeys.count { it == artistKey } >= policy.maxTracksPerArtistWindow) {
                return RejectReason.ArtistCooldown
            }
        }

        val stats = statsByTrackId[id] ?: TrackStats(
            playCount = state.playCounts[id] ?: 0,
            skipCount = state.skipCounts[id] ?: 0
        )
        if (stats.total >= policy.minSkipSamples && stats.skipRatio > policy.skipRatioThreshold) {
            return RejectReason.SkipRatio
        }

        return null
    }
}
