package com.custom.astrion.ui.ambient.scene

/**
 * A CPU blur for the backdrop. The HA100 is API 27 with no `RenderEffect`, so
 * `Modifier.blur` is a no-op; the soft colour pools have to be blurred on the
 * CPU once per track instead.
 *
 * This is a separable box blur run as a few passes, which converges on a
 * Gaussian and is plenty for diffuse pools. It works on the tiny bake bitmap
 * (120×200), never per frame, so it is cheap. Operates on a plain `IntArray` of
 * 0xAARRGGBB so it carries no android.graphics dependency.
 */
object StackBlur {

    /**
     * Blur [pixels] (row-major, [width]×[height], 0xAARRGGBB) in place.
     * @param radius box half-width in pixels; 0 or less is a no-op.
     * @param passes how many box passes (3 ≈ Gaussian).
     */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 3) {
        if (radius < 1 || width < 1 || height < 1) return
        repeat(passes) {
            boxBlurH(pixels, width, height, radius)
            boxBlurV(pixels, width, height, radius)
        }
    }

    private fun boxBlurH(px: IntArray, w: Int, h: Int, r: Int) {
        val win = 2 * r + 1
        val row = IntArray(w)
        for (y in 0 until h) {
            val base = y * w
            System.arraycopy(px, base, row, 0, w)
            var a = 0; var rr = 0; var gg = 0; var bb = 0
            // Prime the window with the clamped left edge.
            for (i in -r..r) {
                val c = row[i.coerceIn(0, w - 1)]
                a += (c ushr 24) and 0xFF; rr += (c ushr 16) and 0xFF; gg += (c ushr 8) and 0xFF; bb += c and 0xFF
            }
            for (x in 0 until w) {
                px[base + x] = ((a / win) shl 24) or ((rr / win) shl 16) or ((gg / win) shl 8) or (bb / win)
                val out = row[(x - r).coerceIn(0, w - 1)]
                val inc = row[(x + r + 1).coerceIn(0, w - 1)]
                a += ((inc ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                rr += ((inc ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
                gg += ((inc ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
                bb += (inc and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun boxBlurV(px: IntArray, w: Int, h: Int, r: Int) {
        val win = 2 * r + 1
        val col = IntArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = px[y * w + x]
            var a = 0; var rr = 0; var gg = 0; var bb = 0
            for (i in -r..r) {
                val c = col[i.coerceIn(0, h - 1)]
                a += (c ushr 24) and 0xFF; rr += (c ushr 16) and 0xFF; gg += (c ushr 8) and 0xFF; bb += c and 0xFF
            }
            for (y in 0 until h) {
                px[y * w + x] = ((a / win) shl 24) or ((rr / win) shl 16) or ((gg / win) shl 8) or (bb / win)
                val out = col[(y - r).coerceIn(0, h - 1)]
                val inc = col[(y + r + 1).coerceIn(0, h - 1)]
                a += ((inc ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                rr += ((inc ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
                gg += ((inc ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
                bb += (inc and 0xFF) - (out and 0xFF)
            }
        }
    }
}
