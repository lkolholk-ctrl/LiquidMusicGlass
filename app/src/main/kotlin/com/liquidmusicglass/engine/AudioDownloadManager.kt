package com.liquidmusicglass.engine

import android.content.Context
import com.liquidmusicglass.data.local.db.DownloadedTrackEntity
import com.liquidmusicglass.data.local.db.FavoriteTrackDatabase
import com.liquidmusicglass.api.icm.IcmAuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Singleton manager to coordinate offline audio downloading.
 * Respects strict premium boundaries enforced by aggregator rules.
 */
object AudioDownloadManager {

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress

    fun isDownloading(trackId: String): Boolean {
        return _downloadProgress.value.containsKey(trackId)
    }

    fun getDownloadProgressValue(trackId: String): Float? {
        return _downloadProgress.value[trackId]
    }

    fun downloadTrack(context: Context, track: Track, onComplete: (Boolean) -> Unit = {}) {
        // Enforce aggregator rule: PREMIUM ONLY
        if (!IcmAuthRepository.isPremium.value) {
            onComplete(false)
            return
        }

        // Сотовый гейтинг: загрузки по сотовой выключены → качаем только по Wi-Fi.
        if (!PlayerSettings.downloadsAllowed()) {
            android.util.Log.d("AudioDownloadManager", "Download blocked: cellular downloads off")
            onComplete(false)
            return
        }

        val trackId = track.id
        if (isDownloading(trackId)) return

        val db = FavoriteTrackDatabase.getInstance(context)
        if (db.isDownloaded(trackId)) {
            onComplete(true)
            return
        }

        // Determine file extension from quality
        val quality = IcmAuthRepository.maxQuality.value ?: "256K"
        val ext = if (quality.uppercase() == "ALAC") ".m4a" else ".mp3"

        CoroutineScope(Dispatchers.IO).launch {
            updateProgress(trackId, 0.0f)
            val success = performDownload(context, track, ext)
            if (success) {
                val downloadsDir = File(context.filesDir, "downloads")
                val finalFile = File(downloadsDir, "$trackId$ext")
                db.insertDownloaded(
                    DownloadedTrackEntity(
                        trackId = trackId,
                        title = track.title,
                        artistName = track.artist,
                        albumTitle = track.albumName,
                        durationMs = track.durationMs,
                        imageUrl = track.coverUrl,
                        localPath = finalFile.absolutePath,
                        localCoverPath = null, // Single-track download doesn't cache cover locally yet
                        quality = quality
                    )
                )
                updateProgress(trackId, null) // remove from active downloading map
                onComplete(true)
            } else {
                updateProgress(trackId, null)
                onComplete(false)
            }
        }
    }

    fun deleteDownloadedTrack(context: Context, trackId: String) {
        val db = FavoriteTrackDatabase.getInstance(context)
        val entity = db.getDownloadedTracks().find { it.trackId == trackId }
        val ext = if (entity?.quality?.uppercase() == "ALAC") ".m4a" else ".mp3"

        // Delete physical audio file
        val audioFile = File(context.filesDir, "downloads/$trackId$ext")
        if (audioFile.exists()) {
            audioFile.delete()
        }

        // Delete physical cover art file if it exists
        entity?.localCoverPath?.let { coverPath ->
            val coverFile = File(coverPath)
            if (coverFile.exists()) {
                coverFile.delete()
            }
        }

        // Remove from database
        db.deleteDownloaded(trackId)
    }

    /**
     * Clears ALL downloaded tracks from both the database and the file system.
     * Deletes everything in Downloads/LiquidMusicGlass/ including the .covers/ folder.
     * Runs on Dispatchers.IO to avoid ANR when deleting thousands of files.
     */
    suspend fun clearAllDownloads(context: Context) = withContext(Dispatchers.IO) {
        val db = FavoriteTrackDatabase.getInstance(context)

        // 1. Delete all physical files in the public Downloads/LiquidMusicGlass directory
        val publicDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "LiquidMusicGlass"
        )
        if (publicDir.exists()) {
            publicDir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    // Recursively delete subdirectories (e.g., .covers/)
                    file.listFiles()?.forEach { it.delete() }
                }
                file.delete()
            }
            // Delete the directory itself if empty
            publicDir.delete()
        }

        // 2. Delete all physical files in the private app downloads directory
        val privateDir = File(context.filesDir, "downloads")
        if (privateDir.exists()) {
            privateDir.listFiles()?.forEach { it.delete() }
        }

        // 3. Clear the database table
        db.clearAllDownloads()
    }

    private suspend fun performDownload(context: Context, track: Track, ext: String): Boolean = withContext(Dispatchers.IO) {
        val trackId = track.id
        val tempFile = File(context.filesDir, "downloads/${trackId}.temp")

        android.util.Log.d("DOWNLOAD", "performDownload START trackId=$trackId ext=$ext")

        try {
            val downloadsDir = File(context.filesDir, "downloads")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            if (tempFile.exists()) {
                tempFile.delete()
            }

            // 1. Resolve signed streaming URL
            val resolvedUri = PlayerController.resolveStreamUrlSync(trackId)
            android.util.Log.d("DOWNLOAD", "resolvedUri=$resolvedUri")
            if (resolvedUri == null) {
                android.util.Log.e("DOWNLOAD", "resolveStreamUrlSync returned null for $trackId")
                return@withContext false
            }
            val urlString = resolvedUri.toString()
            android.util.Log.d("DOWNLOAD", "urlString=$urlString")

            // 2. Open HTTP connection and download
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext false
            }

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(tempFile)

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int
            while (inputStream.read(data).also { count = it } != -1) {
                total += count
                if (fileLength > 0) {
                    updateProgress(trackId, total.toFloat() / fileLength)
                }
                outputStream.write(data, 0, count)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            connection.disconnect()

            // 3. Move temp file to final location
            val finalFile = File(downloadsDir, "$trackId$ext")
            if (finalFile.exists()) {
                finalFile.delete()
            }
            val renamed = tempFile.renameTo(finalFile)
            android.util.Log.d("DOWNLOAD", "Download complete trackId=$trackId finalFile=${finalFile.absolutePath} size=${finalFile.length()} renamed=$renamed")
            renamed
        } catch (e: Exception) {
            android.util.Log.e("DOWNLOAD", "Download failed trackId=$trackId error=${e.message}")
            e.printStackTrace()
            if (tempFile.exists()) {
                tempFile.delete()
            }
            false
        }
    }

    private fun updateProgress(trackId: String, progress: Float?) {
        val current = _downloadProgress.value.toMutableMap()
        if (progress == null) {
            current.remove(trackId)
        } else {
            current[trackId] = progress
        }
        _downloadProgress.value = current
    }
}
