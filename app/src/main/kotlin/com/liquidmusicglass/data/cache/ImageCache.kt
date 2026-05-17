package com.liquidmusicglass.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Disk cache for album artwork.
 * Images are stored in app cache directory with MD5-hashed filenames.
 */
object ImageCache {

    private const val CACHE_DIR = "image_cache"
    private const val MAX_CACHE_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB

    private fun getCacheDir(context: Context): File {
        return File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
    }

    private fun hashUrl(url: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Get cached bitmap or null if not cached.
     */
    fun get(context: Context, url: String): Bitmap? {
        val file = File(getCacheDir(context), hashUrl(url))
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Download and cache image from URL.
     */
    suspend fun put(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.doInput = true
            connection.connect()

            val input = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(input)
            input.close()
            connection.disconnect()

            bitmap?.let { bmp ->
                val file = File(getCacheDir(context), hashUrl(url))
                FileOutputStream(file).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                trimCache(context)
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get bitmap from cache or download it.
     */
    suspend fun getOrPut(context: Context, url: String): Bitmap? {
        get(context, url)?.let { return it }
        return put(context, url)
    }

    /**
     * Remove oldest files if cache exceeds max size.
     */
    private fun trimCache(context: Context) {
        val dir = getCacheDir(context)
        val files = dir.listFiles() ?: return
        val totalSize = files.sumOf { it.length() }
        if (totalSize <= MAX_CACHE_SIZE_BYTES) return

        val sorted = files.sortedBy { it.lastModified() }
        var toDelete = totalSize - MAX_CACHE_SIZE_BYTES * 3 / 4
        for (file in sorted) {
            if (toDelete <= 0) break
            toDelete -= file.length()
            file.delete()
        }
    }

    fun clear(context: Context) {
        getCacheDir(context).listFiles()?.forEach { it.delete() }
    }
}
