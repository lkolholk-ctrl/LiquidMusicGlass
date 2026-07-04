package com.liquidmusicglass.data.playlistimport.di

import com.liquidmusicglass.api.icm.IcmApi
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.data.playlistimport.IcmSearchApi
import com.liquidmusicglass.data.playlistimport.PlaylistImportRepository
import com.liquidmusicglass.data.playlistimport.PlaylistImportViewModel
import com.liquidmusicglass.security.LcmNative

object PlaylistImportModule {

    // Яндекс-резолвер теперь on-device (YandexPlaylistFetcher, object) —
    // Retrofit-клиент личного FastAPI-сервера выпилен вместе с сервером.

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
            icmSearch = provideIcmSearchApi()
        )
    }

    fun providePlaylistImportViewModel(): PlaylistImportViewModel {
        return PlaylistImportViewModel()
    }
}
