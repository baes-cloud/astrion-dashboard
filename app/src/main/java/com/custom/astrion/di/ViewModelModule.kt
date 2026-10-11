package com.custom.astrion.di

import com.custom.astrion.viewmodel.DashboardViewModel
import com.custom.astrion.viewmodel.InputViewModel
import com.custom.astrion.viewmodel.PowerViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel {
        DashboardViewModel(
            client = get(),
            voice = get(),
            configRepo = get(),
        )
    }
    viewModel {
        PowerViewModel(
            context = androidContext(),
            client = get(),
            motionWake = get(),
            configRepo = get(),
        )
    }
    viewModel {
        InputViewModel(
            context = androidContext(),
            router = get(),
            irBlaster = get(),
            client = get(),
            configRepo = get(),
            haptics = get(),
        )
    }
}

val appModules = listOf(networkModule, hardwareModule, repositoryModule, viewModelModule)
