package com.custom.astrion.power

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Detects when Android's `/data` f2fs partition has remounted read-only due to
 * eMMC flash decay, so the app can:
 *  1. Surface a calm non-blocking warning banner ("do not reboot").
 *  2. Include `storage_ro: true` in Home Assistant battery telemetry.
 *  3. Short-circuit non-tmpfs writes that would only fail with EROFS.
 */
object StorageHealth {
    private const val PROC_MOUNTS = "/proc/mounts"

    private val _isDataReadOnly = MutableStateFlow(false)
    val isDataReadOnly: StateFlow<Boolean> = _isDataReadOnly.asStateFlow()

    /** Blocking read of `/proc/mounts`; call from [kotlinx.coroutines.Dispatchers.IO]. */
    fun refresh(mountsPath: String = PROC_MOUNTS): Boolean {
        val ro = parseDataReadOnly(runCatching { File(mountsPath).readText() }.getOrNull())
        _isDataReadOnly.value = ro
        return ro
    }

    /** Pure parser for `/proc/mounts` lines (unit-testable on JVM). */
    internal fun parseDataReadOnly(mountsText: String?): Boolean {
        if (mountsText.isNullOrBlank()) return false
        for (line in mountsText.lineSequence()) {
            val parts = line.trim().split(' ')
            if (parts.size >= 4 && parts[1] == "/data") {
                return parts[3].split(',').any { it == "ro" }
            }
        }
        return false
    }
}
