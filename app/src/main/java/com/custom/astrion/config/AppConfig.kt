package com.custom.astrion.config

import com.custom.astrion.cards.CardConfig

/**
 * Full app configuration: swipeable pages of cards plus hardware-button
 * bindings. Loaded from /sdcard/astrion/dashboard.json by DashboardLoader,
 * with DashboardConfig.default as the compiled-in fallback.
 */
data class AppConfig(
    /** Left-to-right page order; swipe between them. */
    val pages: List<PageConfig>,
    /** Index of the page shown at launch (the "home" page). */
    val startPage: Int = 0,
    /** Short-press button bindings. */
    val hotkeys: List<HotkeyConfig> = emptyList(),
    /** Long-press (~500ms hold) button bindings — same shape as hotkeys. */
    val longHotkeys: List<HotkeyConfig> = emptyList(),
    /**
     * Double-tap bindings — same shape again.
     *
     * A key listed here pays for it: its SHORT action can no longer fire on
     * release, because there is no way to know a second tap isn't coming until
     * the double-tap window has passed. Only bind keys where that delay is
     * worth the extra action; everything else stays instant.
     */
    val doubleHotkeys: List<HotkeyConfig> = emptyList(),
    /**
     * Top-level feature blocks that aren't tied to a single page or button —
     * currently `ir_mode` (Samsung IR codes + popup buttons) and `voice`
     * (Assist pipeline id, artwork folder). Free-form so new features can be
     * configured without a schema change.
     */
    val options: Map<String, Any?> = emptyMap(),
)

/** One swipeable page: a name (used by hotkey `page` navigation) and its cards. */
data class PageConfig(
    val name: String,
    val cards: List<CardConfig>,
)

/**
 * One physical-button binding. `key` is a HardwareKey name — the HA100 has:
 * UP DOWN LEFT RIGHT CENTER, PAGE_UP PAGE_DOWN, VOLUME_UP VOLUME_DOWN MUTE,
 * BACK HOME POWER VOICE, LIGHT CURTAIN SCENE AC, CUSTOM_1..CUSTOM_4.
 *
 * Exactly one action per binding:
 *  - `page`: navigate to the page with that name (case-insensitive), or
 *  - `service` ("domain.service") + optional `entityId` + flat `data` map.
 */
data class HotkeyConfig(
    val key: String,
    val page: String? = null,
    val service: String? = null,
    val entityId: String? = null,
    val data: Map<String, Any?> = emptyMap(),
    /**
     * Extra actions fired straight after this one, so a single button can do
     * several things (e.g. mute the TV *and* toggle the speaker's mute).
     * Same shape as a hotkey minus the key/page.
     */
    val then: List<HotkeyConfig> = emptyList(),
)
