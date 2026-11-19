package com.custom.astrion.di

import com.custom.astrion.BuildConfig
import com.custom.astrion.config.AppConfigRepository
import com.custom.astrion.ha.HaEndpoint
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
        // device.json's `"ha": { "url": "…", "token": "…" }` wins over the
        // values compiled in from secrets.properties, so one APK (a CI build
        // included) serves any remote and a rotated token needs no rebuild.
        // Read once: the client is made at app start, so a change takes
        // effect when the app next starts.
        val ha = HaEndpoint.from(getOrNull<AppConfigRepository>()?.getCurrentResult()?.config?.options.orEmpty(), BuildConfig.HA_URL, BuildConfig.HA_TOKEN)
        HaClient(
            baseUrl = ha.url,
            token = ha.token,
            cache = get(),
            clientOverride = get(),
        )
    }
}
