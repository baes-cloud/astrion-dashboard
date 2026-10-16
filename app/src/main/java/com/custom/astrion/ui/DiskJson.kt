package com.custom.astrion.ui

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Small JSON documents kept under `cacheDir/json`, for screens that should
 * show their last result on a cold start (the Plex shelves) and refresh
 * behind it. Lost if Android clears the cache, which only costs one load.
 */
object DiskJson {
    @Volatile private var dir: File? = null

    /** Call once at startup; until then reads miss and writes are dropped. */
    fun init(cacheDir: File) {
        dir = File(cacheDir, "json").apply { mkdirs() }
    }

    fun read(key: String): String? = file(key)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun write(key: String, text: String) {
        val f = file(key) ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) tmp.delete()
        }.onFailure { Log.w("DiskJson", "write failed", it) }
    }

    private fun file(key: String): File? {
        val d = dir ?: return null
        val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(d, name)
    }
}
