package com.custom.astrion.config

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : AppConfigRepository {

    private var loadedConfigStamp: Pair<Long, Long>? = null

    private val _configFlow = MutableStateFlow(DashboardLoader.Result(DashboardLoader.default, null))
    override val configFlow: StateFlow<DashboardLoader.Result> = _configFlow.asStateFlow()

    override fun getCurrentResult(): DashboardLoader.Result = _configFlow.value

    override suspend fun loadConfig(force: Boolean): DashboardLoader.Result = withContext(Dispatchers.IO) {
        val file = configFileProvider()
        val stamp = file.lastModified() to file.length()
        if (!force && stamp.first != 0L && stamp == loadedConfigStamp) {
            return@withContext _configFlow.value
        }
        val result = DashboardLoader.load()
        loadedConfigStamp = file.lastModified() to file.length()
        _configFlow.value = result
        result
    }
}
