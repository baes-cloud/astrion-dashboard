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

    // Floorplan fixtures, for lights that aren't a bulb.
    val tableLamp: ImageVector by lazy { glyph("tableLamp", P("M8 4h8l3 8H5z", INK), P("M12 12v7M8.5 20h7", INK)) }
    val ledStrip: ImageVector by lazy { glyph("ledStrip", P("M3.5 10h17a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-17a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1z", INK), P("M6.5 12h.01M10 12h.01M14 12h.01M17.5 12h.01", INK)) }
    val hueStrip: ImageVector by lazy { glyph("hueStrip", P("M3.5 12h17a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-17a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1z", INK), P("M7 8.5l-1-2.5M12 8.5V5.5M17 8.5l1-2.5", INK)) }
    val underglow: ImageVector by lazy { glyph("underglow", P("M5 7h14a1.5 1.5 0 0 1 1.5 1.5v3.5h-17v-3.5a1.5 1.5 0 0 1 1.5 -1.5z", INK), P("M6 15.5l-1.2 3M12 15.5v3.5M18 15.5l1.2 3", INK)) }
    val spotlight: ImageVector by lazy { glyph("spotlight", P("M10 3.5h4v2.5l2.5 3.5h-9L10 6z", INK), P("M8.5 13l-2 5.5M12 13v6M15.5 13l2 5.5", INK)) }
    val candles: ImageVector by lazy { glyph("candles", P("M4.5 13h3v7h-3zM10.5 10h3v10h-3zM16.5 14h3v6h-3z", INK), P("M6 10.5v-1.5M12 7.5v-2M18 11.5v-1.5", INK)) }
    val downlight: ImageVector by lazy { glyph("downlight", P("M6 12a6 6 0 1 0 12 0a6 6 0 1 0 -12 0z", INK), P("M9.75 12a2.25 2.25 0 1 0 4.5 0a2.25 2.25 0 1 0 -4.5 0z", INK)) }
    val playbar: ImageVector by lazy { glyph("playbar", P("M10.5 3h3v14h-3z", INK), P("M12 17v3.5M9 20.5h6", INK), P("M7.5 7.5l-2-1M7.5 11.5H5M16.5 7.5l2-1M16.5 11.5H19", INK)) }
    val glowStrip: ImageVector by lazy { glyph("glowStrip", P("M3.5 10.5h17a1 1 0 0 1 1 1v1a1 1 0 0 1 -1 1h-17a1 1 0 0 1 -1 -1v-1a1 1 0 0 1 1 -1z", INK), P("M6 7.5l-.8-2M12 7V4.5M18 7.5l.8-2M6 16.5l-.8 2M12 17v2.5M18 16.5l.8 2", INK)) }
    val zigzag: ImageVector by lazy { glyph("zigzag", P("M2.5 16l4.75-8 4.75 8 4.75-8 4.75 8", INK)) }
    val roundel: ImageVector by lazy { glyph("roundel", P("M5.5 12a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0z", INK), P("M2.5 10h19v4h-19z", INK)) }
    val twoArtworks: ImageVector by lazy { glyph("twoArtworks", P("M2.5 7h8.5v10H2.5zM13 7h8.5v10H13z", INK), P("M2.5 15l3-3 2.5 2.5M13 15l3-3 2.5 2.5", INK)) }
    val portraitArt: ImageVector by lazy { glyph("portraitArt", P("M6 3h12v18H6z", INK), P("M6 17l4-4 3 3 2-2 3 3", INK), P("M14.5 7.5h.01", INK)) }
    val bulbs: ImageVector by lazy { glyph("bulbs", P("M6 3v4.5M12 3v2.5M18 3v4.5", INK), P("M3.5 10a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0zM9.5 8a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0zM15.5 10a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z", INK), P("M5 14.5h2M11 12.5h2M17 14.5h2", INK)) }
    val sofa: ImageVector by lazy { glyph("sofa", P("M5 11V8a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v3", INK), P("M3 11a1.5 1.5 0 0 1 3 0v2h12v-2a1.5 1.5 0 0 1 3 0v5H3z", INK), P("M5 16v2M19 16v2", INK)) }
    val xmasTree: ImageVector by lazy { glyph("xmasTree", P("M12 3l4.5 5.5h-2.5l3.5 4.5h-2.5l3.5 4.5h-13l3.5-4.5h-2.5l3.5-4.5h-2.5z", INK), P("M12 17.5v3", INK)) }
    val desk: ImageVector by lazy { glyph("desk", P("M4 4.5h16v10.5H4z", INK), P("M12 15v3.5M8 20h8", INK)) }
    val bulbGrid: ImageVector by lazy { glyph("bulbGrid", P("M2.75 7a2.25 2.25 0 1 0 4.5 0a2.25 2.25 0 1 0 -4.5 0zM9.75 7a2.25 2.25 0 1 0 4.5 0a2.25 2.25 0 1 0 -4.5 0zM16.75 7a2.25 2.25 0 1 0 4.5 0a2.25 2.25 0 1 0 -4.5 0zM2.75 15a2.25 2.25 0 1 0 4.5 0a2.25 2.25 0 1 0 -4.5 0zM9.75 15a2.25 2.25 0 1 0 4.5 0a2.25 2.25 0 1 0 -4.5 0zM16.75 15a2.25 2.25 0 1 0 4.5 0a2.25 2.25 0 1 0 -4.5 0z", INK), P("M4 10.5h2M11 10.5h2M18 10.5h2M4 18.5h2M11 18.5h2M18 18.5h2", INK)) }
    val artBlob: ImageVector by lazy { glyph("artBlob", P("M7 4.5c3.5-1.5 8-1 10.5 1.5s2.5 7 1 10-5.5 4.5-9 3.5S3.5 15.5 3.5 12 3.5 6 7 4.5z", INK), P("M6.5 15l3.5-3.5 2.5 2.5 2-2 3 3", INK), P("M14.5 8.5h.01", INK)) }
    val mirrorball: ImageVector by lazy { glyph("mirrorball", P("M12 2.5v3.5", INK), P("M4.5 13.5a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0 -15 0z", INK), P("M5.2 10.5h13.6M5.2 16.5h13.6M12 6c-2.6 2-2.6 13 0 15M12 6c2.6 2 2.6 13 0 15", INK)) }
    val ledBar: ImageVector by lazy { glyph("ledBar", P("M4 9h16a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1 -1.5 1.5H4a1.5 1.5 0 0 1 -1.5 -1.5v-4A1.5 1.5 0 0 1 4 9z", INK), P("M5 11.25h2v2.5H5zM9.33 11.25h2v2.5h-2zM12.67 11.25h2v2.5h-2zM17 11.25h2v2.5h-2z", INK, fill = INK), P("M6 6.5l-.6-2M12 6V3.8M18 6.5l.6-2", INK)) }
    val artwork: ImageVector by lazy { glyph("artwork", P("M4 5h16v14H4z", INK), P("M4 16l5-5 4 4 2-2 5 5", INK), P("M15.5 9h.01", INK)) }
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
