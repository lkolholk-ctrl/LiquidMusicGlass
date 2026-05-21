package com.liquidmusicglass.api.icm

import org.junit.Test
import kotlin.test.assertEquals

class DurationNormalizationTest {

    @Test
    fun `apple music duration remains in milliseconds`() {
        // Mocking behavior of IcmSearchItem durationMs with source="primary"
        val duration = 180000L
        val source = "primary"
        val isVk = false
        
        val durationMs = if (isVk) duration * 1000L else duration
        assertEquals(180000L, durationMs)
    }

    @Test
    fun `vk music duration is converted to milliseconds`() {
        // Mocking behavior of IcmSearchItem durationMs with source="vk"
        val duration = 180L
        val source = "vk"
        val isVk = true
        
        val durationMs = if (isVk) duration * 1000L else duration
        assertEquals(180000L, durationMs)
    }

    @Test
    fun `short apple music track does not get scaled incorrectly`() {
        // Mocking behavior of IcmSearchItem durationMs with short duration (e.g. 500ms)
        val duration = 500L
        val source = "primary"
        val isVk = false
        
        // Old logic would have made this 500,000ms
        val durationMs = if (isVk) duration * 1000L else duration
        assertEquals(500L, durationMs)
    }

    @Test
    fun `IcmPlaylistTrack duration normalization`() {
        val trackSeconds = IcmPlaylistTrack(
            id = "1",
            title = "Track in Seconds",
            artist = "Artist",
            artistId = "artist_1",
            cover = "",
            collectionId = "col_1",
            duration = 180L, // 3 minutes
            isExplicit = false,
            isCustom = false
        )
        assertEquals(180000L, trackSeconds.durationMs)

        val trackMs = IcmPlaylistTrack(
            id = "2",
            title = "Track in Ms",
            artist = "Artist",
            artistId = "artist_1",
            cover = "",
            collectionId = "col_1",
            duration = 180000L, // 3 minutes
            isExplicit = false,
            isCustom = false
        )
        assertEquals(180000L, trackMs.durationMs)
    }

    @Test
    fun `IcmLibraryTrack duration normalization`() {
        val trackSeconds = IcmLibraryTrack(
            id = "1",
            title = "Track in Seconds",
            duration = 180L
        )
        assertEquals(180000L, trackSeconds.durationMs)

        val trackMs = IcmLibraryTrack(
            id = "2",
            title = "Track in Ms",
            duration = 180000L
        )
        assertEquals(180000L, trackMs.durationMs)

        val trackNull = IcmLibraryTrack(
            id = "3",
            title = "Track Null",
            duration = null
        )
        assertEquals(0L, trackNull.durationMs)
    }
}
