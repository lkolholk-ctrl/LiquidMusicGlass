package com.liquidmusicglass.data.wave

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveCandidateFilterTest {

    @Test
    fun `filter hard-rejects in-run excluded tracks but not seeded recent history`() {
        val filter = WaveCandidateFilter()
        // excludeIds = in-run anti-repeat (hard reject). playedIds = the soft
        // played_track_ids signal sent to the server — NOT a client hard-reject:
        // personal wave legitimately returns tracks close to recent listening, and
        // banning them client-side reduced whole 200-batches to zero (the wave-loop bug).
        val state = WaveSessionState(excludeIds = listOf("1"), playedIds = listOf("2"))

        val result = filter.filter(
            candidates = listOf(
                WaveCandidate(id = "1", artistId = "a"),
                WaveCandidate(id = "2", artistId = "b"),
                WaveCandidate(id = "3", artistId = "c")
            ),
            state = state
        )

        // "1" is in the in-run exclude set → DuplicateTrack. "2" (recent history)
        // is now allowed. "3" is new.
        assertEquals(listOf("2", "3"), result.accepted.map { it.id })
        assertEquals(
            listOf(WaveCandidateFilter.RejectReason.DuplicateTrack),
            result.rejected.map { it.reason }
        )
        assertTrue("3" in result.nextState.excludeIds)
    }

    @Test
    fun `filter never empties a non-empty batch (infinite-wave floor)`() {
        val filter = WaveCandidateFilter()
        // Every candidate would be a DuplicateTrack under the strict pass — the
        // relaxation floor must still yield playable tracks so the wave never dies.
        val state = WaveSessionState(excludeIds = listOf("1", "2"))

        val result = filter.filter(
            candidates = listOf(
                WaveCandidate(id = "1", artistId = "a"),
                WaveCandidate(id = "2", artistId = "b")
            ),
            state = state
        )

        assertTrue(result.accepted.isNotEmpty())
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
    fun `filter rejects negative artist by display name when id differs`() {
        val filter = WaveCandidateFilter()
        val state = WaveSessionState(negativeArtistKeys = listOf("kishlak"))

        val result = filter.filter(
            candidates = listOf(
                WaveCandidate(id = "12", artistId = "123456", artistName = "Kishlak"),
                WaveCandidate(id = "13", artistId = "654321", artistName = "Other")
            ),
            state = state
        )

        assertEquals(listOf("13"), result.accepted.map { it.id })
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
