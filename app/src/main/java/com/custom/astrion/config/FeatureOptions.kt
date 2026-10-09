package com.custom.astrion.config

/**
 * The top-level feature blocks of dashboard.json (`screensaver`, `voice`,
 * `power`, `alarm`, `ir_mode`), read once per loaded config instead of being
 * cast out of the free-form map on every key press and timer tick.
 *
 * Each keeps its raw map for the composables that still take one.
 */
class FeatureOptions(options: Map<String, Any?>, defaults: Map<String, Any?>) {
    val screensaver = ScreensaverOptions(
        // dashboard.json's keys over the built-in defaults, so a config only
        // has to name what it changes (e.g. just `keys_pass_through`). A file
        // written before the screensaver existed has no such block at all.
        block(defaults, "screensaver") + block(options, "screensaver"),
    )
    val voice = VoiceOptions.from(block(options, "voice"))
    val power = PowerOptions(block(options, "power"))
    val alarm = AlarmOptions(block(options, "alarm"))
    val ir = IrOptions(block(options, "ir_mode"))

    companion object {
        @Suppress("UNCHECKED_CAST")
        internal fun block(options: Map<String, Any?>, key: String): Map<String, Any?> =
            (options[key] as? Map<String, Any?>) ?: emptyMap()
    }
}

private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
private fun Map<String, Any?>.float(key: String): Float? = (this[key] as? Number)?.toFloat()
private fun Map<String, Any?>.str(key: String): String? = this[key] as? String
private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
private fun Map<String, Any?>.strings(key: String): List<String> =
    (this[key] as? List<*>)?.filterIsInstance<String>().orEmpty()

class ScreensaverOptions(val raw: Map<String, Any?>) {
    val enabled = raw.bool("enabled") ?: true
    /** `"always"`: the docked idle time applies off the dock as well. */
    val alwaysTrigger = raw["trigger"] == "always"
    val idleMs = (raw.long("idle_seconds") ?: 45L) * 1000
    /** -1 when there is no screensaver off the dock. */
    val undockedIdleMs = (raw.long("undocked_idle_seconds") ?: UNDOCKED_IDLE_S.toLong())
        .let { if (it < 0) -1 else it * 1000 }
    val keepScreenOn = raw.bool("keep_screen_on") != false
    val brightness = raw.float("brightness") ?: 0.22f
    val nightBrightness = raw.float("night_brightness") ?: 0.05f
    val undockedBrightness = raw.float("undocked_brightness") ?: UNDOCKED_BRIGHTNESS

    private val passThrough: Any? = raw["keys_pass_through"]

    /** Whether a key that wakes the screensaver also does its usual job. */
    fun passesThrough(keyName: String): Boolean = when (val v = passThrough) {
        is Boolean -> v
        is List<*> -> v.any { (it as? String)?.equals(keyName, ignoreCase = true) == true }
        else -> true
    }

    companion object {
        /**
         * `undocked_idle_seconds` default: off the dock a short idle brings
         * up a dim screensaver, until the system timeout turns the screen
         * off. -1 = no screensaver off the dock.
         */
        const val UNDOCKED_IDLE_S = 90

        /** `undocked_brightness` default: dimmer than docked, it's on battery. */
        const val UNDOCKED_BRIGHTNESS = 0.08f
    }
}

/** A data class, so a reload with the same `voice` block isn't a change. */
data class VoiceOptions(
    val pipeline: String?,
    val imageDir: String,
    /** `"always"`, `"docked"` (default) or anything else for off. */
    val wakeWord: String,
    val wakeWordUndockedMs: Long,
) {
    companion object {
        /** `wake_word_undocked_minutes` default: keep listening this long off the dock. */
        const val WAKE_WORD_UNDOCKED_MIN = 10

        fun from(raw: Map<String, Any?>) = VoiceOptions(
            pipeline = raw.str("pipeline"),
            imageDir = raw.str("image_dir") ?: "/sdcard/astrion/voice",
            wakeWord = raw.str("wake_word") ?: "docked",
            wakeWordUndockedMs = (raw.long("wake_word_undocked_minutes") ?: WAKE_WORD_UNDOCKED_MIN.toLong()) * 60_000,
        )
    }
}

class PowerOptions(raw: Map<String, Any?>) {
    /**
     * Applied to the system setting and re-applied whenever something else
     * changes it: the stock HaRemote app writes "never" (2147483647), which
     * kept an undocked remote's screen on until it ran flat. 0 = leave the
     * setting alone; -1 = never time out, so an undocked remote stays on its
     * screensaver (about 6% battery an hour).
     */
    val screenTimeoutSeconds = raw.int("screen_timeout_seconds") ?: 120
    /** Listen for a pick-up this long after the screen goes off. */
    val motionWakeMinutes = raw.int("motion_wake_minutes") ?: 5
    /** Narrow the HA subscription after this long dark. */
    val screenOffFilterSeconds = raw.int("screen_off_filter_seconds") ?: 30
    /** Charging this long before it counts as docked. */
    val dockDebounceSeconds = raw.int("dock_debounce_seconds") ?: 5
    val reportEntity = raw.str("report_entity")
    val reportName = raw.str("report_name")
    val screenOffEntities = raw.strings("screen_off_entities")
}

/** A `snooze` / `stop` action: `{ service, entity_id }`. */
data class AlarmAction(val service: String, val entityId: String?)

class AlarmOptions(raw: Map<String, Any?>) {
    val ringingEntity = raw.str("ringing_entity")
    val snoozeTimer = raw.str("snooze_timer")
    val infoEntity = raw.str("info_entity")
    val snooze = action(raw, "snooze")
    val stop = action(raw, "stop")

    /** What must keep arriving with the screen off for the alarm to work. */
    val entities: List<String> = listOfNotNull(ringingEntity, snoozeTimer, infoEntity)

    private companion object {
        fun action(raw: Map<String, Any?>, key: String): AlarmAction? {
            val m = FeatureOptions.block(raw, key)
            val service = m.str("service") ?: return null
            return AlarmAction(service, m.str("entity_id"))
        }
    }
}

class IrOptions(val raw: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST")
    val codes = raw["codes"] as? Map<String, Any?>
    /** How many times each frame is sent. */
    val repeat = (raw.int("repeat") ?: 1).coerceIn(1, 5)
    val toggleKey = raw.str("toggle_key") ?: "MENU"
    val toggleLong = raw.bool("toggle_long") ?: false
}
