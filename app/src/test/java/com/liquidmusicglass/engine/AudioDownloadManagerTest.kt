package com.liquidmusicglass.engine

import android.content.Context
import android.net.Uri
import com.liquidmusicglass.api.icm.IcmAuthRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioDownloadManagerTest {

    private val mockContext = mockk<Context>(relaxed = true)
    
    // Lazy initialized so it runs after Uri static mocking is set up
    private val mockTrack by lazy {
        Track(
            id = "test_track_123",
            title = "Test Track",
            artist = "Test Artist",
            albumName = "Test Album",
            uri = Uri.parse("https://byicloud.online/stream/123"),
            durationMs = 180000L,
            albumId = 456L
        )
    }

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        // Clear premium state before each test
        IcmAuthRepository.setPremium(false)
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
        IcmAuthRepository.setPremium(false)
    }

    @Test
    fun `downloadTrack returns false when user is not premium`() {
        var completed = false
        var resultSuccess = false

        AudioDownloadManager.downloadTrack(mockContext, mockTrack) { success ->
            resultSuccess = success
            completed = true
        }

        assertTrue(completed, "Callback should be called immediately")
        assertFalse(resultSuccess, "Download should fail for non-premium users")
        assertFalse(AudioDownloadManager.isDownloading("test_track_123"), "Should not be added to downloading queue")
    }

    @Test
    fun `isDownloading returns false for untracked tracks`() {
        assertFalse(AudioDownloadManager.isDownloading("unknown_id"))
        assertEquals(null, AudioDownloadManager.getDownloadProgressValue("unknown_id"))
    }
}
