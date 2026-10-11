package com.custom.astrion.ui.ambient.scene

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The stand-in when a track has no artwork (or it failed to load): a
 * Chladni-style figure — two or three wavy concentric nodal rings drawn as fine
 * grains on a dark ground — in colours derived from the track itself.
 *
 * Deterministic for a given `title + artist`: the same track always draws the
 * same figure, so it never shimmers between samples. A small seeded RNG does
 * the grain scatter; nothing here touches the clock or any global state.
 *
 * Returns both the figure (to show where the cover would go) and the swatches
 * taken from its own colours, so the backdrop pools match it.
 */
object ChladniArt {

    data class Result(val cover: Bitmap, val swatches: List<Swatch>)

    fun of(key: String, size: Int = 400): Result {
        val rnd = SmallRng(hash(key))

        // Two or three colours off a hash-chosen hue, moderate L so grains read
        // on the dark ground without glaring.
        val baseHue = rnd.nextFloat() * 2f * PI.toFloat()
        val count = 2 + (rnd.nextInt(2)) // 2 or 3
        val swatches = (0 until count).map { i ->
            val hue = baseHue + i * (0.6f + rnd.nextFloat() * 0.5f)
            val chroma = 0.07f + rnd.nextFloat() * 0.06f
            val l = 0.40f + rnd.nextFloat() * 0.12f
            Swatch(Oklab(l, chroma * cos(hue), chroma * sin(hue)), population = 1f / count, weight = 1f - i * 0.2f)
        }

        val ground = 0xFF0C0E10.toInt()
        val px = IntArray(size * size) { ground }

        // Ring parameters: 2–3 nodal rings with a gentle angular wobble.
        val rings = 2 + rnd.nextInt(2)
        val freq = (rings * 2 + 1) * (PI.toFloat() / size)
        val wobbleM = 3 + rnd.nextInt(4)
        val wobbleAmp = 0.18f + rnd.nextFloat() * 0.22f
        val phase = rnd.nextFloat() * 2f * PI.toFloat()
        val cx = size / 2f; val cy = size / 2f
        val maxR = size / 2f - 4f

        // Scatter grains, keeping those that land on a nodal line (|f| small).
        // Density is a cost knob: this runs on the weak SoC when art fails to
        // load, so keep it light — the figure still reads at this grain count.
        val grains = size * size / 18
        repeat(grains) {
            val x = rnd.nextFloat() * size
            val y = rnd.nextFloat() * size
            val dx = x - cx; val dy = y - cy
            val r = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (r > maxR) return@repeat
            val theta = kotlin.math.atan2(dy, dx)
            val f = sin(freq * r + wobbleAmp * sin(wobbleM * theta + phase) + phase)
            if (kotlin.math.abs(f) < 0.14f) {
                val s = swatches[(r / maxR * swatches.size).toInt().coerceIn(0, swatches.size - 1)]
                // Fade grains out toward the edge.
                val a = (0.85f - 0.5f * (r / maxR)).coerceIn(0.2f, 0.85f)
                val col = OklabConvert.toRgb(Oklab((s.color.l + 0.25f).coerceAtMost(0.85f), s.color.a, s.color.b))
                val i = y.toInt().coerceIn(0, size - 1) * size + x.toInt().coerceIn(0, size - 1)
                px[i] = blend(px[i], col, a)
            }
        }

        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return Result(bmp, swatches)
    }

    private fun blend(dst: Int, srcRgb: Int, a: Float): Int {
        val dr = (dst ushr 16) and 0xFF; val dg = (dst ushr 8) and 0xFF; val db = dst and 0xFF
        val sr = (srcRgb ushr 16) and 0xFF; val sg = (srcRgb ushr 8) and 0xFF; val sb = srcRgb and 0xFF
        val r = (sr * a + dr * (1 - a)).toInt()
        val g = (sg * a + dg * (1 - a)).toInt()
        val b = (sb * a + db * (1 - a)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** FNV-1a: stable across runs and processes, unlike String.hashCode seeds nowhere here. */
    private fun hash(s: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV offset basis
        for (c in s) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
        return h
    }

    /** A tiny deterministic xorshift, so the figure needs no platform RNG. */
    private class SmallRng(seed: Long) {
        private var s = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed
        private fun next(): Long { s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17); return s }
        fun nextFloat(): Float = ((next() ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()
        fun nextInt(bound: Int): Int = (Math.floorMod(next(), bound.toLong())).toInt()
    }
}
