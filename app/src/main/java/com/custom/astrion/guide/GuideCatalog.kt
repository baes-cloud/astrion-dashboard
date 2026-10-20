package com.custom.astrion.guide

import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.HotkeyConfig
import com.custom.astrion.input.HardwareKey

/** How a button was pressed: the three gestures every binding can have. */
enum class PressKind(val word: String) { TAP("Press"), DOUBLE("Double press"), HOLD("Hold") }

/** One physical button (or the four arrows as one) and what each press does. */
data class KeyHelp(
    val keys: List<HardwareKey>,
    val name: String,
    val group: KeyGroup,
    val tap: String?,
    val double: String?,
    val hold: String?,
) {
    val key: HardwareKey get() = keys.first()
    fun action(kind: PressKind): String? = when (kind) {
        PressKind.TAP -> tap
        PressKind.DOUBLE -> double
        PressKind.HOLD -> hold
    }
}

/** In the order a newcomer needs them: pages first, the odds and ends last. */
enum class KeyGroup(val title: String) {
    ROOMS("Page buttons"),
    TV("TV"),
    SOUND("Volume and brightness"),
    COLOURS("Colour buttons"),
    EXTRAS("Menu and mic"),
}

/**
 * What the guide needs to draw an example floorplan: the plan image and a few
 * real spots on it (percent of the image), so the example is the user's flat.
 */
data class PlanExample(
    val image: String?,
    val lights: List<Pair<Float, Float>>,
    val vacuum: Pair<Float, Float>?,
)

/** A "how do I…" card: a title and a few short steps. */
data class GuideTip(val title: String, val icon: String, val steps: List<String>)

/**
 * Everything the button guide shows, read from the live config so it can't
 * drift from what the buttons really do: each hotkey's `label` when it has
 * one, otherwise a description of the action itself.
 *
 * The `guide` block in dashboard.json adds the parts config can't say:
 *   "guide": {
 *     "names": { "SCENE": "Media" },      // what the physical button is called
 *     "tips":  [ { "title": "Turn on a light", "icon": "light",
 *                  "steps": ["Press Light …", "…"] } ]
 *   }
 */
class GuideCatalog(
    val keys: List<KeyHelp>,
    val tips: List<GuideTip>,
    /** The first floorplan in the config, so the guide can explain it with a picture. */
    val plan: PlanExample? = null,
    /** `guide.voice`: the wake word as people say it, and things to try. */
    val wakeWord: String? = null,
    val voiceExamples: List<String> = emptyList(),
) {
    val hasFloorplan: Boolean get() = plan != null

    fun forKey(key: HardwareKey): KeyHelp? = keys.firstOrNull { key in it.keys }

    companion object {
        private val ARROWS = listOf(HardwareKey.UP, HardwareKey.DOWN, HardwareKey.LEFT, HardwareKey.RIGHT)

        private val DEFAULT_NAMES = mapOf(
            HardwareKey.LIGHT to "Light",
            HardwareKey.CURTAIN to "Curtain",
            HardwareKey.SCENE to "Music",
            HardwareKey.AC to "Climate",
            HardwareKey.CUSTOM_1 to "Red",
            HardwareKey.CUSTOM_2 to "Green",
            HardwareKey.CUSTOM_3 to "Blue",
            HardwareKey.CUSTOM_4 to "Yellow",
            HardwareKey.UP to "Arrows",
            HardwareKey.CENTER to "OK",
            HardwareKey.HOME to "Home",
            HardwareKey.BACK to "Back",
            HardwareKey.POWER to "Power",
            HardwareKey.VOLUME_UP to "Volume +",
            HardwareKey.VOLUME_DOWN to "Volume −",
            HardwareKey.MUTE to "Mute",
            HardwareKey.PAGE_UP to "Page ▲",
            HardwareKey.PAGE_DOWN to "Page ▼",
            HardwareKey.MENU to "Menu ☰",
            HardwareKey.VOICE to "Mic",
        )

        private val LAYOUT: List<Pair<KeyGroup, List<HardwareKey>>> = listOf(
            KeyGroup.ROOMS to listOf(HardwareKey.LIGHT, HardwareKey.CURTAIN, HardwareKey.SCENE, HardwareKey.AC),
            KeyGroup.TV to listOf(HardwareKey.POWER, HardwareKey.UP, HardwareKey.CENTER, HardwareKey.HOME, HardwareKey.BACK),
            KeyGroup.SOUND to listOf(
                HardwareKey.VOLUME_UP, HardwareKey.VOLUME_DOWN, HardwareKey.MUTE,
                HardwareKey.PAGE_UP, HardwareKey.PAGE_DOWN,
            ),
            KeyGroup.COLOURS to listOf(HardwareKey.CUSTOM_1, HardwareKey.CUSTOM_2, HardwareKey.CUSTOM_3, HardwareKey.CUSTOM_4),
            KeyGroup.EXTRAS to listOf(HardwareKey.MENU, HardwareKey.VOICE),
        )

        /**
         * @param friendlyName HA's friendly name for an entity id, or null,
         *   so "script.long_lights" can read as "long lights".
         */
        fun from(config: AppConfig, friendlyName: (String) -> String? = { null }): GuideCatalog {
            val guide = (config.options["guide"] as? Map<*, *>).orEmpty()
            val names = (guide["names"] as? Map<*, *>).orEmpty()
            // Buttons the guide leaves out ("hide": ["MUTE"]): not worth explaining.
            val hidden = (guide["hide"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.uppercase() }.toSet()
            val describer = Describer(friendlyName)

            fun bound(list: List<HotkeyConfig>, key: HardwareKey) =
                list.firstOrNull { it.key.equals(key.name, ignoreCase = true) }

            val ir = config.features.ir
            val irKey = HardwareKey.entries.firstOrNull { it.name.equals(ir.toggleKey, ignoreCase = true) }

            fun describe(key: HardwareKey, kind: PressKind): String? {
                // The app's own bindings, which config doesn't list as hotkeys.
                val irKind = if (ir.toggleLong) PressKind.HOLD else PressKind.TAP
                if (key == irKey && kind == irKind) {
                    return "IR mode: works the TV directly, for when it won't respond. Press again to leave"
                }
                if (key == HardwareKey.VOICE && kind == PressKind.TAP) {
                    return "Talk to the house. Press again to stop"
                }
                val hk = when (kind) {
                    PressKind.TAP -> bound(config.hotkeys, key)
                    PressKind.DOUBLE -> bound(config.doubleHotkeys, key)
                    PressKind.HOLD -> bound(config.longHotkeys, key)
                } ?: return null
                return describer.hotkey(hk)
            }

            val keys = LAYOUT.flatMap { (group, list) ->
                list.mapNotNull { key ->
                    if (key.name in hidden) return@mapNotNull null
                    val members = if (key == HardwareKey.UP) ARROWS else listOf(key)
                    val name = (names[key.name] as? String) ?: DEFAULT_NAMES[key] ?: key.name
                    val help = if (key == HardwareKey.UP) {
                        arrowsHelp(name, group) { k, kind -> describe(k, kind) }
                    } else {
                        KeyHelp(
                            members, name, group,
                            tap = describe(key, PressKind.TAP),
                            double = describe(key, PressKind.DOUBLE),
                            hold = describe(key, PressKind.HOLD),
                        )
                    }
                    help.takeIf { it.tap != null || it.double != null || it.hold != null }
                }
            }

            @Suppress("UNCHECKED_CAST")
            val tips = (guide["tips"] as? List<Map<String, Any?>>)?.mapNotNull { t ->
                val title = t["title"] as? String ?: return@mapNotNull null
                val steps = (t["steps"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                GuideTip(title, t["icon"] as? String ?: "info", steps)
            } ?: DEFAULT_TIPS
            val plan = config.pages.firstNotNullOfOrNull { page ->
                page.cards.firstNotNullOfOrNull { findPlan(it.type, it.options) }
            }?.let(::planExample)
            val voice = (guide["voice"] as? Map<*, *>).orEmpty()
            val examples = (voice["examples"] as? List<*>).orEmpty().filterIsInstance<String>()
            return GuideCatalog(keys, tips, plan, voice["wake_word"] as? String, examples)
        }

        /** A picture_elements card's options, anywhere inside this one (stacks, rows, swipe stacks). */
        private fun findPlan(type: String?, options: Map<*, *>?): Map<*, *>? {
            if (type == "picture_elements") return options
            val children = options?.get("cards") as? List<*> ?: return null
            return children.firstNotNullOfOrNull { c ->
                (c as? Map<*, *>)?.let { findPlan(it["type"] as? String, it["options"] as? Map<*, *>) }
            }
        }

        private fun planExample(o: Map<*, *>): PlanExample {
            fun pct(v: Any?) = (v as? Number)?.toFloat()
            val lights = (o["elements"] as? List<*>).orEmpty().mapNotNull { e ->
                val m = e as? Map<*, *> ?: return@mapNotNull null
                if ((m["entity_id"] as? String)?.startsWith("light.") != true) return@mapNotNull null
                val l = pct(m["left"]) ?: return@mapNotNull null
                val t = pct(m["top"]) ?: return@mapNotNull null
                l to t
            }
            val dock = ((o["vacuum"] as? Map<*, *>)?.get("dock_position") as? List<*>)
                ?.mapNotNull { pct(it) }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
            return PlanExample(o["image"] as? String, lights, dock)
        }

        /** The arrows read as one entry when they do the obvious thing. */
        private fun arrowsHelp(
            name: String,
            group: KeyGroup,
            describe: (HardwareKey, PressKind) -> String?,
        ): KeyHelp {
            fun merged(kind: PressKind): String? {
                val each = ARROWS.map { describe(it, kind) }
                if (each.all { it == null }) return null
                // "Up on the TV", "Down on the TV", … → one line.
                if (each.all { it?.endsWith(" on the TV") == true }) return "Move around the TV's menus"
                return ARROWS.zip(each)
                    .filter { it.second != null }
                    .joinToString(" · ") { (k, d) -> "${k.name.lowercase().replaceFirstChar(Char::uppercase)}: $d" }
            }
            return KeyHelp(ARROWS, name, group, merged(PressKind.TAP), merged(PressKind.DOUBLE), merged(PressKind.HOLD))
        }

        val DEFAULT_TIPS = listOf(
            GuideTip(
                "Turn a light on or off", "light",
                listOf(
                    "Press Light for the lights.",
                    "Tap a light to switch it. Hold it for brightness and colour.",
                ),
            ),
            GuideTip(
                "Play music", "music",
                listOf(
                    "Press Music (the note).",
                    "Tap a playlist or album to start it. Swipe for more.",
                    "Volume + and − work from any page.",
                ),
            ),
            GuideTip(
                "Something went wrong", "help",
                listOf(
                    "Press Back to close whatever is open.",
                    "Hold Menu ☰ to open this guide again.",
                ),
            ),
        )
    }
}

/** Turns a hotkey into a short phrase for someone who's never seen the config. */
internal class Describer(private val friendlyName: (String) -> String?) {

    fun hotkey(hk: HotkeyConfig): String {
        hk.label?.let { return it }
        hk.page?.let { return "Opens the $it page" }
        // Follow-ups (`then`) like "wake the TV" are plumbing: the main action
        // is the line, and a follow-up only speaks when there is no main one.
        return action(hk).ifBlank {
            hk.then.firstNotNullOfOrNull { it.label ?: action(it).ifBlank { null } } ?: "Does something custom"
        }
    }

    private fun name(entityId: String?): String? =
        entityId?.let { friendlyName(it) ?: it.substringAfter('.').replace('_', ' ') }

    fun action(hk: HotkeyConfig): String {
        val service = hk.service ?: return ""
        val entity = hk.entityId
        return when (service) {
            "astrion.toggle_mute" -> "Mute or unmute the music"
            "astrion.unjoin_others" -> "Stop the other speakers playing along"
            "astrion.open_settings" -> "Opens Android settings"
            "astrion.launch" -> "Opens the ${appName(hk.data["package"] as? String)} app"
            "astrion.open_guide" -> "Opens this guide"
            "media_player.volume_up" -> "Volume up"
            "media_player.volume_down" -> "Volume down"
            "media_player.media_play_pause" -> "Play or pause the music"
            "media_player.media_next_track" -> "Next track"
            "media_player.media_previous_track" -> "Previous track"
            "media_player.select_source" -> "Switches to ${sourceName(hk.data["source"] as? String)}"
            "remote.send_command" -> "${commandName(hk.data["command"] as? String)} on the TV"
            "light.toggle" -> "Turns ${name(entity) ?: "the light"} on or off"
            "light.turn_on" -> "Turns ${name(entity) ?: "the light"} on"
            "light.turn_off" -> "Turns ${name(entity) ?: "the light"} off"
            "climate.toggle" -> "Turns ${name(entity) ?: "the aircon"} on or off"
            "script.turn_on", "scene.turn_on", "automation.trigger" ->
                "Runs “${name(entity) ?: "a routine"}”"
            "androidtv.adb_command" -> "Wakes the TV"
            else -> {
                val domain = service.substringBefore('.')
                when (domain) {
                    // `script.long_lights` called as a service: its own name.
                    "script" -> "Runs “${name(service) ?: service.substringAfter('.')}”"
                    else -> {
                        val verb = service.substringAfter('.').replace('_', ' ').replaceFirstChar(Char::uppercase)
                        listOfNotNull(verb, name(entity)).joinToString(" ")
                    }
                }
            }
        }
    }

    private fun appName(pkg: String?): String = KNOWN_APPS[pkg] ?: pkg?.substringAfterLast('.') ?: "other"

    private fun sourceName(source: String?): String {
        if (source == null) return "another input"
        KNOWN_APPS[source]?.let { return it }
        Regex("^hdmi(\\d)$", RegexOption.IGNORE_CASE).find(source)?.let { return "HDMI ${it.groupValues[1]}" }
        // A playlist name or an input name, as the speaker reports it.
        return if (source.length > 28) "“${source.take(26)}…”" else "“$source”"
    }

    private fun commandName(cmd: String?): String = when (cmd?.uppercase()) {
        "DPAD_UP" -> "Up"
        "DPAD_DOWN" -> "Down"
        "DPAD_LEFT" -> "Left"
        "DPAD_RIGHT" -> "Right"
        "DPAD_CENTER" -> "Select"
        "HOME" -> "Home screen"
        "BACK" -> "Back"
        "POWER" -> "Power"
        null -> "A button"
        else -> cmd.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
    }

    companion object {
        val KNOWN_APPS = mapOf(
            "com.netflix.ninja" to "Netflix",
            "com.plexapp.android" to "Plex",
            "au.net.abc.iview" to "ABC iview",
            "org.jellyfin.androidtv" to "Jellyfin",
            "com.google.android.youtube.tv" to "YouTube",
            "com.aiks.HaRemote" to "original remote",
        )
    }
}
