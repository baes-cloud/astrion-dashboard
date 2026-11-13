package com.custom.astrion.config

import android.content.res.AssetManager
import android.os.Environment
import android.util.Log
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.input.HardwareKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * Loads the whole app layout (swipeable pages + hardware-button bindings) from
 * a JSON file on shared storage, falling back to the layout bundled in the APK
 * (device/config/dashboard.json, see [init]) when the file is missing or
 * malformed — the app never crashes over a bad config, it just shows a notice
 * banner.
 *
 * Path: /sdcard/astrion/dashboard.json. Shared storage keeps the file editable
 * over `adb push` or any file manager; MainActivity requests the storage
 * permission at runtime (Android 8.1 on the HA100).
 *
 * JSON shape:
 * {
 *   "startPage": 1,
 *   "pages": [
 *     { "name": "Lights", "cards": [ { "type": "...", "options": { ... } } ] },
 *     { "name": "Main",   "cards": [ ... ] },
 *     { "name": "TV",     "cards": [ ... ] }
 *   ],
 *   "hotkeys": [
 *     { "key": "UP", "service": "remote.send_command",
 *       "entityId": "remote.the_club_tvv", "data": { "command": "DPAD_UP" } },
 *     { "key": "LIGHT", "page": "Lights" }
 *   ]
 * }
 *
 * A bare top-level array is also accepted for convenience — it becomes a single
 * page named "Main" with no hotkeys.
 */
object DashboardLoader {
    private const val TAG = "DashboardLoader"

    val configDir: File
        get() = File(Environment.getExternalStorageDirectory(), "astrion")

    val configFile: File
        get() = File(configDir, CONFIG_NAME)

    /**
     * Per-remote settings laid over dashboard.json (see [withDeviceOverrides]):
     * which battery sensor this remote reports to, its name. Keeping them out
     * of dashboard.json lets the same layout be pushed to every remote.
     */
    val deviceFile: File
        get() = File(configDir, DEVICE_NAME)

    private val json = Json { ignoreUnknownKeys = true }

    data class Result(val config: AppConfig, val notice: String?)

    /**
     * The last dashboard.json that parsed, kept so a typo in the next edit
     * leaves the remote on the layout it had rather than dropping it to the
     * bundled one (someone else's flat, if this isn't the author's remote).
     * In memory only: after a restart a broken file still means the bundled
     * layout.
     */
    @Volatile private var lastGood: AppConfig? = null

    /** The bundled layout's text, written out verbatim as the first config file. */
    private var bundledText: String? = null

    /** The bundled layout, or [DashboardConfig.fallback] if it won't parse. */
    var default: AppConfig = DashboardConfig.fallback
        private set

    /** Read the layout bundled from device/config/dashboard.json. Call once at startup. */
    fun init(assets: AssetManager) {
        bundledText = runCatching { assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() } }
            .onFailure { Log.e(TAG, "No bundled $BUNDLED_ASSET", it) }
            .getOrNull()
        default = bundledText?.let { text ->
            runCatching { parse(text) }.onFailure { Log.e(TAG, "Bundled $BUNDLED_ASSET invalid", it) }.getOrNull()
        } ?: DashboardConfig.fallback
    }

    fun load(): Result {
        val base = loadLayout()
        val device = deviceFile
        if (!device.exists()) return base
        return try {
            Result(withDeviceOverrides(base.config, device.readText()), base.notice)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ${device.path}", e)
            Result(base.config, base.notice ?: "device.json invalid (${e.message?.take(80)}), ignored")
        }
    }

    private fun loadLayout(): Result {
        val file = configFile
        if (!file.exists()) {
            return if (writeDefaults()) {
                Result(default, "Wrote defaults to ${file.path}. Edit it and it reloads")
            } else {
                Result(default, "Can't access ${file.path} (storage permission?), using built-in defaults")
            }
        }
        return loadLayoutText({ file.readText() }, file.path)
    }

    /** [loadLayout] once the file is known to exist; apart for tests. */
    internal fun loadLayoutText(read: () -> String, path: String = CONFIG_NAME): Result = try {
        val config = parse(read())
        lastGood = config
        Result(config, warnings(config).takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { "dashboard.json: $it" })
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load $path", e)
        val why = e.message?.take(80)
        lastGood?.let { Result(it, "dashboard.json invalid ($why), keeping the last layout that worked") }
            ?: Result(default, "dashboard.json invalid ($why), using built-in defaults")
    }

    internal fun forgetLastGood() { lastGood = null }

    /**
     * Things in a layout that parsed but won't do what was meant, for the
     * notice banner: a card that couldn't be read, a card type nothing
     * renders, a hotkey on a key the remote doesn't have. Each of these used
     * to be dropped or drawn as a red box with no word about why.
     */
    internal fun warnings(config: AppConfig, knownTypes: Set<String> = CardRegistry.known()): List<String> {
        val out = mutableListOf<String>()
        config.pages.forEach { page ->
            page.cards.forEachIndexed { i, card ->
                when {
                    card.type == INVALID_CARD ->
                        out += "${page.name} card ${i + 1}: ${card.string("error") ?: "can't be read"}"
                    // Before the registry is filled (and in unit tests) nothing is known.
                    knownTypes.isNotEmpty() && card.type !in knownTypes ->
                        out += "${page.name} card ${i + 1}: unknown type \"${card.type}\""
                }
            }
        }
        val keys = HardwareKey.entries.map { it.name }.toSet() - HardwareKey.UNKNOWN.name
        listOf("hotkeys" to config.hotkeys, "longHotkeys" to config.longHotkeys, "doubleHotkeys" to config.doubleHotkeys)
            .forEach { (list, bindings) ->
                bindings.map { it.key }.filter { it.uppercase() !in keys }.distinct()
                    .forEach { out += "$list: no key called \"$it\"" }
            }
        return if (out.size <= MAX_WARNINGS) out else out.take(MAX_WARNINGS) + "and ${out.size - MAX_WARNINGS} more"
    }

    /**
     * Lays device.json's top-level blocks over [config]'s options, one level
     * deep: `{ "power": { "report_entity": "…" } }` replaces just that key and
     * keeps the rest of dashboard.json's `power` block. Applied over the
     * bundled layout too, so a remote keeps its identity when dashboard.json
     * is missing or broken.
     */
    internal fun withDeviceOverrides(config: AppConfig, text: String): AppConfig {
        val root = json.parseToJsonElement(text) as? JsonObject ?: error("top level must be an object")
        val options = config.options.toMutableMap()
        root.forEach { (key, value) ->
            val override = JsonPlain.toPlain(value)
            val current = options[key]
            @Suppress("UNCHECKED_CAST")
            options[key] = if (current is Map<*, *> && override is Map<*, *>) {
                (current as Map<String, Any?>) + (override as Map<String, Any?>)
            } else {
                override
            }
        }
        return config.copy(options = options)
    }

    // ---- parse --------------------------------------------------------------

    internal fun parse(text: String): AppConfig {
        return when (val root = json.parseToJsonElement(text)) {
            is JsonArray -> AppConfig(
                pages = listOf(PageConfig("Main", root.map(::parseCardOrInvalid))),
            )
            is JsonObject -> {
                val pagesArr = root["pages"] as? JsonArray ?: error("missing \"pages\" array")
                val pages = pagesArr.map { p ->
                    val obj = p as? JsonObject ?: error("each page must be an object")
                    val name = (obj["name"] as? JsonPrimitive)?.content ?: "Page"
                    val cards = (obj["cards"] as? JsonArray)?.map(::parseCardOrInvalid) ?: emptyList()
                    val deviceName = (obj["device_name"] as? JsonPrimitive)?.content
                    PageConfig(name, cards, deviceName)
                }
                if (pages.isEmpty()) error("\"pages\" is empty")
                val start = (root["startPage"] as? JsonPrimitive)?.intOrNull ?: 0
                val hotkeys = (root["hotkeys"] as? JsonArray)?.map { parseHotkey(it as JsonObject) } ?: emptyList()
                val longHotkeys = (root["longHotkeys"] as? JsonArray)?.map { parseHotkey(it as JsonObject) } ?: emptyList()
                val doubleHotkeys = (root["doubleHotkeys"] as? JsonArray)?.map { parseHotkey(it as JsonObject) } ?: emptyList()
                // Top-level feature blocks (ir_mode, voice, …). Anything that
                // isn't a known structural key is carried through verbatim, so
                // new features need no loader change.
                val structural = setOf("pages", "startPage", "hotkeys", "longHotkeys", "doubleHotkeys")
                val options = root.entries
                    .filter { it.key !in structural }
                    .associate { (k, v) -> k to JsonPlain.toPlain(v) }
                AppConfig(
                    pages, start.coerceIn(0, pages.size - 1),
                    hotkeys, longHotkeys, doubleHotkeys, options,
                )
            }
            else -> error("top level must be an object or array")
        }
    }

    /**
     * One unreadable card (not an object, no "type") becomes a placeholder
     * that says so, instead of throwing the whole layout away with it.
     */
    private fun parseCardOrInvalid(el: kotlinx.serialization.json.JsonElement): CardConfig = try {
        parseCard(el as? JsonObject ?: error("a card must be an object"))
    } catch (e: Exception) {
        CardConfig(INVALID_CARD, mapOf("error" to (e.message ?: "can't be read")))
    }

    private fun parseCard(obj: JsonObject): CardConfig {
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("card missing \"type\" string")
        val options = (obj["options"] as? JsonObject)
            ?.entries?.associate { (k, v) -> k to JsonPlain.toPlain(v) }
            ?: emptyMap()
        return CardConfig(type, options)
    }

    private fun parseHotkey(obj: JsonObject): HotkeyConfig {
        val key = (obj["key"] as? JsonPrimitive)?.content ?: error("hotkey missing \"key\"")
        val page = (obj["page"] as? JsonPrimitive)?.content
        val service = (obj["service"] as? JsonPrimitive)?.content
        val entityId = (obj["entityId"] as? JsonPrimitive)?.content
        val data = (obj["data"] as? JsonObject)
            ?.entries?.associate { (k, v) -> k to JsonPlain.toPlain(v) }
            ?: emptyMap()
        // Chained follow-up actions; each is a hotkey object without its own key.
        val then = (obj["then"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let { o -> parseChainedAction(o) } }
            ?: emptyList()
        val label = (obj["label"] as? JsonPrimitive)?.content
        return HotkeyConfig(key, page, service, entityId, data, then, label)
    }

    /** A `then` entry: same shape as a hotkey but the key is inherited. */
    private fun parseChainedAction(obj: JsonObject): HotkeyConfig = HotkeyConfig(
        key = (obj["key"] as? JsonPrimitive)?.content ?: "",
        service = (obj["service"] as? JsonPrimitive)?.content,
        entityId = (obj["entityId"] as? JsonPrimitive)?.content,
        data = (obj["data"] as? JsonObject)
            ?.entries?.associate { (k, v) -> k to JsonPlain.toPlain(v) }
            ?: emptyMap(),
    )

    // ---- write defaults -----------------------------------------------------

    private fun writeDefaults(): Boolean = try {
        val text = bundledText ?: error("no bundled layout")
        val file = configFile
        file.parentFile?.mkdirs()
        file.writeText(text)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't write default config", e)
        false
    }

    /** The type of the placeholder a card that couldn't be parsed becomes. */
    const val INVALID_CARD = "invalid"
    private const val MAX_WARNINGS = 4
    private const val BUNDLED_ASSET = "dashboard.json"
    const val CONFIG_NAME = "dashboard.json"
    const val DEVICE_NAME = "device.json"
}
