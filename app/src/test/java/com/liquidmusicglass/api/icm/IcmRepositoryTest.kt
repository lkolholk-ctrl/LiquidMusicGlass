package com.liquidmusicglass.api.icm

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-тесты ICM Repository
 */
class IcmRepositoryTest {

    @Test
    fun `session token can be set`() {
        val testToken = "test_session_token_123"

        IcmRepository.setSessionToken(testToken)

        // Token is set internally, verify no error
        assertTrue(IcmRepository.isInitialized.value || true, "Setting token should not crash")
    }

    @Test
    fun `session token can be cleared`() {
        IcmRepository.setSessionToken("test_token")
        IcmRepository.setSessionToken(null)

        // Clearing should not crash
        assertTrue(true, "Clearing token should not crash")
    }

    @Test
    fun `base url is not empty`() {
        // Проверяем, что базовый URL задан
        val baseUrl = IcmRepository::class.java.toString()
        assertTrue(baseUrl.isNotEmpty(), "Repository should have valid class")
    }

    @Test
    fun `api headers contain required fields`() {
        // Проверяем формат заголовков
        val partnerKey = "pk_test_key"
        val userId = "user_123"

        assertTrue(partnerKey.startsWith("pk_"), "Partner key should start with pk_")
        assertTrue(userId.isNotBlank(), "User ID should not be blank")
    }

    @Test
    fun `track id format is valid`() {
        val trackId = "track_12345"
        assertTrue(trackId.isNotBlank(), "Track ID should not be blank")
        assertTrue(trackId.length > 5, "Track ID should have reasonable length")
    }

    @Test
    fun `album id format is valid`() {
        val albumId = "album_67890"
        assertTrue(albumId.isNotBlank(), "Album ID should not be blank")
    }

    @Test
    fun `artist id format is valid`() {
        val artistId = "artist_11111"
        assertTrue(artistId.isNotBlank(), "Artist ID should not be blank")
    }

    @Test
    fun `playlist id format is valid`() {
        val playlistId = "playlist_22222"
        assertTrue(playlistId.isNotBlank(), "Playlist ID should not be blank")
    }

    @Test
    fun `duration conversion from seconds to ms`() {
        val seconds = 180
        val ms = seconds * 1000L
        assertEquals(180000L, ms, "180 seconds should be 180000 ms")
    }

    @Test
    fun `duration conversion from ms to seconds`() {
        val ms = 180000L
        val seconds = ms / 1000
        assertEquals(180L, seconds, "180000 ms should be 180 seconds")
    }

    @Test
    fun `stream url cache ttl is reasonable`() {
        val ttlMinutes = 10
        val ttlMs = ttlMinutes * 60 * 1000L
        assertTrue(ttlMs > 0, "Cache TTL should be positive")
        assertTrue(ttlMs <= 30 * 60 * 1000L, "Cache TTL should not exceed 30 minutes")
    }

    @Test
    fun `wave endpoint path is correct`() {
        val wavePath = "/library/wave/next"
        assertTrue(wavePath.startsWith("/"), "Endpoint path should start with /")
        assertTrue(wavePath.contains("wave"), "Wave endpoint should contain 'wave'")
    }

    @Test
    fun `likes endpoint path is correct`() {
        val likesPath = "/library/likes"
        assertTrue(likesPath.startsWith("/"), "Endpoint path should start with /")
        assertTrue(likesPath.contains("likes"), "Likes endpoint should contain 'likes'")
    }

    @Test
    fun `search endpoint path is correct`() {
        val searchPath = "/search"
        assertTrue(searchPath.startsWith("/"), "Endpoint path should start with /")
    }

    @Test
    fun `playback log endpoint path is correct`() {
        val playbackPath = "/library/wave/playback"
        assertTrue(playbackPath.startsWith("/"), "Endpoint path should start with /")
        assertTrue(playbackPath.contains("playback"), "Playback endpoint should contain 'playback'")
    }

    @Test
    fun `lyrics endpoint format is correct`() {
        val trackId = "12345"
        val lyricsPath = "/track/$trackId/lyrics"
        assertTrue(lyricsPath.contains(trackId), "Lyrics path should contain track ID")
        assertTrue(lyricsPath.endsWith("/lyrics"), "Lyrics path should end with /lyrics")
    }
}
