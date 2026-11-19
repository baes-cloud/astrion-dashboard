package com.custom.astrion.cards.impl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.custom.astrion.ui.ambient.scene.StackBlur
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Step 5b (feature-flagged `"baked_glow_masks": true`):
 *
 * Pre-bakes a cove strip wash (`glow_line`) or hidden-strip halo (`glow_outline`)
 * into a 1/4-resolution `Bitmap.Config.ALPHA_8` texture (~10 KB apiece) off the
 * main thread using [StackBlur]. Replaces 32 stroked `drawPath` calls per pass
 * (96 paths per fixture across DstOut/Multiply/Screen) with 1 single alpha-tinted
 * `drawImage` blit, eliminating Android 8.1 HWUI PathCache exhaustion.
 *
 * Preserves [LitRooms]'s asymmetric halo feather + interior `DIFFERENCE` clip
 * and room-wall polygon clipping.
 */
internal object BakedGlowMasks {

    private const val SCALE = 0.25f

    fun bakeLineWash(
        fullW: Int,
        fullH: Int,
        line: List<Pair<Float, Float>>,
        reach: Float,
        roomShape: List<Pair<Float, Float>>?,
    ): ImageBitmap? {
        if (fullW < 16 || fullH < 16 || line.size < 2) return null
        val w = (fullW * SCALE).roundToInt().coerceIn(32, 180)
        val h = (fullH * SCALE).roundToInt().coerceIn(32, 180)
        val depth = reach * min(w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val path = Path().apply {
            moveTo(line[0].first * w, line[0].second * h)
            for (i in 1 until line.size) lineTo(line[i].first * w, line[i].second * h)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        // Build dense bright core + medium skirt, then Gaussian-converge via StackBlur.
        paint.strokeWidth = (depth * 0.22f).coerceAtLeast(1.5f)
        paint.alpha = 210
        canvas.drawPath(path, paint)
        paint.strokeWidth = (depth * 0.55f).coerceAtLeast(3f)
        paint.alpha = 115
        canvas.drawPath(path, paint)

        val blurRadius = (depth * 0.36f).roundToInt().coerceIn(2, 18)
        blurAndClip(bmp, w, h, blurRadius, roomShape, subtractInner = null)
        return toAlpha8ImageBitmap(bmp, w, h)
    }

    fun bakeOutlineHalo(
        fullW: Int,
        fullH: Int,
        outline: List<Pair<Float, Float>>,
        reach: Float,
        roomShape: List<Pair<Float, Float>>?,
    ): ImageBitmap? {
        if (fullW < 16 || fullH < 16 || outline.size < 3) return null
        val w = (fullW * SCALE).roundToInt().coerceIn(32, 180)
        val h = (fullH * SCALE).roundToInt().coerceIn(32, 180)
        val depth = reach * min(w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val outlinePath = closedPath(outline, w, h)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        // Outer glow stroke around the outline, blurred then clipped outside the
        // object while leaving a feathered inner edge (matching LitRooms HALO_FEATHER).
        paint.strokeWidth = (depth * 1.15f).coerceAtLeast(2f)
        paint.alpha = 205
        canvas.drawPath(outlinePath, paint)

        val blurRadius = (depth * 0.42f).roundToInt().coerceIn(2, 14)
        blurAndClip(bmp, w, h, blurRadius, roomShape, subtractInner = outlinePath)

        // Draw subtle inner feather stroke onto the edge (matches LitRooms HALO_FEATHER = 0.3f).
        val featherCanvas = Canvas(bmp)
        roomShape?.takeIf { it.size >= 3 }?.let { featherCanvas.clipPath(closedPath(it, w, h)) }
        paint.strokeWidth = (depth * 0.45f).coerceAtLeast(1.2f)
        paint.alpha = 88
        featherCanvas.drawPath(outlinePath, paint)

        return toAlpha8ImageBitmap(bmp, w, h)
    }

    private fun blurAndClip(
        bmp: Bitmap,
        w: Int,
        h: Int,
        blurRadius: Int,
        roomShape: List<Pair<Float, Float>>?,
        subtractInner: Path?,
    ) {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        StackBlur.blur(px, w, h, radius = blurRadius, passes = 3)
        bmp.setPixels(px, 0, w, 0, 0, w, h)

        val canvas = Canvas(bmp)
        if (subtractInner != null) {
            val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            canvas.drawPath(subtractInner, clearPaint)
        }
        if (roomShape != null && roomShape.size >= 3) {
            val roomPath = closedPath(roomShape, w, h)
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = 0xFFFFFFFF.toInt()
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            canvas.drawPath(roomPath, maskPaint)
        }
    }

    private fun toAlpha8ImageBitmap(argb: Bitmap, w: Int, h: Int): ImageBitmap {
        val px = IntArray(w * h)
        argb.getPixels(px, 0, w, 0, 0, w, h)
        argb.recycle()
        val alphaBytes = ByteArray(w * h) { i -> ((px[i] ushr 24) and 0xFF).toByte() }
        val alphaBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        alphaBmp.copyPixelsFromBuffer(ByteBuffer.wrap(alphaBytes))
        return alphaBmp.asImageBitmap()
    }

    private fun closedPath(points: List<Pair<Float, Float>>, w: Int, h: Int): Path = Path().apply {
        moveTo(points[0].first * w, points[0].second * h)
        for (i in 1 until points.size) lineTo(points[i].first * w, points[i].second * h)
        close()
    }
}
