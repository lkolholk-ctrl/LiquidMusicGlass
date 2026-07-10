package com.liquidmusicglass.data.wave

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaveNegativeFeedbackTest {

    @Test
    fun `track dislike aliases map to track candidate`() {
        for (type in listOf("less_track", "dislike-track", "less-like-this", "LESS_TRACK")) {
            val candidate = negativeWaveCandidate(type, "1440831203")
            assertEquals("1440831203", candidate?.id, "type=$type")
            assertNull(candidate?.artistId, "type=$type")
        }
    }

    @Test
    fun `artist dislike aliases map to artist candidate`() {
        for (type in listOf("less_artist", "dislike-artist")) {
            val candidate = negativeWaveCandidate(type, "358714030")
            assertEquals("", candidate?.id, "type=$type")
            assertEquals("358714030", candidate?.artistId, "type=$type")
        }
    }

    @Test
    fun `positive and genre feedback is not banned locally`() {
        for (type in listOf("more_track", "more_artist", "more_genre", "less_genre", "like-track", "skip")) {
            assertNull(negativeWaveCandidate(type, "123"), "type=$type")
        }
    }

    @Test
    fun `blank value produces no candidate`() {
        assertNull(negativeWaveCandidate("less_track", "  "))
        assertNull(negativeWaveCandidate("less_artist", ""))
    }

    @Test
    fun `disliked track is rejected by candidate filter`() {
        val negative = negativeWaveCandidate("less_track", "42")!!
        val state = WaveSessionState().markNegativeFeedback(negative)
        val filter = WaveCandidateFilter()

        val result = filter.filter(
            candidates = listOf(
                WaveCandidate(id = "42", artistId = "a1"),
                WaveCandidate(id = "43", artistId = "a2")
            ),
            state = state
        )

        assertEquals(listOf("43"), result.accepted.map { it.id })
        assertEquals(
            WaveCandidateFilter.RejectReason.NegativeTrack,
            result.rejected.single().reason
        )
    }

    @Test
    fun `disliked artist bans every track of that artist`() {
        val negative = negativeWaveCandidate("dislike-artist", "358714030")!!
        val state = WaveSessionState().markNegativeFeedback(negative)
        val filter = WaveCandidateFilter()

        val result = filter.filter(
            candidates = listOf(
                WaveCandidate(id = "10", artistId = "358714030"),
                WaveCandidate(id = "11", artistId = "358714030"),
                WaveCandidate(id = "12", artistId = "999")
            ),
            state = state
        )

        assertEquals(listOf("12"), result.accepted.map { it.id })
        assertTrue(result.rejected.all {
            it.reason == WaveCandidateFilter.RejectReason.NegativeArtist
        })
    }
}
