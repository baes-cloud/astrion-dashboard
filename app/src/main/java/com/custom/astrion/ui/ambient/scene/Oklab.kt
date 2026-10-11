package com.custom.astrion.ui.ambient.scene

import kotlin.math.hypot
import kotlin.math.pow

/**
 * The Oklab colour space (Björn Ottosson, 2020), used for all the ambient
 * scene's colour reasoning: clustering a cover's palette and keeping pools far
 * enough apart to read as distinct.
 *
 * Oklab is perceptually uniform, so a plain Euclidean distance between two
 * [Oklab] matches how different they look — which sRGB distance does not. That
 * is exactly what the palette and the backdrop need: "0.09 apart" means the
 * same thing for a pair of blues as for a blue and an orange.
 *
 * Kept free of any `android.graphics` type on purpose, so the colour maths
 * runs under a plain JVM unit test (there is no Robolectric here).
 *
 * L is lightness (0 = black, ≈1 = white); [a] green→red, [b] blue→yellow; both
 * roughly in ±0.4 for real colours. [chroma] is how colourful it is.
 */
data class Oklab(val l: Float, val a: Float, val b: Float) {
    /** Colourfulness: 0 for any grey, larger the more saturated. */
    val chroma: Float get() = hypot(a, b)

    /** Perceptual distance to another colour, the thing "0.09 apart" measures. */
    fun distance(o: Oklab): Float = hypot(hypot(l - o.l, a - o.a), b - o.b)

    /**
     * Squared distance: same ordering as [distance] but no square root, for the
     * hot nearest-centre loops in clustering where only "which is closest"
     * matters. Compare against a squared threshold.
     */
    fun distanceSq(o: Oklab): Float {
        val dl = l - o.l; val da = a - o.a; val db = b - o.b
        return dl * dl + da * da + db * db
    }
}

/** sRGB ↔ Oklab. Colours are packed 0xRRGGBB ints (the top byte is ignored). */
object OklabConvert {

    fun red(rgb: Int): Int = (rgb shr 16) and 0xFF
    fun green(rgb: Int): Int = (rgb shr 8) and 0xFF
    fun blue(rgb: Int): Int = rgb and 0xFF
    fun rgb(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** 8-bit sRGB → linear, precomputed: the forward path runs this per pixel. */
    private val LINEAR = FloatArray(256) { linearize(it) }

    private fun linearize(c8: Int): Float {
        val c = c8 / 255f
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    /** One 8-bit sRGB channel to linear light (0..1), from the lookup table. */
    private fun toLinear(c8: Int): Float = LINEAR[c8 and 0xFF]

    /** Linear light (0..1) back to an 8-bit sRGB channel. */
    private fun toSrgb8(c: Float): Int {
        val x = c.coerceIn(0f, 1f)
        val s = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
        return (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun cbrtf(x: Float): Float = Math.cbrt(x.toDouble()).toFloat()

    fun toOklab(rgb: Int): Oklab = toOklab(red(rgb), green(rgb), blue(rgb))

    fun toOklab(r8: Int, g8: Int, b8: Int): Oklab {
        val r = toLinear(r8); val g = toLinear(g8); val b = toLinear(b8)
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val l_ = cbrtf(l); val m_ = cbrtf(m); val s_ = cbrtf(s)
        return Oklab(
            l = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_,
            a = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_,
            b = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_,
        )
    }

    /** Oklab to a packed 0xRRGGBB int, out-of-gamut colours clamped per channel. */
    fun toRgb(c: Oklab): Int {
        val l_ = c.l + 0.3963377774f * c.a + 0.2158037573f * c.b
        val m_ = c.l - 0.1055613458f * c.a - 0.0638541728f * c.b
        val s_ = c.l - 0.0894841775f * c.a - 1.2914855480f * c.b
        val l = l_ * l_ * l_; val m = m_ * m_ * m_; val s = s_ * s_ * s_
        val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val b = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        return rgb(toSrgb8(r), toSrgb8(g), toSrgb8(b))
    }

    /** The same colour with its lightness forced into [lo, hi], hue and chroma kept. */
    fun clampLightness(c: Oklab, lo: Float, hi: Float): Oklab =
        if (c.l in lo..hi) c else Oklab(c.l.coerceIn(lo, hi), c.a, c.b)
}
