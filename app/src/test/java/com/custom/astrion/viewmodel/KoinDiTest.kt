package com.custom.astrion.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.custom.astrion.config.AppConfigRepository
import com.custom.astrion.di.appModules
import com.custom.astrion.di.hardwareModule
import com.custom.astrion.di.networkModule
import com.custom.astrion.di.repositoryModule
import com.custom.astrion.di.viewModelModule
import com.custom.astrion.ha.EntityCacheRepository
import com.custom.astrion.ha.HaClient
import com.custom.astrion.input.HardwareKeyRouter
import com.custom.astrion.input.KeyDispatcher
import com.custom.astrion.ir.IrBlaster
import com.custom.astrion.power.MotionWakeController
import com.custom.astrion.sync.SyncRepository
import com.custom.astrion.test.MainDispatcherRule
import com.custom.astrion.ui.StrongHaptics
import com.custom.astrion.voice.VoiceSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.get
import java.io.File
import java.nio.file.Files

class KoinDiTest : KoinTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var tempDir: File
    private val mockContext = mockk<Context>(relaxed = true)

    private var dashboardVm: DashboardViewModel? = null
    private var powerVm: PowerViewModel? = null
    private var inputVm: InputViewModel? = null

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("koin_di_test").toFile()
        every { mockContext.filesDir } returns tempDir
        every { mockContext.cacheDir } returns tempDir

        startKoin {
            androidContext(mockContext)
            modules(appModules)
        }
    }

    @After
    fun tearDown() {
        dashboardVm?.viewModelScope?.cancel()
        powerVm?.viewModelScope?.cancel()
        inputVm?.viewModelScope?.cancel()
        stopKoin()
        tempDir.deleteRecursively()
    }

    @Test
    fun appModulesContainsAllIndividualModules() {
        assertEquals(4, appModules.size)
        assertTrue(appModules.contains(networkModule))
        assertTrue(appModules.contains(hardwareModule))
        assertTrue(appModules.contains(repositoryModule))
        assertTrue(appModules.contains(viewModelModule))
    }

    @Test
    fun verifyNetworkModuleResolvesSingletons() {
        val okHttp = get<OkHttpClient>()
        assertNotNull(okHttp)

        val entityCacheRepo = get<EntityCacheRepository>()
        assertNotNull(entityCacheRepo)

        val haClient = get<HaClient>()
        assertNotNull(haClient)

        // Verify singletons return the same instance
        assertSame(haClient, get<HaClient>())
        assertSame(entityCacheRepo, get<EntityCacheRepository>())
    }

    @Test
    fun verifyHardwareModuleResolvesDefinitions() {
        val irBlaster = get<IrBlaster>()
        assertNotNull(irBlaster)

        val router = get<HardwareKeyRouter>()
        assertNotNull(router)

        val motionWake = get<MotionWakeController>()
        assertNotNull(motionWake)

        val haptics = get<StrongHaptics>()
        assertNotNull(haptics)

        val voice = get<VoiceSession>()
        assertNotNull(voice)

        val dispatcher1 = get<KeyDispatcher>()
        val dispatcher2 = get<KeyDispatcher>()
        assertNotNull(dispatcher1)
        assertNotNull(dispatcher2)

        // KeyDispatcher is configured as factory: should be distinct instances
        assertNotSame(dispatcher1, dispatcher2)
    }

    @Test
    fun verifyRepositoryModuleResolvesSingletons() {
        val configRepo = get<AppConfigRepository>()
        assertNotNull(configRepo)

        val syncRepo = get<SyncRepository>()
        assertNotNull(syncRepo)

        assertSame(configRepo, get<AppConfigRepository>())
        assertSame(syncRepo, get<SyncRepository>())
    }

    @Test
    fun verifyViewModelModuleResolvesAllViewModels() {
        dashboardVm = get<DashboardViewModel>()
        assertNotNull(dashboardVm)

        powerVm = get<PowerViewModel>()
        assertNotNull(powerVm)

        inputVm = get<InputViewModel>()
        assertNotNull(inputVm)
    }

    @Test
    fun verifyInjectedDependenciesMatchKoinSingletons() {
        dashboardVm = get<DashboardViewModel>()
        powerVm = get<PowerViewModel>()
        inputVm = get<InputViewModel>()

        // DashboardViewModel dependencies
        assertSame(get<HaClient>(), dashboardVm?.client)
        assertSame(get<VoiceSession>(), dashboardVm?.voice)
        assertSame(get<AppConfigRepository>(), dashboardVm?.configRepo)

        // InputViewModel dependencies
        assertSame(get<HardwareKeyRouter>(), inputVm?.router)
        assertSame(get<IrBlaster>(), inputVm?.irBlaster)
    }
}
