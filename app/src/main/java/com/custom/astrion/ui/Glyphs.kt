package com.custom.astrion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.custom.astrion.ui.theme.AstrionColors

/**
 * The redesign's line icons: 24-unit grid, 1.75 stroke, round ends, drawn in
 * one ink that [androidx.compose.material3.Icon] tints. The weather glyphs
 * are the exception: they carry their own sky colours (sun gold, cloud
 * sailcloth, rain blue), so draw those with `tint = Color.Unspecified`.
 *
 * Built lazily from path strings, so an icon that is never shown costs nothing.
 */
object Glyphs {
    val bulb: ImageVector by lazy { glyph("bulb", P("M9 18h6M10 21h4", INK), P("M12 3a6 6 0 0 0-3.6 10.8c.7.6 1.1 1.3 1.1 2.2h5c0-.9.4-1.6 1.1-2.2A6 6 0 0 0 12 3z", INK)) }
    val lock: ImageVector by lazy { glyph("lock", P("M7 10.5h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z", INK), P("M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5", INK)) }
    val unlock: ImageVector by lazy { glyph("unlock", P("M7 10.5h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z", INK), P("M8.5 10.5V8a3.5 3.5 0 0 1 6.7-1.4", INK)) }
    /** A bulb with a walking figure: lights that follow motion. */
    val automations: ImageVector by lazy {
        glyph(
            "automations",
            P("M7.5 3a4 4 0 0 0-2.4 7.2c.5.4.8 1 .8 1.6v.7h3.2v-.7c0-.6.3-1.2.8-1.6A4 4 0 0 0 7.5 3z", INK),
            P("M6 15.5h3", INK),
            P("M15.9 4.5a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z", INK),
            P("M17 8l-1.6 4.8 2.4 2.6V20M15.4 12.8L14 15.5M17 8l2.4 2.6 1.8.4M17.8 15.4l-2.6 4.6", INK),
        )
    }
    /** A door with a closed padlock. */
    val doorLocked: ImageVector by lazy {
        glyph(
            "doorLocked",
            P("M5 21V4.5A1.5 1.5 0 0 1 6.5 3h8A1.5 1.5 0 0 1 16 4.5V11M3 21h10", INK),
            P("M11.8 12a.8.8 0 1 0 1.6 0a.8.8 0 1 0 -1.6 0z", INK, INK),
            P("M15.8 15h4.4a1.3 1.3 0 0 1 1.3 1.3v3.4a1.3 1.3 0 0 1 -1.3 1.3h-4.4a1.3 1.3 0 0 1 -1.3 -1.3v-3.4a1.3 1.3 0 0 1 1.3 -1.3z", INK),
            P("M16 15v-1.5a2 2 0 0 1 4 0V15", INK),
        )
    }
    /** A door with its padlock open. */
    val doorUnlocked: ImageVector by lazy {
        glyph(
            "doorUnlocked",
            P("M5 21V4.5A1.5 1.5 0 0 1 6.5 3h8A1.5 1.5 0 0 1 16 4.5V11M3 21h10", INK),
            P("M11.8 12a.8.8 0 1 0 1.6 0a.8.8 0 1 0 -1.6 0z", INK, INK),
            P("M15.8 15h4.4a1.3 1.3 0 0 1 1.3 1.3v3.4a1.3 1.3 0 0 1 -1.3 1.3h-4.4a1.3 1.3 0 0 1 -1.3 -1.3v-3.4a1.3 1.3 0 0 1 1.3 -1.3z", INK),
            P("M16 15v-1.5a2 2 0 0 1 3.9-.6", INK),
        )
    }
    val motion: ImageVector by lazy { glyph("motion", P("M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z", INK), P("M8 8a5.7 5.7 0 0 0 0 8M16 8a5.7 5.7 0 0 1 0 8M5.2 5.2a9.6 9.6 0 0 0 0 13.6M18.8 5.2a9.6 9.6 0 0 1 0 13.6", INK)) }
    val paused: ImageVector by lazy { glyph("paused", P("M9.5 8v8M14.5 8v8", INK), P("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0z", INK)) }
    val pause: ImageVector by lazy { glyph("pause", P("M9 5.5v13M15 5.5v13", INK)) }
    val play: ImageVector by lazy { glyph("play", P("M8 5.5v13l10.5-6.5z", INK, fill = INK)) }
    val prev: ImageVector by lazy { glyph("prev", P("M6 5.5v13", INK), P("M19 5.5v13L9 12z", INK, fill = INK)) }
    val next: ImageVector by lazy { glyph("next", P("M18 5.5v13", INK), P("M5 5.5v13L15 12z", INK, fill = INK)) }
    val search: ImageVector by lazy { glyph("search", P("M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0z", INK), P("M20 20l-4.4-4.4", INK)) }
    val film: ImageVector by lazy { glyph("film", P("M5.5 5h13a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-13a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z", INK), P("M3.5 9.5h17M8 5l2.2 4.5M13.2 5l2.2 4.5", INK)) }
    val tv: ImageVector by lazy { glyph("tv", P("M5 4.5h14a2 2 0 0 1 2 2v8.5a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-8.5a2 2 0 0 1 2 -2z", INK), P("M8.5 20.5h7", INK)) }
    val mtv: ImageVector by lazy { glyph("mtv", P("M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z", INK), P("M10 9.3v5.4l4.5-2.7z", INK, fill = INK)) }
    val note: ImageVector by lazy { glyph("note", P("M9 18V6l10-2v12", INK), P("M4 18a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z", INK), P("M14 16a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z", INK)) }
    val plan: ImageVector by lazy { glyph("plan", P("M5 3.5h14a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1 -1.5 1.5h-14a1.5 1.5 0 0 1 -1.5 -1.5v-14a1.5 1.5 0 0 1 1.5 -1.5z", INK), P("M3.5 12h7.5v8.5M14 3.5V9M14 12.5h6.5", INK)) }
    val thermo: ImageVector by lazy { glyph("thermo", P("M10 14.3V5.5a2 2 0 0 1 4 0v8.8a4 4 0 1 1-4 0z", INK), P("M12 9.5v7", INK)) }
    val speaker: ImageVector by lazy { glyph("speaker", P("M8.5 3h7a2.5 2.5 0 0 1 2.5 2.5v13a2.5 2.5 0 0 1 -2.5 2.5h-7a2.5 2.5 0 0 1 -2.5 -2.5v-13a2.5 2.5 0 0 1 2.5 -2.5z", INK), P("M8.8 14a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0z", INK), P("M12 7.2h.01", INK)) }
    val vol: ImageVector by lazy { glyph("vol", P("M4 9.5h3.5L12 6v12l-4.5-3.5H4z", INK), P("M15.5 9.2a4 4 0 0 1 0 5.6M18.2 6.6a7.6 7.6 0 0 1 0 10.8", INK)) }
    val mute: ImageVector by lazy { glyph("mute", P("M4 9.5h3.5L12 6v12l-4.5-3.5H4z", INK), P("M16 9.5l5 5M21 9.5l-5 5", INK)) }
    val plus: ImageVector by lazy { glyph("plus", P("M12 5v14M5 12h14", INK)) }
    val minus: ImageVector by lazy { glyph("minus", P("M5 12h14", INK)) }
    val power: ImageVector by lazy { glyph("power", P("M12 3.5v8", INK), P("M7.2 6.6a7 7 0 1 0 9.6 0", INK)) }
    val up: ImageVector by lazy { glyph("up", P("M6 14.5l6-6 6 6", INK)) }
    val down: ImageVector by lazy { glyph("down", P("M6 9.5l6 6 6-6", INK)) }
    val stop: ImageVector by lazy { glyph("stop", P("M8.5 7h7a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1 -1.5 1.5h-7a1.5 1.5 0 0 1 -1.5 -1.5v-7a1.5 1.5 0 0 1 1.5 -1.5z", INK)) }
    val close: ImageVector by lazy { glyph("close", P("M6.5 6.5l11 11M17.5 6.5l-11 11", INK)) }
    val back: ImageVector by lazy { glyph("back", P("M15 5l-7 7 7 7", INK)) }
    val fwd: ImageVector by lazy { glyph("fwd", P("M9 5l7 7-7 7", INK)) }
    val calendar: ImageVector by lazy { glyph("calendar", P("M6 5.5h12a2 2 0 0 1 2 2v10.5a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-10.5a2 2 0 0 1 2 -2z", INK), P("M4 10h16M8.5 3.5v4M15.5 3.5v4", INK)) }
    val alarm: ImageVector by lazy { glyph("alarm", P("M5 13a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z", INK), P("M12 9.5V13l2.5 2M4.5 5.8l3-2.5M19.5 5.8l-3-2.5", INK)) }
    val snooze: ImageVector by lazy { glyph("snooze", P("M5 13a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z", INK), P("M9.6 10.6h4.8l-4.8 4.8h4.8M4.5 5.8l3-2.5M19.5 5.8l-3-2.5", INK)) }
    val washer: ImageVector by lazy { glyph("washer", P("M7.5 3h9a2.5 2.5 0 0 1 2.5 2.5v13a2.5 2.5 0 0 1 -2.5 2.5h-9a2.5 2.5 0 0 1 -2.5 -2.5v-13a2.5 2.5 0 0 1 2.5 -2.5z", INK), P("M8 13.5a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z", INK), P("M8.5 6.5h.01M11 6.5h.01", INK)) }
    val drop: ImageVector by lazy { glyph("drop", P("M12 3.5c3 4 5.5 7 5.5 10a5.5 5.5 0 0 1-11 0c0-3 2.5-6 5.5-10z", INK)) }
    val vacuum: ImageVector by lazy { glyph("vacuum", P("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0z", INK), P("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", INK), P("M8.5 5.8h7", INK)) }
    val dock: ImageVector by lazy { glyph("dock", P("M4 11l8-6.5 8 6.5", INK), P("M6.5 9.5V19.5h11V9.5", INK)) }
    val locate: ImageVector by lazy { glyph("locate", P("M7.5 12a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0z", INK), P("M12 3v3M12 18v3M3 12h3M18 12h3", INK)) }
    val fan: ImageVector by lazy { glyph("fan", P("M10.4 12a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z", INK), P("M12 10.4c-1-4 .8-7 3.4-6.5 2 .4 1.2 4-2 6.3M13.6 12c4-1 7 .8 6.5 3.4-.4 2-4 1.2-6.3-2M12 13.6c1 4-.8 7-3.4 6.5-2-.4-1.2-4 2-6.3M10.4 12c-4 1-7-.8-6.5-3.4.4-2 4-1.2 6.3 2", INK)) }
    val blind: ImageVector by lazy { glyph("blind", P("M3.5 4h17M5.5 4v15.5h13V4M5.5 8.5h13M5.5 13h13M12 13v3.5", INK)) }
    val snow: ImageVector by lazy { glyph("snow", P("M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9", INK)) }
    val flame: ImageVector by lazy { glyph("flame", P("M12 3.5c1 3 4.8 5 4.8 9.7a4.8 4.8 0 0 1-9.6 0c0-2.1 1-3.6 2-4.6.3 1.5 1 2.5 2 3 0-3-.4-5.4.8-8.1z", INK)) }
    val wind: ImageVector by lazy { glyph("wind", P("M3 9h11a2.5 2.5 0 1 0-2.5-2.5M3 15h14a2.5 2.5 0 1 1-2.5 2.5M3 12h7", INK)) }
    val menu: ImageVector by lazy { glyph("menu", P("M4 7h16M4 12h16M4 17h16", INK)) }
    val mic: ImageVector by lazy { glyph("mic", P("M12 3.5h0a3 3 0 0 1 3 3v5a3 3 0 0 1 -3 3h-0a3 3 0 0 1 -3 -3v-5a3 3 0 0 1 3 -3z", INK), P("M6 11.5a6 6 0 0 0 12 0M12 17.5V21", INK)) }
    val link: ImageVector by lazy { glyph("link", P("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", INK), P("M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1", INK)) }
    val back10: ImageVector by lazy { glyph("back10", P("M4.5 12a7.5 7.5 0 1 0 2.2-5.3", INK), P("M4.5 4v3.5H8", INK), P("M10 10v5M13 10.5h2v4h-2z", INK)) }
    val fwd30: ImageVector by lazy { glyph("fwd30", P("M19.5 12a7.5 7.5 0 1 1-2.2-5.3", INK), P("M19.5 4v3.5H16", INK), P("M9 10h2v2H9.6M11 12v2.5H9M13.2 10h1.8v4.5h-1.8z", INK)) }
    val check: ImageVector by lazy { glyph("check", P("M5 12.5l4.5 4.5L19 7.5", INK)) }
    val sun: ImageVector by lazy { glyph("sun", P("M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z", WX_SUN, fill = WX_SUN_FILL), P("M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4", WX_SUN)) }
    val cloud: ImageVector by lazy { glyph("cloud", P("M7 18.5h10a4 4 0 0 0 .5-8 5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 18.5z", WX_CLOUD, fill = WX_CLOUD_FILL)) }
    val partly: ImageVector by lazy { glyph("partly", P("M8.5 3.5V5M3 9h1.5M4.6 5.1l1 1M12.4 5.1l-1 1", WX_SUN), P("M5.6 11.6A3.6 3.6 0 0 1 11.8 8.6", WX_SUN, fill = WX_SUN_FILL), P("M9 20h8.5a3.5 3.5 0 0 0 .4-7 4.8 4.8 0 0 0-9.2 1.3A2.9 2.9 0 0 0 9 20z", WX_CLOUD, fill = WX_CLOUD_FILL)) }
    val rain: ImageVector by lazy { glyph("rain", P("M7 15h10a4 4 0 0 0 .5-8 5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 15z", WX_CLOUD, fill = WX_CLOUD_FILL), P("M8.5 18l-1 2.5M12.5 18l-1 2.5M16.5 18l-1 2.5", WX_RAIN)) }
    val queue: ImageVector by lazy { glyph("queue", P("M4 7h11M4 12h11M4 17h7M18 14v6M15 17h6", INK)) }
    val list: ImageVector by lazy { glyph("list", P("M8 7h12M8 12h12M8 17h12M4 7h.01M4 12h.01M4 17h.01", INK)) }
    val disc: ImageVector by lazy { glyph("disc", P("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0z", INK), P("M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z", INK)) }
    val radio: ImageVector by lazy { glyph("radio", P("M5.5 8h13a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-13a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z", INK), P("M7 8l9-4.5", INK), P("M12.5 14a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z", INK), P("M7 12.5h3M7 15.5h3", INK)) }
    val moon: ImageVector by lazy { glyph("moon", P("M19.5 14.6A8 8 0 0 1 9.4 4.5a8 8 0 1 0 10.1 10.1z", WX_CLOUD, fill = WX_CLOUD_FILL)) }
    val storm: ImageVector by lazy { glyph("storm", P("M7 15h10a4 4 0 0 0 .5-8 5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 15z", WX_CLOUD, fill = WX_CLOUD_FILL), P("M12.5 15.5l-2 3.5h3l-2 3.5", WX_SUN)) }
    val fog: ImageVector by lazy { glyph("fog", P("M7 12.5h10a4 4 0 0 0 .5-8 5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 12.5z", WX_CLOUD, fill = WX_CLOUD_FILL), P("M4 16h16M6 19.5h12", WX_CLOUD)) }
    val snowy: ImageVector by lazy { glyph("snowy", P("M7 15h10a4 4 0 0 0 .5-8 5.5 5.5 0 0 0-10.6 1.5A3.3 3.3 0 0 0 7 15z", WX_CLOUD, fill = WX_CLOUD_FILL), P("M8.5 18.5h.01M12 20.5h.01M15.5 18.5h.01", WX_RAIN)) }
    val windy: ImageVector by lazy { glyph("windy", P("M3 9h11a2.5 2.5 0 1 0-2.5-2.5M3 15h14a2.5 2.5 0 1 1-2.5 2.5M3 12h7", WX_CLOUD)) }
    val mirrorball: ImageVector by lazy { glyph("mirrorball", P("M12 2v5", INK), P("M5 14a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z", INK), P("M5 14h14M6.2 10.5h11.6M6.2 17.5h11.6", INK), P("M12 7c-2.4 2.2-2.4 11.8 0 14M12 7c2.4 2.2 2.4 11.8 0 14", INK)) }
    val eiffel: ImageVector by lazy { glyph("eiffel", P("M12 2v3M10.4 5h3.2", INK), P("M10.8 5L9.2 11.5M13.2 5l1.6 6.5", INK), P("M7.8 11.5h8.4", INK), P("M9.2 11.5L5.5 21M14.8 11.5L18.5 21", INK), P("M7.4 16.5h9.2", INK), P("M9.6 21c.4-3 4.4-3 4.8 0", INK)) }
    val help: ImageVector by lazy { glyph("help", P("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0z", INK), P("M9.6 9.4a2.5 2.5 0 0 1 4.8.9c0 1.7-2.4 2.2-2.4 3.7M12 17h.01", INK)) }
}

/** A glyph by its config name ("radio", "disc", "note", …), or null. */
fun namedGlyph(name: String?): ImageVector? = when (name) {
    "bulb" -> Glyphs.bulb; "lock" -> Glyphs.lock; "motion" -> Glyphs.motion
    "play" -> Glyphs.play; "search" -> Glyphs.search; "film" -> Glyphs.film
    "tv" -> Glyphs.tv; "mtv" -> Glyphs.mtv; "note" -> Glyphs.note; "plan" -> Glyphs.plan
    "thermo" -> Glyphs.thermo; "speaker" -> Glyphs.speaker; "vol" -> Glyphs.vol
    "power" -> Glyphs.power; "calendar" -> Glyphs.calendar; "alarm" -> Glyphs.alarm
    "washer" -> Glyphs.washer; "drop" -> Glyphs.drop; "vacuum" -> Glyphs.vacuum
    "fan" -> Glyphs.fan; "blind" -> Glyphs.blind; "snow" -> Glyphs.snow; "flame" -> Glyphs.flame
    "wind" -> Glyphs.wind; "menu" -> Glyphs.menu; "mic" -> Glyphs.mic; "link" -> Glyphs.link
    "list" -> Glyphs.list; "queue" -> Glyphs.queue; "disc" -> Glyphs.disc; "radio" -> Glyphs.radio
    "moon" -> Glyphs.moon; "help" -> Glyphs.help
    "mirrorball" -> Glyphs.mirrorball; "eiffel" -> Glyphs.eiffel
    else -> null
}

/** The weather glyph for a Home Assistant condition. */
fun weatherGlyph(condition: String): ImageVector = when (condition) {
    "sunny", "clear" -> Glyphs.sun
    "clear-night" -> Glyphs.moon
    "partlycloudy" -> Glyphs.partly
    "cloudy" -> Glyphs.cloud
    "fog" -> Glyphs.fog
    "rainy", "pouring" -> Glyphs.rain
    "lightning", "lightning-rainy" -> Glyphs.storm
    "snowy", "snowy-rainy", "hail" -> Glyphs.snowy
    "windy", "windy-variant" -> Glyphs.windy
    else -> Glyphs.cloud
}

// Placeholder ink: Icon() replaces it with its tint.
private val INK = Color.Black
private val palette = AstrionColors()
private val WX_SUN = palette.wxSun
private val WX_SUN_FILL = palette.wxSunFill
private val WX_CLOUD = palette.textSoft
private val WX_CLOUD_FILL = palette.wxCloudFill
private val WX_RAIN = palette.wxRain

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
