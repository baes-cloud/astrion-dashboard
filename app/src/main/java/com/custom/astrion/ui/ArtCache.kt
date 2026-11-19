package com.custom.astrion.ui

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cover art (media shelves, Plex posters, player hero art) cached in memory and
 * on disk (tmpfs on the HA100).
 *
 *  - Memory: bounded to 16 MB. Small thumbnails (`minPx in 1..256`) decode as
 *    RGB_565 (50% RAM/GPU bandwidth reduction), while active hero art and
 *    background washes (`minPx > 256` or `0`) keep ARGB_8888 to prevent
 *    visible 5-bit gradient banding on Mali-400.
 *  - Network: bounded to 4 concurrent fetches (`Semaphore(4)`) with a 60 s
 *    negative-cache TTL so unreachable CDNs don't cause timeout storms over
 *    Wi-Fi power save.
 *  - Keys: URL -> SHA-1 results are memoized in memory so `peek()` never runs
 *    SHA-1 or string formatting during lazy list scrolling.
 */
object ArtCache {
    private const val TAG = "ArtCache"

    /** Fits the 32 MB tmpfs bootguard.sh mounts over the app's cache dir. */
    private const val DISK_MAX = 28L * 1024 * 1024
    /** Trim down to this so a full cache doesn't trim on every write. */
    private const val DISK_TRIM_TO = 20L * 1024 * 1024
    private const val MEMORY_MAX = 16 * 1024 * 1024
    private const val NEGATIVE_TTL_MS = 60_000L
    private const val URL_KEY_CACHE_MAX = 1024
    private val STRIP_PARAMS = setOf("token", "authSig", "X-Plex-Token")
    private val HEX = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')

    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_MAX) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }

    private val urlKeyCache = ConcurrentHashMap<String, String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val fetchGate = Semaphore(4)

    @Volatile private var dir: File? = null
    private val diskBytes = AtomicLong(-1)

    /** Call once at startup (from IO); without it only the memory cache is used. */
    fun init(cacheDir: File) {
        dir = File(cacheDir, "art").apply { mkdirs() }
    }

    /** Loads under way, by memory key, so two cards asking at once share one. */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()

    /** Already-decoded art, for a first frame that doesn't flash a placeholder. */
    fun peek(url: String, minPx: Int = 0): ImageBitmap? = memory.get(memoryKey(key(url), minPx))

    suspend fun load(url: String, minPx: Int = 0, fetch: suspend () -> ByteArray?): ImageBitmap? {
        val k = key(url)
        val mk = memoryKey(k, minPx)
        memory.get(mk)?.let { return it }

        val lastFail = failedAt[k]
        if (lastFail != null && System.currentTimeMillis() - lastFail < NEGATIVE_TTL_MS) {
            return null
        }

        val mine = CompletableDeferred<ImageBitmap?>()
        inFlight.putIfAbsent(mk, mine)?.let { other ->
            return other.await() ?: memory.get(mk)
        }
        var bitmap: ImageBitmap? = null
        try {
            bitmap = withContext(Dispatchers.IO) {
                val fromDisk = readDisk(k)?.let { bytes ->
                    decode(bytes, minPx) ?: run {
                        dir?.let { File(it, k).delete() }
                        null
                    }
                }
                if (fromDisk != null) {
                    fromDisk
                } else {
                    val fetchedBytes = fetchGate.withPermit { fetch() }
                    if (fetchedBytes != null) {
                        decode(fetchedBytes, minPx)?.also {
                            failedAt.remove(k)
                            writeDisk(k, fetchedBytes)
                        }
                    } else {
                        failedAt[k] = System.currentTimeMillis()
                        null
                    }
                }
            }
            if (bitmap != null) {
                failedAt.remove(k)
                memory.put(mk, bitmap)
            }
        } finally {
            inFlight.remove(mk, mine)
            mine.complete(bitmap)
        }
        return bitmap
    }

    private fun memoryKey(k: String, minPx: Int) = "$k@$minPx"

    private fun key(url: String): String {
        urlKeyCache[url]?.let { return it }
        val q = url.indexOf('?')
        val stable = if (q < 0) url else {
            val kept = url.substring(q + 1).split('&').filterNot {
                it.substringBefore('=') in STRIP_PARAMS
            }
            url.substring(0, q) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(stable.toByteArray())
        val chars = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val b = digest[i].toInt() and 0xFF
            chars[i * 2] = HEX[b ushr 4]
            chars[i * 2 + 1] = HEX[b and 0x0F]
        }
        val hex = String(chars)
        if (urlKeyCache.size >= URL_KEY_CACHE_MAX) urlKeyCache.clear()
        urlKeyCache[url] = hex
        return hex
    }

    private fun decode(bytes: ByteArray, minPx: Int): ImageBitmap? {
        // Thumbnails (<= 256 px) use RGB_565 for 50% RAM/GPU savings; active
        // 480 px hero art and ArtBackdrop washes keep ARGB_8888 to avoid banding.
        val cfg = if (minPx in 1..256) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        return decodeSampled(bytes, minPx, cfg)
    }

    private fun readDisk(k: String): ByteArray? {
        val f = File(dir ?: return null, k)
        if (!f.isFile) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    private fun writeDisk(k: String, bytes: ByteArray) {
        val d = dir ?: return
        runCatching {
            val tmp = File(d, "$k.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(d, k))) { tmp.delete(); return }
            if (diskBytes.get() < 0) diskBytes.set(d.listFiles()?.sumOf { it.length() } ?: 0)
            else diskBytes.addAndGet(bytes.size.toLong())
            if (diskBytes.get() > DISK_MAX) trim(d)
        }.onFailure { Log.w(TAG, "writing art to disk failed", it) }
    }

    @Synchronized
    private fun trim(d: File) {
        val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= DISK_TRIM_TO) break
            val len = f.length()
            if (f.delete()) total -= len
        }
        diskBytes.set(total)
    }
}
