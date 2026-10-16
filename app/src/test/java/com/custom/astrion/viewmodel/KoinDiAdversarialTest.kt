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
import com.custom.astrion.test.MainDispatcherRule
import com.custom.astrion.ui.StrongHaptics
import com.custom.astrion.voice.VoiceSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.InstanceCreationException
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import java.io.File
import java.nio.file.Files

/**
 * Adversarial test suite for Koin Dependency Injection graph.
 * Stress-tests isolated module resolution, missing dependency failures,
 * singleton vs factory lifecycles, and Koin re-entrancy.
 */
class KoinDiAdversarialTest : KoinTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var tempDir: File
    private val mockContext = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("koin_adv_test").toFile()
        every { mockContext.filesDir } returns tempDir
        every { mockContext.cacheDir } returns tempDir
    }

    @After
    fun tearDown() {
        stopKoin()
        tempDir.deleteRecursively()
    }

    @Test
    fun isolatedResolution_networkModuleSucceedsIndependently() {
        startKoin {
            androidContext(mockContext)
            modules(networkModule)
        }

        val okHttp = get<OkHttpClient>()
        val cache = get<EntityCacheRepository>()
        val haClient = get<HaClient>()

        assertNotNull(okHttp)
        assertNotNull(cache)
        assertNotNull(haClient)

        assertSame(okHttp, get<OkHttpClient>())
        assertSame(cache, get<EntityCacheRepository>())
        assertSame(haClient, get<HaClient>())
    }

    @Test
    fun isolatedResolution_repositoryModuleSucceedsIndependently() {
        startKoin {
            androidContext(mockContext)
            modules(repositoryModule)
        }

        val config = get<AppConfigRepository>()
        assertNotNull(config)
        assertSame(config, get<AppConfigRepository>())
    }

    @Test
    fun isolatedResolution_hardwareModuleFailsFastOnVoiceSessionWithoutHaClient() {
        startKoin {
            androidContext(mockContext)
            modules(hardwareModule)
        }

        // Independent components in hardwareModule resolve cleanly
        assertNotNull(get<IrBlaster>())
        assertNotNull(get<HardwareKeyRouter>())
        assertNotNull(get<MotionWakeController>())
        assertNotNull(get<StrongHaptics>())
        assertNotNull(get<KeyDispatcher>())

        // VoiceSession requires HaClient from networkModule: must throw InstanceCreationException
        val exception = assertThrows(InstanceCreationException::class.java) {
            get<VoiceSession>()
        }
        assertTrue(
            "Exception message must mention missing HaClient dependency",
            exception.cause is NoDefinitionFoundException || exception.message?.contains("HaClient") == true
        )
    }

    @Test
    fun isolatedResolution_viewModelModuleFailsFastWhenDependenciesMissing() {
        startKoin {
            androidContext(mockContext)
            modules(viewModelModule)
        }

        assertThrows(InstanceCreationException::class.java) {
            get<DashboardViewModel>()
        }
        assertThrows(InstanceCreationException::class.java) {
            get<PowerViewModel>()
        }
        assertThrows(InstanceCreationException::class.java) {
            get<InputViewModel>()
        }
    }

    @Test
    fun missingDefinition_unregisteredTypeThrowsNoDefinitionFoundException() {
        startKoin {
            androidContext(mockContext)
            modules(appModules)
        }

        assertThrows(NoDefinitionFoundException::class.java) {
            get<java.util.Date>()
        }
    }

    @Test
    fun singletonVsFactory_allSingletonsProduceIdenticalReferences() {
        startKoin {
            androidContext(mockContext)
            modules(appModules)
        }

        // Network module singletons
        assertSame(get<OkHttpClient>(), get<OkHttpClient>())
        assertSame(get<EntityCacheRepository>(), get<EntityCacheRepository>())
        assertSame(get<HaClient>(), get<HaClient>())

        // Hardware module singletons
        assertSame(get<IrBlaster>(), get<IrBlaster>())
        assertSame(get<HardwareKeyRouter>(), get<HardwareKeyRouter>())
        assertSame(get<MotionWakeController>(), get<MotionWakeController>())
        assertSame(get<StrongHaptics>(), get<StrongHaptics>())
        assertSame(get<VoiceSession>(), get<VoiceSession>())

        // Repository module singletons
        assertSame(get<AppConfigRepository>(), get<AppConfigRepository>())
    }

    @Test
    fun singletonVsFactory_keyDispatcherProducesNewInstancePerResolution() {
        startKoin {
            androidContext(mockContext)
            modules(appModules)
        }

        val d1 = get<KeyDispatcher>()
        val d2 = get<KeyDispatcher>()
        val d3 = get<KeyDispatcher>()

        assertNotSame("KeyDispatcher must be a factory, not singleton", d1, d2)
        assertNotSame("KeyDispatcher must be a factory, not singleton", d2, d3)
        assertNotSame("KeyDispatcher must be a factory, not singleton", d1, d3)
    }

    @Test
    fun viewModelModule_resolvesDistinctViewModelInstances() {
        startKoin {
            androidContext(mockContext)
            modules(appModules)
        }

        val vm1 = get<DashboardViewModel>()
        val vm2 = get<DashboardViewModel>()

        try {
            assertNotSame("ViewModels resolve as distinct instances outside lifecycle scope", vm1, vm2)
        } finally {
            vm1.viewModelScope.cancel()
            vm2.viewModelScope.cancel()
        }
    }

    @Test
    fun koinLifecycle_reentrantStartStopDoesNotLeakOrCorruptGraph() {
        for (i in 1..3) {
            startKoin {
                androidContext(mockContext)
                modules(appModules)
            }

            assertNotNull(get<HaClient>())
            assertNotNull(get<AppConfigRepository>())

            stopKoin()
        }
    }

    @Test
    fun moduleOverride_allowsMockingSingletonsInTestHarness() {
        val mockCache = mockk<EntityCacheRepository>(relaxed = true)
        val overrideModule = module {
            single<EntityCacheRepository> { mockCache }
        }

        startKoin {
            androidContext(mockContext)
            modules(networkModule, overrideModule)
        }

        assertSame("Injected cache must be the override mock", mockCache, get<EntityCacheRepository>())
    }
}
