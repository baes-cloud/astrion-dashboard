package com.custom.astrion.config

import android.os.Environment
import android.util.Log
import com.custom.astrion.cards.CardConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Loads the whole app layout (swipeable pages + hardware-button bindings) from
 * a JSON file on shared storage, falling back to the compiled-in
 * DashboardConfig.default when the file is missing or malformed — the app never
 * crashes over a bad config, it just shows a notice banner.
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

    val configFile: File
        get() = File(Environment.getExternalStorageDirectory(), "astrion/dashboard.json")

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    data class Result(val config: AppConfig, val notice: String?)

    fun load(): Result {
        val file = configFile
        if (!file.exists()) {
            return if (writeDefaults()) {
                Result(DashboardConfig.default, "Wrote defaults to ${file.path} — edit it, then reopen the app")
            } else {
                Result(DashboardConfig.default, "Can't access ${file.path} (storage permission?) — using built-in defaults")
            }
        }
        return try {
            Result(parse(file.readText()), null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ${file.path}", e)
            Result(DashboardConfig.default, "dashboard.json invalid (${e.message?.take(80)}) — using built-in defaults")
        }
    }

    // ---- parse --------------------------------------------------------------

    private fun parse(text: String): AppConfig {
        return when (val root = json.parseToJsonElement(text)) {
            is JsonArray -> AppConfig(
                pages = listOf(PageConfig("Main", root.map { parseCard(it as JsonObject) })),
            )
            is JsonObject -> {
                val pagesArr = root["pages"] as? JsonArray ?: error("missing \"pages\" array")
                val pages = pagesArr.map { p ->
                    val obj = p as? JsonObject ?: error("each page must be an object")
                    val name = (obj["name"] as? JsonPrimitive)?.content ?: "Page"
                    val cards = (obj["cards"] as? JsonArray)?.map { parseCard(it as JsonObject) } ?: emptyList()
                    PageConfig(name, cards)
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
        return HotkeyConfig(key, page, service, entityId, data, then)
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

    // ---- serialize defaults -------------------------------------------------

    private fun writeDefaults(): Boolean = try {
        val file = configFile
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(JsonObject.serializer(), encode(DashboardConfig.default)))
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't write default config", e)
        false
    }

    private fun encode(cfg: AppConfig): JsonObject = buildJsonObject {
        put("startPage", cfg.startPage)
        put("pages", buildJsonArray {
            cfg.pages.forEach { page ->
                add(buildJsonObject {
                    put("name", page.name)
                    put("cards", buildJsonArray {
                        page.cards.forEach { card ->
                            add(buildJsonObject {
                                put("type", card.type)
                                put("options", JsonPlain.toJson(card.options))
                            })
                        }
                    })
                })
            }
        })
        put("hotkeys", encodeHotkeys(cfg.hotkeys))
        put("longHotkeys", encodeHotkeys(cfg.longHotkeys))
        put("doubleHotkeys", encodeHotkeys(cfg.doubleHotkeys))
        // Round-trip the feature blocks so a freshly written default config
        // still contains ir_mode / voice for the user to edit.
        cfg.options.forEach { (k, v) -> put(k, JsonPlain.toJson(v)) }
    }

    private fun encodeHotkeys(hotkeys: List<HotkeyConfig>) = buildJsonArray {
        hotkeys.forEach { hk -> add(encodeHotkey(hk)) }
    }

    private fun encodeHotkey(hk: HotkeyConfig): JsonObject = buildJsonObject {
        if (hk.key.isNotEmpty()) put("key", hk.key)
        hk.page?.let { put("page", it) }
        hk.service?.let { put("service", it) }
        hk.entityId?.let { put("entityId", it) }
        if (hk.data.isNotEmpty()) put("data", JsonPlain.toJson(hk.data))
        if (hk.then.isNotEmpty()) {
            put("then", buildJsonArray { hk.then.forEach { add(encodeHotkey(it)) } })
        }
    }
}
