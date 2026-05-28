package com.liquidmusicglass.data.playlistimport.di

import com.liquidmusicglass.api.icm.IcmApi
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.data.playlistimport.IcmSearchApi
import com.liquidmusicglass.data.playlistimport.PlaylistImportRepository
import com.liquidmusicglass.data.playlistimport.YandexResolverApi
import com.liquidmusicglass.data.playlistimport.PlaylistImportViewModel
import com.liquidmusicglass.security.LcmNative
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object PlaylistImportModule {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    fun provideYandexResolverApi(baseUrl: String): YandexResolverApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl.ensureTrailingSlash())
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(YandexResolverApi::class.java)
    }

    fun provideIcmSearchApi(): IcmSearchApi {
        return IcmSearchApi(
            baseUrl = LcmNative.getIcmBaseUrl(), // из нативного слоя
            authProvider = {
                val partnerKey = IcmAuthRepository.getPartnerKey()
                    .takeIf { it.isNotBlank() }
                    ?: IcmApi.getInstance().apiKey
                val sessionToken = IcmApi.getInstance().sessionToken
                partnerKey to sessionToken
            }
        )
    }

    fun providePlaylistImportRepository(): PlaylistImportRepository {
        return PlaylistImportRepository(
            yandexResolver = provideYandexResolverApi(LcmNative.getYandexResolverUrl()),
            icmSearch = provideIcmSearchApi()
        )
    }

    fun providePlaylistImportViewModel(): PlaylistImportViewModel {
        return PlaylistImportViewModel()
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"
}
