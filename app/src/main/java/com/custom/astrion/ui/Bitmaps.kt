package com.custom.astrion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One image pipeline for the whole app: decode at the size it's drawn,
 * RGB_565 for opaque formats, and a single byte-budgeted LRU shared by the
 * floorplan, grid icons, album art, shelf covers and Plex posters.
 *
 * Before: album art and shelf covers were decoded at full size (640×640 ARGB
 * = 1.6 MB for an 85dp tile, ~30 per shelf page) with no cache, and the Plex
 * cache held 48 posters for a page that shows 75.
 */
object ImageCache {
    /** 12 MB: a page of posters plus shelves plus the floorplan, on a 1 GB device. */
    private const val BUDGET_BYTES = 12 * 1024 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(BUDGET_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.asAndroidBitmap().byteCount
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, value: ImageBitmap) {
        cache.put(key, value)
    }

    fun remoteKey(url: String, targetPx: Int): String = "$url@$targetPx"

    /** File entries are keyed on mtime too, so an `adb push` of a new plan shows. */
    fun fileKey(path: String, targetPx: Int): String? {
        val f = File(path)
        if (!f.exists()) return null
        return "file:$path@$targetPx:${f.lastModified()}"
    }
}

/** Largest power-of-two sample that keeps the longest edge ≥ [targetPx]. */
private fun sampleFor(width: Int, height: Int, targetPx: Int): Int {
    val longest = maxOf(width, height)
    var sample = 1
    while (targetPx > 0 && longest / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/**
 * Decode encoded image bytes downsampled to about [targetPx] on the longest
 * edge (0 = full size). Opaque formats (JPEG) decode as RGB_565, half the
 * memory; PNG keeps ARGB in case it has transparency.
 */
fun decodeSampledBytes(bytes: ByteArray, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val opaque = bounds.outMimeType?.contains("jpeg") == true
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, targetPx)
        if (opaque) inPreferredConfig = Bitmap.Config.RGB_565
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}.getOrNull()

/**
 * Decode [path] downsampled so its longest edge is no smaller than
 * [targetPx]. Blocking: call from [Dispatchers.IO].
 */
fun decodeSampled(path: String, targetPx: Int): ImageBitmap? = runCatching {
    val f = File(path)
    if (!f.exists()) return@runCatching null
    val key = ImageCache.fileKey(path, targetPx)
    key?.let { ImageCache.get(it) }?.let { return@runCatching it }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, targetPx)
    }
    BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
        ?.also { bmp -> key?.let { ImageCache.put(it, bmp) } }
}.getOrNull()

/**
 * [decodeSampled] on the IO dispatcher, surfaced as composable state. A
 * cached image is returned on the FIRST frame (no pop-in when you come back
 * to a page); otherwise null until the decode finishes.
 */
@Composable
fun rememberSampledBitmap(path: String?, targetPx: Int): State<ImageBitmap?> {
    val cached = remember(path, targetPx) {
        path?.let { p -> ImageCache.fileKey(p, targetPx)?.let { ImageCache.get(it) } }
    }
    return produceState(initialValue = cached, path, targetPx) {
        if (path == null) {
            value = null
            return@produceState
        }
        // decodeSampled answers from the cache when it can.
        value = withContext(Dispatchers.IO) { decodeSampled(path, targetPx) }
    }
}

/**
 * A remote image as composable state: the cache hit (if any) on the first
 * frame, else [load] (which should fill the cache under [cacheKey]).
 */
@Composable
fun rememberRemoteBitmap(
    cacheKey: String?,
    load: suspend () -> ImageBitmap?,
): State<ImageBitmap?> {
    val cached = remember(cacheKey) { cacheKey?.let { ImageCache.get(it) } }
    return produceState(initialValue = cached, cacheKey) {
        if (cacheKey == null) {
            value = null
            return@produceState
        }
        value = ImageCache.get(cacheKey) ?: load()
    }
}
