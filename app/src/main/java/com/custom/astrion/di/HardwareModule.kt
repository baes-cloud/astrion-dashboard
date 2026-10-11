package com.custom.astrion.di

import com.custom.astrion.input.HardwareKeyRouter
import com.custom.astrion.input.KeyDispatcher
import com.custom.astrion.ir.IrBlaster
import com.custom.astrion.power.MotionWake
import com.custom.astrion.power.MotionWakeController
import com.custom.astrion.ui.StrongHaptics
import com.custom.astrion.voice.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val hardwareModule = module {
    single { IrBlaster(androidContext()) }
    single { HardwareKeyRouter() }
    single<MotionWakeController> { MotionWake(context = androidContext()) }
    single { StrongHaptics(androidContext()) }
    single { VoiceSession(androidContext(), get()) }
    factory { KeyDispatcher(router = get(), scope = CoroutineScope(Dispatchers.Main.immediate)) }
}
