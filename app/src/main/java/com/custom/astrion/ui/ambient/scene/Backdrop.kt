package com.custom.astrion.ui.ambient.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/** A cover swatch with roughly where in the cover it sits (0..1 of the cover). */
data class PositionedSwatch(val color: Oklab, val weight: Float, val nx: Float, val ny: Float)

/**
 * Bakes the backdrop: a deep ground plus a few soft radial colour pools placed
 * where each colour sits in the cover, a darker veil under the text, and a halo
 * where the cover will sit — then blurs the lot on the CPU.
 *
 * Tiny on purpose ([W]×[H] = 120×200) so the per-track bake and the CPU blur
 * are cheap; it is drawn scaled up to the full 480×800 screen with filtering,
 * where the softness hides the upscale. Produced once per track off the main
 * thread; per frame only its alpha/transform change.
 */
object Backdrop {
    const val W = 120
    const val H = 200

    // Screen geometry the pools and halo are positioned against (see brief).
    private const val SCREEN_W = 480f
    private const val SCREEN_H = 800f
    private const val COVER = 400f
    private const val COVER_TOP = 72f
    private const val COVER_LEFT = (SCREEN_W - COVER) / 2f

    /** Lightness window that keeps text readable over any pool. */
    private const val POOL_L_LO = 0.15f
    private const val POOL_L_HI = 0.50f

    fun build(swatches: List<PositionedSwatch>, ground: Oklab): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Deep ground, slightly tinted with the cover's dominant hue.
        val g = OklabConvert.clampLightness(ground, 0.12f, 0.20f)
        canvas.drawColor(0xFF000000.toInt() or (OklabConvert.toRgb(g) and 0xFFFFFF))

        // Colour pools, heaviest first, where each colour sits in the cover.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        swatches.take(5).forEachIndexed { i, s ->
            val c = OklabConvert.clampLightness(s.color, POOL_L_LO, POOL_L_HI)
            val rgb = OklabConvert.toRgb(c) and 0xFFFFFF
            val cx = s.nx * W
            val cy = s.ny * H
            val radius = (0.35f + 0.55f * s.weight.coerceIn(0f, 1f)) * W
            val alpha = (0xE0 - i * 0x18).coerceAtLeast(0x60)
            paint.shader = RadialGradient(
                cx, cy, radius.coerceAtLeast(12f),
                (alpha shl 24) or rgb, rgb and 0xFFFFFF, // centre opaque-ish → transparent edge
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        }
        paint.shader = null

        // Halo where the cover sits: the pools' own brightest colour, bled around
        // the cover's footprint so its edges glow rather than cut hard.
        val halo = swatches.maxByOrNull { it.color.chroma }
        if (halo != null) {
            val rgb = OklabConvert.toRgb(OklabConvert.clampLightness(halo.color, 0.25f, 0.55f)) and 0xFFFFFF
            val cx = (COVER_LEFT + COVER / 2f) / SCREEN_W * W
            val cy = (COVER_TOP + COVER / 2f) / SCREEN_H * H
            val r = COVER / SCREEN_W * W * 0.75f
            paint.shader = RadialGradient(cx, cy, r, (0x55 shl 24) or rgb, rgb and 0xFFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
            paint.shader = null
        }

        // Darker veil under the text area (below the cover), so the three text
        // levels keep their contrast.
        val veilTop = (COVER_TOP + COVER) / SCREEN_H * H - 6f
        paint.shader = LinearGradient(
            0f, veilTop, 0f, H.toFloat(),
            0x00000000, 0xC8000000.toInt(), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, veilTop, W.toFloat(), H.toFloat(), paint)
        paint.shader = null

        // Blur it all on the CPU: no RenderEffect on API 27.
        val px = IntArray(W * H)
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        StackBlur.blur(px, W, H, radius = 9, passes = 3)
        bmp.setPixels(px, 0, W, 0, 0, W, H)
        return bmp
    }
}
