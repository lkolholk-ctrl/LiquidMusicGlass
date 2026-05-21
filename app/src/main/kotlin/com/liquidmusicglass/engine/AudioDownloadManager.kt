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
        val file = File(context.filesDir, "downloads/$trackId$ext")
        if (file.exists()) {
            file.delete()
        }
        db.deleteDownloaded(trackId)
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
