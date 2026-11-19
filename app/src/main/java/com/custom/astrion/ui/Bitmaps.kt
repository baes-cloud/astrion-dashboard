package com.custom.astrion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Loading PNGs off `/sdcard` without stalling composition or hoarding RAM.
 *
 * Uses a bounded 8 MB LRU cache and performs `File.lastModified()` checks
 * strictly on [Dispatchers.IO] (never during composition).
 */
fun decodeSampled(path: String, targetPx: Int): ImageBitmap? = runCatching {
    val f = File(path)
    if (!f.exists()) return@runCatching null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return@runCatching null

    var sample = 1
    while (targetPx > 0 && longest / (sample * 2) >= targetPx) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
}.getOrNull()

/**
 * [decodeSampled] for image bytes already in memory (a download). Same
 * rule: the largest power-of-two reduction that keeps the longest edge at or
 * above [targetPx] (0 = full size). [config] allows thumbnails to decode as
 * RGB_565 while hero/backdrop images keep ARGB_8888 to avoid gradient banding.
 */
fun decodeSampled(
    bytes: ByteArray,
    targetPx: Int,
    config: Bitmap.Config = Bitmap.Config.ARGB_8888,
): ImageBitmap? = runCatching {
    val opts = BitmapFactory.Options().apply { inPreferredConfig = config }
    if (targetPx > 0) {
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val longest = maxOf(opts.outWidth, opts.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= targetPx) sample *= 2
        opts.inJustDecodeBounds = false
        opts.inSampleSize = sample
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}.getOrNull()

/**
 * [decodeSampled] on the IO dispatcher, surfaced as composable state.
 * Never calls [File.lastModified] on the composition thread; checks mtime
 * asynchronously on IO and re-decodes only when the file changed.
 */
@Composable
fun rememberSampledBitmap(path: String?, targetPx: Int): State<ImageBitmap?> {
    val memKey = path?.let { "$it|$targetPx" }
    return produceState(initialValue = memKey?.let { sampledCache.get(it) }, path, targetPx) {
        val p = path ?: run { value = null; return@produceState }
        val key = "$p|$targetPx"
        val currentMtime = withContext(Dispatchers.IO) { File(p).lastModified() }
        val cached = sampledCache.get(key)
        if (cached != null && sampledMtimes[key] == currentMtime) {
            value = cached
            return@produceState
        }
        val decoded = withContext(Dispatchers.IO) { decodeSampled(p, targetPx) }
        if (decoded != null) {
            sampledCache.put(key, decoded)
            sampledMtimes[key] = currentMtime
        } else {
            sampledCache.remove(key)
            sampledMtimes.remove(key)
        }
        value = decoded
    }
}

private const val SAMPLED_CACHE_MAX_BYTES = 8 * 1024 * 1024

private val sampledCache = object : LruCache<String, ImageBitmap>(SAMPLED_CACHE_MAX_BYTES) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
}

private val sampledMtimes = ConcurrentHashMap<String, Long>()
