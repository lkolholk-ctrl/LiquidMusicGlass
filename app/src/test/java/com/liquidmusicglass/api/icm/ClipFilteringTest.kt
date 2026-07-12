package com.liquidmusicglass.api.icm

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Видеоклипы Apple-каталога приходят в /search и /wave как «треки» (числовой id,
 * isArtist=false, isAlbum=false), но аудио-стрима у них нет — POST /track даёт
 * 404 track_not_found. Сервер помечает их isClip; клиент обязан их не играть.
 */
class ClipFilteringTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `search item with isClip is not a track`() {
        val item = IcmSearchItem(id = "367614920", title = "Some Video", isClip = true)
        assertFalse(item.isTrack)
    }

    @Test
    fun `plain search item remains a track`() {
        val item = IcmSearchItem(id = "42", title = "Some Song")
        assertTrue(item.isTrack)
    }

    @Test
    fun `isClip parses from server json`() {
        val item = json.decodeFromString<IcmSearchItem>(
            """{"id":"880774576","title":"Video Clip","isClip":true}"""
        )
        assertTrue(item.isClip)
        assertFalse(item.isTrack)
    }

    @Test
    fun `wave track without isClip in json defaults to false`() {
        // Старые/непатченные ответы сервера без поля не должны ронять парс
        // и не должны резать обычные песни.
        val track = json.decodeFromString<IcmWaveTrack>(
            """{"id":"1482275340","title":"Song"}"""
        )
        assertFalse(track.isClip)
    }

    @Test
    fun `wave track isClip parses from server json`() {
        val track = json.decodeFromString<IcmWaveTrack>(
            """{"id":"367614920","title":"Video","isClip":true}"""
        )
        assertTrue(track.isClip)
    }

    @Test
    fun `home item with isClip is not a track`() {
        val item = IcmHomeItem(id = "880774576", title = "Video Clip", isClip = true)
        assertFalse(item.isTrack)
    }
}
