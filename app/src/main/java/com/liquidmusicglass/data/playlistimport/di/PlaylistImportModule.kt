package com.liquidmusicglass.data.playlistimport.di

import com.liquidmusicglass.api.icm.IcmApi
import com.liquidmusicglass.data.playlistimport.IcmSearchApi
import com.liquidmusicglass.data.playlistimport.PlaylistImportRepository
import com.liquidmusicglass.data.playlistimport.PlaylistImportViewModel

object PlaylistImportModule {

    // Яндекс-резолвер теперь on-device (YandexPlaylistFetcher, object) —
    // Retrofit-клиент личного FastAPI-сервера выпилен вместе с сервером.

    fun provideIcmSearchApi(): IcmSearchApi {
        return IcmSearchApi(
            // Через сервер-брокер (см. IcmApi.SERVER_BASE): партнёрский ключ
            // подставляет сервер, клиент шлёт только Bearer session-token.
            // Нативный base-url (LcmNative.getIcmBaseUrl → byicloud напрямую)
            // больше не используется.
            baseUrl = IcmApi.BASE_URL,
            authProvider = {
                val sessionToken = IcmApi.getInstance().sessionToken
                null to sessionToken
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
