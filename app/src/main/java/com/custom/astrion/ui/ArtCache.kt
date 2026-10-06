package com.custom.astrion.ui

import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cover art (media shelves, Plex posters) cached in memory and on disk.
 *
 * The art on those pages barely changes — favourites rows, a Plex library,
 * and "Random albums" drawn from a finite library — yet every visit used to
 * refetch and redecode all of it, and much of it comes from internet CDNs.
 *
 *  - Memory: decoded bitmaps, bounded by bytes (this is a 1 GB device), so
 *    swiping back to a page is instant.
 *  - Disk: the downloaded image files under `cacheDir/art`, so art survives
 *    restarts and a cover is downloaded once, ever. Capped at [DISK_MAX];
 *    past that the least recently shown files are deleted. It lives in
 *    cacheDir, which Android also clears first if storage runs low.
 *
 * Failed fetches are never cached, so unreachable art is retried next time.
 */
object ArtCache {
    private const val TAG = "ArtCache"

    /** ~10–20k covers — far more than any library, with ~4.5 GB left spare. */
    private const val DISK_MAX = 500L * 1024 * 1024
    /** Trim down to this so a full cache doesn't trim on every write. */
    private const val DISK_TRIM_TO = 450L * 1024 * 1024
    private const val MEMORY_MAX = 24 * 1024 * 1024

    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_MAX) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }

    @Volatile private var dir: File? = null
    private val diskBytes = AtomicLong(-1)

    /** Call once at startup; without it only the memory cache is used. */
    fun init(cacheDir: File) {
        dir = File(cacheDir, "art").apply { mkdirs() }
    }

    /** Loads under way, by memory key, so two cards asking at once share one. */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()

    /** Already-decoded art, for a first frame that doesn't flash a placeholder. */
    fun peek(url: String, minPx: Int = 0): ImageBitmap? = memory.get(memoryKey(key(url), minPx))

    /**
     * Art for [url]: from memory, else disk, else [fetch] (whose bytes are then
     * stored). Decoded downsampled so its longest edge stays at least [minPx]
     * (0 = full size). Null if it can't be fetched or decoded.
     *
     * Memory holds each decode size separately: the screensaver's 128 px
     * cover and the player card's 480 px one share a URL, and with one entry
     * per URL whichever finished last replaced the other. The disk copy is
     * the original bytes, so it is shared.
     */
    suspend fun load(url: String, minPx: Int = 0, fetch: suspend () -> ByteArray?): ImageBitmap? {
        val k = key(url)
        val mk = memoryKey(k, minPx)
        memory.get(mk)?.let { return it }
        val mine = CompletableDeferred<ImageBitmap?>()
        inFlight.putIfAbsent(mk, mine)?.let { other ->
            // Someone else is already loading it; null only if theirs failed
            // or was cancelled, in which case memory has nothing either.
            return other.await() ?: memory.get(mk)
        }
        var bitmap: ImageBitmap? = null
        try {
            bitmap = withContext(Dispatchers.IO) {
                readDisk(k)?.let { decode(it, minPx) }
                    ?: fetch()?.let { bytes -> decode(bytes, minPx)?.also { writeDisk(k, bytes) } }
            }
            if (bitmap != null) memory.put(mk, bitmap)
        } finally {
            inFlight.remove(mk, mine)
            mine.complete(bitmap)
        }
        return bitmap
    }

    private fun memoryKey(k: String, minPx: Int) = "$k@$minPx"

    /**
     * Cache key: the URL minus auth parameters (the HA token, HA's signed-path
     * `authSig`, the Plex token), so a rotated credential doesn't orphan every
     * cover already on disk.
     */
    private fun key(url: String): String {
        val q = url.indexOf('?')
        val stable = if (q < 0) url else {
            val kept = url.substring(q + 1).split('&').filterNot {
                it.substringBefore('=') in setOf("token", "authSig", "X-Plex-Token")
            }
            url.substring(0, q) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        }
        return MessageDigest.getInstance("SHA-1").digest(stable.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun decode(bytes: ByteArray, minPx: Int): ImageBitmap? = decodeSampled(bytes, minPx)

    private fun readDisk(k: String): ByteArray? {
        val f = File(dir ?: return null, k)
        if (!f.isFile) return null
        return runCatching {
            // lastModified doubles as "last shown", for trimming.
            f.setLastModified(System.currentTimeMillis())
            f.readBytes()
        }.getOrNull()
    }

    private fun writeDisk(k: String, bytes: ByteArray) {
        val d = dir ?: return
        runCatching {
            // Write-then-rename, so a crash mid-write never leaves a torn file.
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
