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

        val candidateList = candidates.toList()

        // Один проход фильтра. relaxed включает «пол бесконечной волны»: снимает
        // анти-повтор и косметику (кулдаун артиста, skip-ratio), оставляя только
        // жёсткое — пустой id, явный дизлайк трека/артиста, заблокир. источник.
        fun runPass(relaxed: Boolean): Result {
            val accepted = mutableListOf<WaveCandidate>()
            val rejected = mutableListOf<Decision>()
            // В жёсткий reject идёт только in-run exclude (markQueued), НЕ засеянная
            // недавняя история (playedSet): персональная волна рекомендует близкое к
            // недавнему, и раньше это вырезалось в ноль. playedSet по-прежнему уходит
            // серверу как мягкий played_track_ids, но клиент им больше не банит.
            val seenTrackIds = LinkedHashSet<String>().apply {
                if (!relaxed) addAll(state.excludeSet)
            }
            val recentArtistKeys = state.recentArtistKeys.takeLast(policy.artistWindowSize).toMutableList()
            var nextState = state

            for (candidate in candidateList) {
                if (accepted.size >= limit) break

                val reason = rejectReason(candidate, nextState, seenTrackIds, recentArtistKeys, statsByTrackId, relaxed)
                if (reason != null) {
                    rejected += Decision(candidate, accepted = false, reason = reason)
                    continue
                }

                accepted += candidate
                seenTrackIds += candidate.normalizedId
                candidate.artistKey?.let { recentArtistKeys += it }
                nextState = nextState.markQueued(candidate)
            }

            return Result(accepted = accepted, rejected = rejected, nextState = nextState)
        }

        val strict = runPass(relaxed = false)
        // Непустая пачка кандидатов НИКОГДА не должна дать 0 принятых — иначе волна
        // «высыхает» и не играет (корневой баг из лога). Если строгий проход всё
        // вырезал — повторяем мягко, сохраняя только явные дизлайки.
        return if (strict.accepted.isEmpty() && candidateList.isNotEmpty()) {
            runPass(relaxed = true)
        } else {
            strict
        }
    }

    private fun rejectReason(
        candidate: WaveCandidate,
        state: WaveSessionState,
        seenTrackIds: Set<String>,
        recentArtistKeys: List<String>,
        statsByTrackId: Map<String, TrackStats>,
        relaxed: Boolean
    ): RejectReason? {
        val id = candidate.normalizedId
        if (id.isBlank()) return RejectReason.BlankId
        // В relaxed seenTrackIds стартует пустым → ловит только повтор ВНУТРИ пачки.
        if (id in seenTrackIds) return RejectReason.DuplicateTrack
        if (id in state.negativeTrackSet) return RejectReason.NegativeTrack

        val source = candidate.source.normalizedKey()
        if (policy.normalizedSources.isNotEmpty() && source !in policy.normalizedSources) {
            return RejectReason.SourceBlocked
        }

        val artistKey = candidate.artistKey
        if (artistKey != null) {
            if (candidate.artistKeys.any { it in state.negativeArtistSet }) return RejectReason.NegativeArtist
            if (!relaxed && recentArtistKeys.count { it == artistKey } >= policy.maxTracksPerArtistWindow) {
                return RejectReason.ArtistCooldown
            }
        }

        if (!relaxed) {
            val stats = statsByTrackId[id] ?: TrackStats(
                playCount = state.playCounts[id] ?: 0,
                skipCount = state.skipCounts[id] ?: 0
            )
            if (stats.total >= policy.minSkipSamples && stats.skipRatio > policy.skipRatioThreshold) {
                return RejectReason.SkipRatio
            }
        }

        return null
    }
}
