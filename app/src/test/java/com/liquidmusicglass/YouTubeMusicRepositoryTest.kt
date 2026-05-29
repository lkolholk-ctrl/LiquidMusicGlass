package com.liquidmusicglass

import com.liquidmusicglass.api.youtube.YouTubeMusicRepository
import com.liquidmusicglass.api.youtube.YtSearchFilter
import com.liquidmusicglass.api.youtube.models.YouTubeClient
import com.liquidmusicglass.api.youtube.models.YouTubeLocale
import com.liquidmusicglass.api.youtube.models.body.YtSearchBody
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class YouTubeMusicRepositoryTest {

    @Test
    fun testYouTubeMusicSearch() {
        runBlocking {
            println("--- Starting Raw YouTube Music Search Test ---")
        try {
            io.mockk.mockkStatic(android.util.Log::class)
            io.mockk.every { android.util.Log.d(any<String>(), any<String>()) } returns 0
            io.mockk.every { android.util.Log.w(any<String>(), any<String>()) } returns 0
            io.mockk.every { android.util.Log.e(any<String>(), any<String>()) } returns 0
            io.mockk.every { android.util.Log.i(any<String>(), any<String>()) } returns 0

            // 1. Let's make a raw network call via Ktor and see what the response looks like!
            val rawClient = HttpClient {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                        isLenient = true
                    })
                }
            }

            val client = YouTubeClient.WEB_REMIX
            val locale = YouTubeLocale(gl = "RU", hl = "ru")
            val visitorData = "CgtsZG1ySnZiQWtSbyiMjuGSBg%3D%3D"
            val query = "король и шут"
            val params = "EgWKAQIIAWoKEAMQBBAJEAoQBQ=="

            val rawResponse = rawClient.post("https://music.youtube.com/youtubei/v1/search") {
                contentType(ContentType.Application.Json)
                header("X-Goog-Api-Format-Version", "1")
                header("X-YouTube-Client-Name", client.clientName)
                header("X-YouTube-Client-Version", client.clientVersion)
                header("x-origin", "https://music.youtube.com")
                client.referer?.let { header("Referer", it) }
                header("User-Agent", client.userAgent)
                parameter("key", client.apiKey)

                
                setBody(
                    YtSearchBody(
                        context = client.toContext(locale, visitorData),
                        query = query,
                        params = params
                    )
                )
            }.body<String>()

            println("RAW RESPONSE LENGTH: ${rawResponse.length}")
            println("RAW RESPONSE START:")
            println(rawResponse.take(2000))

            // Let's also parse it using Kotlinx Serialization manually and see if it fails!
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
                isLenient = true
            }

            val parsed = json.decodeFromString<com.liquidmusicglass.api.youtube.models.response.YtSearchResponse>(rawResponse)
            println("Successfully parsed YtSearchResponse! Contents is null? ${parsed.contents == null}")
            
            val tabs = parsed.contents?.tabbedSearchResultsRenderer?.tabs
            println("Number of tabs: ${tabs?.size}")
            if (tabs != null && tabs.isNotEmpty()) {
                val tab = tabs[0]
                val sections = tab.tabRenderer?.content?.sectionListRenderer?.contents
                println("Number of sections: ${sections?.size}")
                sections?.forEachIndexed { index, section ->
                    println("Section $index:")
                    println("  musicShelfRenderer: ${section.musicShelfRenderer != null}")
                    println("  itemSectionRenderer: ${section.itemSectionRenderer != null}")
                    println("  musicCardShelfRenderer: ${section.musicCardShelfRenderer != null}")
                    
                    section.musicShelfRenderer?.contents?.forEachIndexed { itemIdx, item ->
                        val renderer = item.musicResponsiveListItemRenderer
                        println("    musicShelfRenderer Item $itemIdx: renderer=${renderer != null}")
                        if (renderer != null) {
                            println("      videoId: ${renderer.playlistItemData?.videoId ?: renderer.navigationEndpoint?.videoId}")
                            println("      flexColumns size: ${renderer.flexColumns?.size}")
                            renderer.flexColumns?.forEachIndexed { colIdx, col ->
                                println("        col $colIdx text: ${col.musicResponsiveListItemFlexColumnRenderer?.text?.text}")
                            }
                        }
                    }
                    
                    section.itemSectionRenderer?.contents?.forEachIndexed { itemIdx, item ->
                        val renderer = item.musicResponsiveListItemRenderer
                        println("    itemSectionRenderer Item $itemIdx: renderer=${renderer != null}")
                        if (renderer != null) {
                            println("      videoId: ${renderer.playlistItemData?.videoId ?: renderer.navigationEndpoint?.videoId}")
                            println("      flexColumns size: ${renderer.flexColumns?.size}")
                            renderer.flexColumns?.forEachIndexed { colIdx, col ->
                                println("        col $colIdx text: ${col.musicResponsiveListItemFlexColumnRenderer?.text?.text}")
                            }
                        }
                    }
                }
            }
            
            println("JVM Default Country: '${java.util.Locale.getDefault().country}'")
            println("JVM Default Language Tag: '${java.util.Locale.getDefault().toLanguageTag()}'")
            
            val repository = YouTubeMusicRepository.getInstance()
            val result = repository.search("король и шут", YtSearchFilter.SONGS)
            if (result.isSuccess) {
                val tracks = result.getOrThrow()
                println("Repository search success! Found ${tracks.size} tracks")
            } else {
                println("Repository search failed with exception: ${result.exceptionOrNull()?.message}")
                result.exceptionOrNull()?.printStackTrace()
            }


        } catch (e: Throwable) {
            println("Caught exception in test:")
            e.printStackTrace()
            throw e
        }
        }
    }
}
