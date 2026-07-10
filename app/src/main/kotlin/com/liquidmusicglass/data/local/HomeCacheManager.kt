package com.liquidmusicglass.data.local

import android.content.Context
import android.content.SharedPreferences
import com.liquidmusicglass.api.icm.IcmHomeBlock
import com.liquidmusicglass.api.icm.IcmHomeItem
import com.liquidmusicglass.api.icm.IcmHomeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Simple JSON-based cache for Home screen content.
 * Stores in SharedPreferences (survives app updates, no schema migrations needed).
 * 
 * Why not Room: Home content is ephemeral (refreshed frequently),
 * and we want zero migration overhead across app updates.
 */
object HomeCacheManager {

    private const val PREFS_NAME = "home_cache"
    private const val KEY_BLOCKS = "blocks_json"
    private const val KEY_TIMESTAMP = "cached_at"
    private const val KEY_ETAG = "etag"
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Save home response to cache.
     */
    suspend fun save(response: IcmHomeResponse) = withContext(Dispatchers.IO) {
        val p = prefs ?: return@withContext
        val json = JSONObject().apply {
            put("blocks", JSONArray().apply {
                response.blocks.forEach { block ->
                    put(JSONObject().apply {
                        put("id", block.id)
                        put("title", block.title)
                        put("type", block.type)
                        put("items", JSONArray().apply {
                            block.items.forEach { item ->
                                put(JSONObject().apply {
                                    put("id", item.id)
                                    put("title", item.title)
                                    put("artist", item.artist)
                                    put("artistId", item.artistId ?: JSONObject.NULL)
                                    put("cover", item.cover ?: JSONObject.NULL)
                                    put("duration", item.duration ?: JSONObject.NULL)
                                    put("source", item.source ?: JSONObject.NULL)
                                    put("collectionId", item.collectionId ?: JSONObject.NULL)
                                    put("album", item.album ?: JSONObject.NULL)
                                    put("genre", item.genre ?: JSONObject.NULL)
                                })
                            }
                        })
                    })
                }
            })
        }
        p.edit().apply {
            putString(KEY_BLOCKS, json.toString())
            putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            apply()
        }
    }

    /**
     * Load cached home response.
     * @return Cached response or null if expired/missing.
     */
    suspend fun load(): IcmHomeResponse? = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val p = prefs ?: return@withContext null
            val jsonStr = p.getString(KEY_BLOCKS, null) ?: return@withContext null
            val cachedAt = p.getLong(KEY_TIMESTAMP, 0)
            if (System.currentTimeMillis() - cachedAt > CACHE_TTL_MS) return@withContext null

            val json = JSONObject(jsonStr)
            val blocksArray = json.getJSONArray("blocks")
            val blocks = mutableListOf<IcmHomeBlock>()
            
            for (i in 0 until blocksArray.length()) {
                val blockObj = blocksArray.getJSONObject(i)
                val itemsArray = blockObj.getJSONArray("items")
                val items = mutableListOf<IcmHomeItem>()
                
                for (j in 0 until itemsArray.length()) {
                    val itemObj = itemsArray.getJSONObject(j)
                    items.add(IcmHomeItem(
                        id = itemObj.getString("id"),
                        title = itemObj.getString("title"),
                        artist = itemObj.optString("artist", ""),
                        artistId = itemObj.optString("artistId", null)?.takeIf { it != "null" },
                        cover = itemObj.optString("cover", null)?.takeIf { it != "null" },
                        duration = itemObj.optString("duration", null)
                            ?.takeIf { it != "null" && it.isNotBlank() }
                            ?.toLongOrNull(),
                        source = itemObj.optString("source", null)?.takeIf { it != "null" },
                        collectionId = itemObj.optString("collectionId", null)?.takeIf { it != "null" },
                        album = itemObj.optString("album", null)?.takeIf { it != "null" },
                        genre = itemObj.optString("genre", null)?.takeIf { it != "null" }
                    ))
                }
                
                blocks.add(IcmHomeBlock(
                    id = blockObj.getString("id"),
                    title = blockObj.getString("title"),
                    type = blockObj.getString("type"),
                    items = items
                ))
            }
            
            IcmHomeResponse(blocks = blocks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("HomeCacheManager", "Failed to load home cache", e)
            null
        } finally {
            android.util.Log.d("HomeCacheManager", "load finished in ${System.currentTimeMillis() - startedAt}ms")
        }
    }

    /**
     * Check if cache is fresh (not expired).
     */
    fun isFresh(): Boolean {
        val p = prefs ?: return false
        val cachedAt = p.getLong(KEY_TIMESTAMP, 0)
        return cachedAt > 0 && (System.currentTimeMillis() - cachedAt) < CACHE_TTL_MS
    }

    /**
     * Clear cache.
     */
    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }
}
