package com.liquidmusicglass.data.yandex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YandexTokenExtractorTest {

    @Test
    fun `extracts token from music redirect fragment`() {
        val url = "https://music.yandex.ru/#access_token=y0_AgAAAAA123-abc&token_type=bearer&expires_in=31535645"
        assertEquals("y0_AgAAAAA123-abc", YandexTokenExtractor.accessTokenFrom(url))
    }

    @Test
    fun `extracts token when it is not the first fragment param`() {
        val url = "https://music.yandex.ru/#state=xyz&access_token=y0_token&cid=1"
        assertEquals("y0_token", YandexTokenExtractor.accessTokenFrom(url))
    }

    @Test
    fun `decodes url-encoded token`() {
        val url = "https://music.yandex.ru/#access_token=y0%5Ftoken&token_type=bearer"
        assertEquals("y0_token", YandexTokenExtractor.accessTokenFrom(url))
    }

    @Test
    fun `returns null when fragment has no token`() {
        assertNull(YandexTokenExtractor.accessTokenFrom("https://music.yandex.ru/#token_type=bearer"))
        assertNull(YandexTokenExtractor.accessTokenFrom("https://music.yandex.ru/"))
        assertNull(YandexTokenExtractor.accessTokenFrom("https://oauth.yandex.ru/authorize?response_type=token"))
        assertNull(YandexTokenExtractor.accessTokenFrom(null))
    }

    @Test
    fun `does not match access_token in query instead of fragment`() {
        // Токен по спецификации приходит только во фрагменте; query не трогаем.
        assertNull(YandexTokenExtractor.accessTokenFrom("https://evil.example/?access_token=fake"))
    }

    @Test
    fun `empty token value yields null`() {
        assertNull(YandexTokenExtractor.accessTokenFrom("https://music.yandex.ru/#access_token=&token_type=bearer"))
    }

    @Test
    fun `detects auth error in fragment`() {
        assertTrue(YandexTokenExtractor.isAuthError("https://music.yandex.ru/#error=access_denied&state=1"))
        assertFalse(YandexTokenExtractor.isAuthError("https://music.yandex.ru/#access_token=y0_x"))
        assertFalse(YandexTokenExtractor.isAuthError("https://oauth.yandex.ru/authorize"))
        assertFalse(YandexTokenExtractor.isAuthError(null))
    }
}
