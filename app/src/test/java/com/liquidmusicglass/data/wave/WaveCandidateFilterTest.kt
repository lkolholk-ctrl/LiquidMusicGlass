package com.liquidmusicglass.data.wave

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveCandidateFilterTest {

    @Test
    fun `filter rejects tracks already present in session state`() {
        val filter = WaveCandidateFilter()
        val state = WaveSessionState(excludeIds = listOf("1"), playedIds = listOf("2"))

        val result = filter.filter(
            candidates = listOf(
                WaveCandidate(id = "1", artistId = "a"),
                WaveCandidate(id = "2", artistId = "b"),
                WaveCandidate(id = "3", artistId = "c")
            ),
            state = state
        )

        assertEquals(listOf("3"), result.accepted.map { it.id })
        assertEquals(
            listOf(
                WaveCandidateFilter.RejectReason.DuplicateTrack,
                WaveCandidateFilter.RejectReason.DuplicateTrack
            ),
            result.rejected.map { it.reason }
        )
        assertTrue("3" in result.nextState.excludeIds)
    }

    @Test
    fun `filter rejects negative artists before candidates reach queue`() {
        val filter = WaveCandidateFilter()
        val state = WaveSessionState(negativeArtistKeys = listOf("artist_9"))

        val result = filter.filter(
            candidates = listOf(
                WaveCandidate(id = "10", artistId = "artist_9"),
                WaveCandidate(id = "11", artistId = "artist_10")
            ),
            state = state
        )

        assertEquals(listOf("11"), result.accepted.map { it.id })
        assertEquals(WaveCandidateFilter.RejectReason.NegativeArtist, result.rejected.single().reason)
    }

    @Test
    fun `filter rejects tracks with high local skip ratio`() {
        val filter = WaveCandidateFilter()

        val result = filter.filter(
            candidates = listOf(
                WaveCandidate(id = "20", artistId = "a"),
                WaveCandidate(id = "21", artistId = "b")
            ),
            state = WaveSessionState(),
            statsByTrackId = mapOf(
                "20" to WaveCandidateFilter.TrackStats(playCount = 0, skipCount = 3),
                "21" to WaveCandidateFilter.TrackStats(playCount = 3, skipCount = 1)
            )
        )

        assertEquals(listOf("21"), result.accepted.map { it.id })
        assertEquals(WaveCandidateFilter.RejectReason.SkipRatio, result.rejected.single().reason)
    }

    @Test
    fun `filter limits repeated artists inside recent artist window`() {
        val filter = WaveCandidateFilter(
            WaveCandidateFilter.Policy(maxTracksPerArtistWindow = 2, artistWindowSize = 8)
        )
        val state = WaveSessionState(recentArtistKeys = listOf("same_artist"))

        val result = filter.filter(
            candidates = listOf(
                WaveCandidate(id = "30", artistId = "same_artist"),
                WaveCandidate(id = "31", artistId = "same_artist"),
                WaveCandidate(id = "32", artistId = "other_artist")
            ),
            state = state
        )

        assertEquals(listOf("30", "32"), result.accepted.map { it.id })
        assertEquals(WaveCandidateFilter.RejectReason.ArtistCooldown, result.rejected.single().reason)
    }
}
