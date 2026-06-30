package com.liquidmusicglass.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException

/**
 * Custom DataSource for LiquidMusicGlass.
 *
 * Supports URI schemes:
 * - `liquid://track?id=TRACK_ID&url=REAL_URL` — online track with ID (automatically checks local premium offline cache)
 * - `file://...` — local file
 * - `http://...` / `https://...` — direct URL
 *
 * Automatically resolves offline downloads if the user has a valid Premium subscription.
 */
@OptIn(UnstableApi::class)
class StreamingDataSource private constructor(
    private val context: Context,
    private val httpDataSource: DataSource,
    private val fileDataSource: DataSource,
    private val cacheDataSource: DataSource?
) : DataSource {

    private var currentDataSource: DataSource? = null
    private var currentUri: Uri? = null
    private var transferListener: TransferListener? = null

    override fun addTransferListener(transferListener: TransferListener) {
        this.transferListener = transferListener
        httpDataSource.addTransferListener(transferListener)
        fileDataSource.addTransferListener(transferListener)
        cacheDataSource?.addTransferListener(transferListener)
    }

    companion object {
        const val SCHEME_LIQUID = "liquid"
        const val PARAM_TRACK_ID = "id"
        const val PARAM_URL = "url"

        fun create(
            context: Context,
            httpDataSource: DefaultHttpDataSource.Factory,
            fileDataSource: DataSource = DefaultDataSource.Factory(context).createDataSource()
        ): DataSource.Factory {
            return DataSource.Factory {
                val http = httpDataSource.createDataSource()
                val file = fileDataSource
                val cache = MediaCacheManager.getCacheDataSourceFactory()?.createDataSource()
                StreamingDataSource(context.applicationContext, http, file, cache)
            }
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        currentUri = uri

        val resolvedUri = resolveUri(uri)
        val resolvedSpec = dataSpec.withUri(resolvedUri)

        android.util.Log.d("StreamingDataSource", "open uri=$uri resolved=$resolvedUri scheme=${resolvedUri.scheme}")

        currentDataSource = when (resolvedUri.scheme) {
            // fileDataSource — это DefaultDataSource, он умеет file://, а также
            // content:// (через ContentDataSource), asset://, android.resource://.
            // Локальные треки из MediaStore приходят как content:// — без этой
            // ветки они уходили в httpDataSource и не играли (тишина).
            "file", "content", "asset", "android.resource", "rawresource" -> fileDataSource
            "http", "https" -> cacheDataSource ?: httpDataSource
            else -> httpDataSource
        }

        return currentDataSource!!.open(resolvedSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return currentDataSource?.read(buffer, offset, length)
            ?: throw IOException("No data source open")
    }

    override fun close() {
        currentDataSource?.close()
        currentDataSource = null
        currentUri = null
    }

    override fun getUri(): Uri? = currentDataSource?.uri ?: currentUri

    private fun resolveUri(uri: Uri): Uri {
        if (uri.scheme != SCHEME_LIQUID) return uri

        val trackId = uri.getQueryParameter(PARAM_TRACK_ID)
        if (trackId != null) {
            // Check downloaded offline files first (regardless of premium status)
            val offlineMp3 = File(context.filesDir, "downloads/$trackId.mp3")
            val offlineM4a = File(context.filesDir, "downloads/$trackId.m4a")
            when {
                offlineMp3.exists() && offlineMp3.length() > 0 -> return Uri.fromFile(offlineMp3)
                offlineM4a.exists() && offlineM4a.length() > 0 -> return Uri.fromFile(offlineM4a)
            }

            val cachedUri = PlayerController.getValidCachedUri(trackId)
            if (cachedUri != null) return cachedUri

            val resolvedUrl = PlayerController.resolveStreamUrlSync(trackId)
            if (resolvedUrl != null) return resolvedUrl
        }

        val url = uri.getQueryParameter(PARAM_URL)
        if (!url.isNullOrEmpty()) return Uri.parse(url)

        throw IOException("Cannot resolve URL for track $trackId")
    }
}
