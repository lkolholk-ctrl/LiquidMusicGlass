package com.liquidmusicglass.api.icm

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.Serializable

@Serializable
data class IcmErrorWrapper(
    val detail: IcmError
)

class ImportJobParsingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun testInitial202ResponseParsing() {
        val body = """
            {
                "job_id": "p1a2b3c4d5e6f708",
                "status": "pending",
                "poll_url": "https://byicloud.online/api/partner/me/playlists/import/p1a2b3c4d5e6f708",
                "poll_after": 3
            }
        """.trimIndent()
        val response = json.decodeFromString<IcmPlaylistImportResponse>(body)
        assertEquals("p1a2b3c4d5e6f708", response.jobId)
        assertEquals("pending", response.status)
        assertEquals("https://byicloud.online/api/partner/me/playlists/import/p1a2b3c4d5e6f708", response.pollUrl)
        assertEquals(3, response.pollAfter)
        assertEquals(null, response.playlistId)
    }

    @Test
    fun testPendingResponseParsing() {
        val body = """
            {
                "status": "pending",
                "progress": {
                    "total": 100,
                    "matched": 45,
                    "failed": 2
                }
            }
        """.trimIndent()
        val response = json.decodeFromString<IcmPlaylistImportJobResponse>(body)
        assertEquals("pending", response.status)
        assertNotNull(response.progress)
        assertEquals(100, response.progress.total)
        assertEquals(45, response.progress.matched)
        assertEquals(2, response.progress.failed)
    }

    @Test
    fun testReadyResponseParsing() {
        val body = """
            {
                "status": "ready",
                "playlist_id": 1042,
                "total": 100,
                "matched": 95,
                "failed": 5
            }
        """.trimIndent()
        val response = json.decodeFromString<IcmPlaylistImportJobResponse>(body)
        assertEquals("ready", response.status)
        assertEquals("1042", response.playlistId)
        assertEquals(100, response.total)
        assertEquals(95, response.matched)
        assertEquals(5, response.failed)
    }

    @Test
    fun testFailedResponseParsing() {
        val body = """
            {
                "status": "failed",
                "error": "source_api_error",
                "message": "Yandex returned 502 after retries"
            }
        """.trimIndent()
        val response = json.decodeFromString<IcmPlaylistImportJobResponse>(body)
        assertEquals("failed", response.status)
        assertEquals("source_api_error", response.error)
        assertEquals("Yandex returned 502 after retries", response.message)
    }

    @Test
    fun testErrorParsingWithDetailWrapper() {
        val errorText = """{"detail":{"error":"job_not_found_or_expired"}}"""
        
        // Parse with wrapper
        val error = try {
            json.decodeFromString<IcmErrorWrapper>(errorText).detail
        } catch (e: Exception) {
            try {
                json.decodeFromString<IcmError>(errorText)
            } catch (e2: Exception) {
                null
            }
        }
        
        assertNotNull(error)
        assertEquals("job_not_found_or_expired", error.error)
    }

    @Test
    fun testUserPlaylistTrackParsingVariants() {
        // Variant 1: standard trackId
        val body1 = """
            {
                "trackId": "1440831203",
                "title": "Believer",
                "artist": "Imagine Dragons",
                "cover": "https://example.com/cover.jpg",
                "duration": 204347,
                "collectionId": "1440831190",
                "position": 1
            }
        """.trimIndent()
        val track1 = json.decodeFromString<IcmUserPlaylistTrack>(body1)
        assertEquals("1440831203", track1.trackId)
        assertEquals("Believer", track1.title)

        // Variant 2: id
        val body2 = """
            {
                "id": "1440831203",
                "title": "Believer",
                "artist": "Imagine Dragons",
                "cover": "https://example.com/cover.jpg",
                "duration": 204347,
                "collectionId": "1440831190",
                "position": 1
            }
        """.trimIndent()
        val track2 = json.decodeFromString<IcmUserPlaylistTrack>(body2)
        assertEquals("1440831203", track2.trackId)

        // Variant 3: track_id
        val body3 = """
            {
                "track_id": "1440831203",
                "title": "Believer",
                "artist": "Imagine Dragons",
                "cover": "https://example.com/cover.jpg",
                "duration": 204347,
                "collectionId": "1440831190",
                "position": 1
            }
        """.trimIndent()
        val track3 = json.decodeFromString<IcmUserPlaylistTrack>(body3)
        assertEquals("1440831203", track3.trackId)

        // Variant 4: numeric id
        val body4 = """
            {
                "id": 1440831203,
                "title": "Believer",
                "artist": "Imagine Dragons",
                "cover": "https://example.com/cover.jpg",
                "duration": 204347,
                "collectionId": "1440831190",
                "position": 1
            }
        """.trimIndent()
        val track4 = json.decodeFromString<IcmUserPlaylistTrack>(body4)
        assertEquals("1440831203", track4.trackId)
    }

    @Test
    fun testPlaylistImportTrackParsingVariants() {
        val body = """
            {
                "id": "1440831203",
                "title": "Believer",
                "artist": "Imagine Dragons",
                "cover": "https://example.com/cover.jpg",
                "collectionId": "1440831190",
                "duration": 204347,
                "match_score": 0.96
            }
        """.trimIndent()
        val track = json.decodeFromString<IcmPlaylistImportTrack>(body)
        assertEquals("1440831203", track.trackId)
        assertEquals("Believer", track.title)
        assertEquals(0.96, track.matchScore)
    }
}
