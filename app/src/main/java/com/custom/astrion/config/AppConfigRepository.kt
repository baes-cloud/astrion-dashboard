package com.custom.astrion.config

import android.content.Context
import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

interface AppConfigRepository {
    val configFlow: StateFlow<DashboardLoader.Result>
    fun getCurrentResult(): DashboardLoader.Result
    suspend fun loadConfig(force: Boolean = false): DashboardLoader.Result
}

class DefaultAppConfigRepository(
    private val context: Context? = null,
    private val configFileProvider: () -> File = { DashboardLoader.configFile },
    private val deviceFileProvider: () -> File = { DashboardLoader.deviceFile },
    private val loader: () -> DashboardLoader.Result = { DashboardLoader.load() },
) : AppConfigRepository {

    private var loadedConfigStamp: List<Long>? = null

    private val _configFlow = MutableStateFlow(DashboardLoader.Result(DashboardLoader.default, null))
    override val configFlow: StateFlow<DashboardLoader.Result> = _configFlow.asStateFlow()

    private var observer: FileObserver? = null
    private var watchScope: CoroutineScope? = null
    private var reloadJob: Job? = null

    override fun getCurrentResult(): DashboardLoader.Result = _configFlow.value

    override suspend fun loadConfig(force: Boolean): DashboardLoader.Result = withContext(Dispatchers.IO) {
        loadNow(force)
    }

    /**
     * Blocking load, for app start: the ViewModels then begin on the real
     * config instead of the bundled one (which would post the battery to the
     * bundled `report_entity` and apply its screen timeout until onResume).
     */
    @Synchronized
    fun loadNow(force: Boolean = false): DashboardLoader.Result {
        val stamp = stamp()
        if (!force && stamp[0] != 0L && stamp == loadedConfigStamp) {
            return _configFlow.value
        }
        val result = loader()
        loadedConfigStamp = stamp()
        _configFlow.value = result
        tryWatch()
        return result
    }

    private fun stamp(): List<Long> {
        val config = configFileProvider()
        val device = deviceFileProvider()
        return listOf(config.lastModified(), config.length(), device.lastModified(), device.length())
    }

    /**
     * Reloads when dashboard.json or device.json changes on disk (an adb push,
     * a file manager save). Watches the folder rather than the files, since a
     * push can replace a file or create it for the first time. Events come in
     * bursts during a write, so a reload waits for them to settle.
     */
    @Synchronized
    fun startWatching(scope: CoroutineScope) {
        watchScope = scope
        tryWatch()
    }

    /** Also retried after each load: the folder may only appear once storage is granted. */
    @Suppress("DEPRECATION") // FileObserver(File) needs API 29; the HA100 is 27.
    private fun tryWatch() {
        val scope = watchScope ?: return
        if (observer != null) return
        val dir = configFileProvider().parentFile ?: return
        if (!dir.isDirectory) return
        val names = setOf(configFileProvider().name, deviceFileProvider().name)
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.CREATE
        observer = object : FileObserver(dir.path, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path !in names) return
                synchronized(this@DefaultAppConfigRepository) {
                    reloadJob?.cancel()
                    reloadJob = scope.launch(Dispatchers.IO) {
                        delay(RELOAD_SETTLE_MS)
                        runCatching { loadNow() }.onFailure { Log.w(TAG, "Reload failed", it) }
                    }
                }
            }
        }.also { it.startWatching() }
    }

    private companion object {
        const val TAG = "AppConfigRepository"
        const val RELOAD_SETTLE_MS = 500L
    }
}
