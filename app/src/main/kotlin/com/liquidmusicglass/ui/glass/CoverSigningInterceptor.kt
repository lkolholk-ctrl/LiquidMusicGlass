package com.liquidmusicglass.ui.glass

import coil.intercept.Interceptor
import coil.request.ImageResult
import com.liquidmusicglass.api.icm.IcmRepository

/**
 * Coil interceptor to sign custom cover-art/avatar URLs on-the-fly.
 * Under the hood, custom avatar/cover images require signed URLs via /cover-sign.
 */
class CoverSigningInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data

        if (data is String && data.contains("/api/avatar/")) {
            val fileId = extractFileId(data)
            if (fileId != null) {
                // Fetch the signed URL from ICM API
                val signedUrl = IcmRepository.signCover(fileId)
                if (!signedUrl.isNullOrBlank()) {
                    val newRequest = request.newBuilder()
                        .data(signedUrl)
                        .build()
                    return chain.proceed(newRequest)
                }
            }
        }

        return chain.proceed(request)
    }

    private fun extractFileId(url: String): String? {
        val key = "/api/avatar/"
        val index = url.indexOf(key)
        if (index != -1) {
            val start = index + key.length
            var end = url.indexOf('?', start)
            if (end == -1) {
                end = url.length
            }
            return url.substring(start, end)
        }
        return null
    }
}
