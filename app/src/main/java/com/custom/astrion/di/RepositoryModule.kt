package com.custom.astrion.di

import com.custom.astrion.config.AppConfigRepository
import com.custom.astrion.config.DefaultAppConfigRepository
import com.custom.astrion.sync.LocalSyncRepository
import com.custom.astrion.sync.SyncRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val repositoryModule = module {
    single<AppConfigRepository> { DefaultAppConfigRepository(androidContext()) }
    single<SyncRepository> { LocalSyncRepository() }
}
