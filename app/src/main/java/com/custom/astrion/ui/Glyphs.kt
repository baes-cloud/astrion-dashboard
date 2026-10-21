package com.custom.astrion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Clean line icons on a 24-unit grid (1.75 stroke, round caps/joins) tinted by
 * [androidx.compose.material3.Icon].
 */
object Glyphs {
    val bulb: ImageVector by lazy { glyph("bulb", P("M9 18h6M10 21h4", INK), P("M12 3a6 6 0 0 0-3.6 10.8c.7.6 1.1 1.3 1.1 2.2h5c0-.9.4-1.6 1.1-2.2A6 6 0 0 0 12 3z", INK)) }
    val note: ImageVector by lazy { glyph("note", P("M9 18V6l10-2v12", INK), P("M4 18a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z", INK), P("M14 16a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z", INK)) }
    val speaker: ImageVector by lazy { glyph("speaker", P("M8.5 3h7a2.5 2.5 0 0 1 2.5 2.5v13a2.5 2.5 0 0 1 -2.5 2.5h-7a2.5 2.5 0 0 1 -2.5 -2.5v-13a2.5 2.5 0 0 1 2.5 -2.5z", INK), P("M8.8 14a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0z", INK), P("M12 7.2h.01", INK)) }
    val vol: ImageVector by lazy { glyph("vol", P("M4 9.5h3.5L12 6v12l-4.5-3.5H4z", INK), P("M15.5 9.2a4 4 0 0 1 0 5.6M18.2 6.6a7.6 7.6 0 0 1 0 10.8", INK)) }
    val mute: ImageVector by lazy { glyph("mute", P("M4 9.5h3.5L12 6v12l-4.5-3.5H4z", INK), P("M16 9.5l5 5M21 9.5l-5 5", INK)) }
    val close: ImageVector by lazy { glyph("close", P("M6.5 6.5l11 11M17.5 6.5l-11 11", INK)) }
    val back: ImageVector by lazy { glyph("back", P("M15 5l-7 7 7 7", INK)) }
    val fwd: ImageVector by lazy { glyph("fwd", P("M9 5l7 7-7 7", INK)) }
    val drop: ImageVector by lazy { glyph("drop", P("M12 3.5c3 4 5.5 7 5.5 10a5.5 5.5 0 0 1-11 0c0-3 2.5-6 5.5-10z", INK)) }
    val link: ImageVector by lazy { glyph("link", P("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", INK), P("M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1", INK)) }
    val queue: ImageVector by lazy { glyph("queue", P("M4 7h11M4 12h11M4 17h7M18 14v6M15 17h6", INK)) }
    val list: ImageVector by lazy { glyph("list", P("M8 7h12M8 12h12M8 17h12M4 7h.01M4 12h.01M4 17h.01", INK)) }
    val power: ImageVector by lazy { glyph("power", P("M12 3.5v8", INK), P("M7.2 6.6a7 7 0 1 0 9.6 0", INK)) }
    val pause: ImageVector by lazy { glyph("pause", P("M9 5.5v13M15 5.5v13", INK)) }
    val play: ImageVector by lazy { glyph("play", P("M8 5.5v13l10.5-6.5z", INK, fill = INK)) }
    val prev: ImageVector by lazy { glyph("prev", P("M6 5.5v13", INK), P("M19 5.5v13L9 12z", INK, fill = INK)) }
    val next: ImageVector by lazy { glyph("next", P("M18 5.5v13", INK), P("M5 5.5v13L15 12z", INK, fill = INK)) }
}

private val INK = Color.Black

private class P(val d: String, val stroke: Color, val fill: Color? = null)

private fun glyph(name: String, vararg paths: P): ImageVector {
    val b = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    paths.forEach { p ->
        b.addPath(
            pathData = PathParser().parsePathString(p.d).toNodes(),
            fill = p.fill?.let { SolidColor(it) },
            stroke = SolidColor(p.stroke),
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}
