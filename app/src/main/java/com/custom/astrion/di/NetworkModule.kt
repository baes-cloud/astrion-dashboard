package com.custom.astrion.di

import com.custom.astrion.BuildConfig
import com.custom.astrion.ha.DiskEntityCacheRepository
import com.custom.astrion.ha.EntityCacheRepository
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.Http
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val networkModule = module {
    single<OkHttpClient> { Http.base }
    single<EntityCacheRepository> { DiskEntityCacheRepository(androidContext()) }
    single {
        HaClient(
            baseUrl = BuildConfig.HA_URL,
            token = BuildConfig.HA_TOKEN,
            cache = get(),
            clientOverride = get(),
        )
    }
}
