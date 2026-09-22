package com.custom.astrion.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loading PNGs off `/sdcard` without stalling composition or hoarding RAM.
 *
 * Two problems this fixes. `ButtonGridCard` decoded its six shortcut icons
 * synchronously inside `remember` — i.e. file I/O plus a full-resolution decode
 * on the composition thread, at first composition of the Media page, for images
 * up to 78 KB being drawn into a 32dp box. And nothing anywhere passed
 * `inSampleSize`, so the floorplan (1089 × 1047 on this device) sat resident as
 * 1089 × 1047 × 4 = 4.56 MB of ARGB_8888 for the life of the card, to fill
 * roughly 460 px of screen. On a 1 GB MT6580 that is worth reclaiming.
 */

/**
 * Decode [path] downsampled so its longest edge is no smaller than [targetPx].
 *
 * `inSampleSize` only takes powers of two, so this picks the largest power of
 * two that still leaves the image at or above the target — the result is never
 * upscaled into blurriness, it just stops carrying detail the screen cannot
 * show. At `inSampleSize = 2` the floorplan drops to ~1.1 MB with no visible
 * difference at 480px wide.
 *
 * Blocking: call from [Dispatchers.IO].
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
 * [decodeSampled] on the IO dispatcher, surfaced as composable state. Null
 * until the decode finishes, so first composition never blocks.
 */
@Composable
fun rememberSampledBitmap(path: String?, targetPx: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, path, targetPx) {
        value = path?.let { p -> withContext(Dispatchers.IO) { decodeSampled(p, targetPx) } }
    }
