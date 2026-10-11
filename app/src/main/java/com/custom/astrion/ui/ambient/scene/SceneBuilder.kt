package com.custom.astrion.ui.ambient.scene

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the now-playing view draws for one track: the cover and the
 * backdrop as [ImageBitmap]s, and the three tinted text levels, each already
 * lifted to keep ≥4.5:1 over the veil under the text.
 */
data class AmbientScene(
    val trackKey: String,
    val cover: ImageBitmap,
    val backdrop: ImageBitmap,
    /** 100 / 80 / 62% near-white, tinted with the cover's hue. */
    val ink: Color,
    val inkDim: Color,
    val inkFaint: Color,
    val fromArtwork: Boolean,
)

/**
 * Bakes an [AmbientScene] for a track, off the main thread.
 *
 * Decodes the artwork once at [DISPLAY_PX] as RGB_565 (half the bytes of
 * ARGB_8888, and the HA100 has little RAM), downscales a copy to [ANALYSIS_PX]
 * for the palette, clusters it in Oklab, positions the pools by where each
 * colour sits, and bakes + blurs the backdrop. With no artwork it falls back to
 * a deterministic [ChladniArt] figure and uses its colours.
 *
 * Logs the bake time under the `Ambient` tag (the acceptance target is <150 ms
 * on the device — unverified here; see the notes for the adb command).
 */
object SceneBuilder {
    private const val TAG = "Ambient"
    const val DISPLAY_PX = 400

    /**
     * Analysis resolution for the palette. 40px (1600 pixels) still finds a
     * cover's handful of colours and is the bake's dominant cost on this SoC
     * (per-pixel Oklab + clustering), so it is kept small.
     */
    const val ANALYSIS_PX = 40

    suspend fun build(trackKey: String, artBytes: ByteArray?): AmbientScene = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtime()

        val cover: Bitmap
        val swatches: List<Swatch>
        val positioned: List<PositionedSwatch>
        val fromArtwork: Boolean

        val decoded = artBytes?.let { decode(it) }
        var tDecode = 0L; var tPalette = 0L
        if (decoded != null) {
            tDecode = SystemClock.elapsedRealtime() - t0
            cover = decoded
            val small = if (decoded.width > ANALYSIS_PX || decoded.height > ANALYSIS_PX)
                Bitmap.createScaledBitmap(decoded, ANALYSIS_PX, ANALYSIS_PX, true) else decoded
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            // Convert each pixel to Oklab once and reuse it for both the palette
            // and the pool placement — the conversion is the bake's hot spot.
            val labs = Array(px.size) { OklabConvert.toOklab(px[it] and 0xFFFFFF) }
            swatches = CoverPalette.ofLabs(labs)
            positioned = position(labs, small.width, small.height, swatches)
            if (small !== decoded) small.recycle()
            tPalette = SystemClock.elapsedRealtime() - t0 - tDecode
            fromArtwork = true
        } else {
            val art = ChladniArt.of(trackKey, DISPLAY_PX)
            cover = art.cover
            swatches = art.swatches
            // No spatial information for a generated figure: spread the pools.
            positioned = swatches.mapIndexed { i, s ->
                val a = i / swatches.size.toFloat() * 6.283f
                PositionedSwatch(s.color, s.weight, 0.5f + 0.3f * kotlin.math.cos(a), 0.35f + 0.25f * kotlin.math.sin(a))
            }
            fromArtwork = false
        }

        val tBeforeBackdrop = SystemClock.elapsedRealtime()
        val ground = swatches.minByOrNull { it.color.l }?.color ?: Oklab(0.15f, 0f, 0f)
        val backdrop = Backdrop.build(positioned, ground)
        val tBackdrop = SystemClock.elapsedRealtime() - tBeforeBackdrop

        // Text: near-white tinted with the dominant hue, each level lifted to
        // ≥4.5:1 over the darkest the veil leaves under the text.
        val hue = swatches.maxByOrNull { it.color.chroma }?.color ?: Oklab(0.9f, 0f, 0f)
        val veilBg = OklabConvert.toRgb(OklabConvert.clampLightness(ground, 0.05f, 0.09f))
        val ink = tintedInk(0.98f, hue, veilBg)
        val inkDim = tintedInk(0.86f, hue, veilBg)
        val inkFaint = tintedInk(0.72f, hue, veilBg)

        val scene = AmbientScene(trackKey, cover.asImageBitmap(), backdrop.asImageBitmap(), ink, inkDim, inkFaint, fromArtwork)
        Log.i(TAG, "scene bake for \"$trackKey\" took ${SystemClock.elapsedRealtime() - t0} ms " +
            "(fromArtwork=$fromArtwork decode=${tDecode}ms palette=${tPalette}ms backdrop=${tBackdrop}ms)")
        scene
    }

    private fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, DISPLAY_PX)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
        return s
    }

    /** Average position of each swatch's colour across the cover, 0..1. */
    private fun position(labs: Array<Oklab>, w: Int, h: Int, swatches: List<Swatch>): List<PositionedSwatch> {
        if (swatches.isEmpty()) return emptyList()
        val sx = FloatArray(swatches.size); val sy = FloatArray(swatches.size); val n = IntArray(swatches.size)
        val centres = swatches.map { it.color }
        for (i in labs.indices) {
            val lab = labs[i]
            var best = 0; var bestD = Float.MAX_VALUE
            for (c in centres.indices) { val d = lab.distanceSq(centres[c]); if (d < bestD) { bestD = d; best = c } }
            sx[best] += (i % w).toFloat(); sy[best] += (i / w).toFloat(); n[best]++
        }
        return swatches.mapIndexed { idx, s ->
            val nx = if (n[idx] > 0) sx[idx] / n[idx] / w else 0.5f
            val ny = if (n[idx] > 0) sy[idx] / n[idx] / h else 0.5f
            PositionedSwatch(s.color, s.weight, nx, ny)
        }
    }

    /** A near-white at lightness [l], tinted toward [hue], lifted to ≥4.5:1 over [bgRgb]. */
    private fun tintedInk(l: Float, hue: Oklab, bgRgb: Int): Color {
        val chroma = 0.02f
        val dir = hue.chroma.takeIf { it > 1e-4f } ?: 1f
        var c = Oklab(l, hue.a / dir * chroma, hue.b / dir * chroma)
        // Lift lightness until the WCAG ratio clears 4.5:1.
        var guard = 0
        while (contrast(OklabConvert.toRgb(c), bgRgb) < 4.5f && c.l < 1f && guard < 20) {
            c = Oklab((c.l + 0.02f).coerceAtMost(1f), c.a, c.b); guard++
        }
        return Color(0xFF000000.toInt() or (OklabConvert.toRgb(c) and 0xFFFFFF))
    }

    private fun contrast(a: Int, b: Int): Float {
        val la = luminance(a); val lb = luminance(b)
        val hi = maxOf(la, lb); val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun luminance(rgb: Int): Float {
        fun ch(v: Int): Float { val c = v / 255f; return if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055) / 1.055), 2.4).toFloat() }
        return 0.2126f * ch((rgb ushr 16) and 0xFF) + 0.7152f * ch((rgb ushr 8) and 0xFF) + 0.0722f * ch(rgb and 0xFF)
    }
}
