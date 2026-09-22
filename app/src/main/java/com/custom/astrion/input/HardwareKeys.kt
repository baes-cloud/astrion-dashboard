package com.custom.astrion.input

/**
 * Physical button map for the Astrion HA100 hardware.
 *
 * These keycodes were extracted directly from the stock app's
 * assets/device_key_code.json (the "HA100" block). When a physical button is
 * pressed, the OS delivers a standard Android KeyEvent with these codes to the
 * focused Activity, which is why a standalone app can handle them via
 * dispatchKeyEvent / onKeyDown — no vendor SDK required for the buttons.
 *
 * The dedicated shortcut buttons (light / curtain / scene / ac / custom_1..4)
 * are the real prize: you can bind each of them to any action you want, instead
 * of HaRemote's fixed behaviour.
 */
enum class HardwareKey {
    BACK, HOME, POWER,
    VOLUME_UP, VOLUME_DOWN, MUTE,
    PAGE_UP, PAGE_DOWN,
    UP, DOWN, LEFT, RIGHT, CENTER,
    VOICE, MENU,
    LIGHT, CURTAIN, SCENE, AC,
    CUSTOM_1, CUSTOM_2, CUSTOM_3, CUSTOM_4,
    UNKNOWN;

    companion object {
        // Android keycode -> logical button, straight from device_key_code.json (HA100).
        //
        // CORRECTION to the stock table: it lists BOTH 82 and 91 as "mute",
        // which is a copy-paste error in the vendor config. The HA100 has two
        // separate buttons on that row — 🔇 mute and ☰ menu — and 82 is
        // Android's standard KEYCODE_MENU, i.e. the ☰ key. Mapping them apart
        // is what lets ☰ open IR Mode while 🔇 keeps its own binding.
        private val MAP: Map<Int, HardwareKey> = mapOf(
            4 to BACK,
            131 to HOME,
            132 to POWER,
            24 to VOLUME_UP,
            25 to VOLUME_DOWN,
            92 to PAGE_UP,
            93 to PAGE_DOWN,
            19 to UP,
            20 to DOWN,
            21 to LEFT,
            22 to RIGHT,
            23 to CENTER,
            91 to MUTE,   // 🔇 (KEYCODE_MUTE — per the stock firmware map)
            // ...but this unit's 🔇 actually reports KEYCODE_VOLUME_MUTE:
            // scancode 113 (KEY_MUTE) on mt_gpio_kpd resolves to 164, which is
            // what Key Mapper captured from the physical button. Both are
            // mapped so either firmware behaviour works.
            164 to MUTE,
            82 to MENU,   // ☰  (KEYCODE_MENU — opens IR Mode)
            133 to VOICE, // 🎤
            134 to LIGHT,
            135 to CURTAIN,
            136 to SCENE,
            137 to AC,
            138 to CUSTOM_1,
            139 to CUSTOM_2,
            140 to CUSTOM_3,
            141 to CUSTOM_4,
        )

        fun fromKeyCode(code: Int): HardwareKey = MAP[code] ?: UNKNOWN
    }
}

/**
 * Bind hardware buttons to actions. Register short- and long-press handlers at
 * startup; MainActivity does the tap-vs-hold timing and calls back here.
 */
class HardwareKeyRouter {
    private val shortHandlers = mutableMapOf<HardwareKey, () -> Boolean>()
    private val longHandlers = mutableMapOf<HardwareKey, () -> Boolean>()
    private val doubleHandlers = mutableMapOf<HardwareKey, () -> Boolean>()

    fun on(key: HardwareKey, handler: () -> Boolean) {
        shortHandlers[key] = handler
    }

    fun onLong(key: HardwareKey, handler: () -> Boolean) {
        longHandlers[key] = handler
    }

    fun onDouble(key: HardwareKey, handler: () -> Boolean) {
        doubleHandlers[key] = handler
    }

    /** Drop all bindings — used before rebinding from a reloaded config. */
    fun clear() {
        shortHandlers.clear()
        longHandlers.clear()
        doubleHandlers.clear()
    }

    fun shortHandler(code: Int): (() -> Boolean)? {
        val key = HardwareKey.fromKeyCode(code)
        return if (key == HardwareKey.UNKNOWN) null else shortHandlers[key]
    }

    fun longHandler(code: Int): (() -> Boolean)? {
        val key = HardwareKey.fromKeyCode(code)
        return if (key == HardwareKey.UNKNOWN) null else longHandlers[key]
    }

    fun doubleHandler(code: Int): (() -> Boolean)? {
        val key = HardwareKey.fromKeyCode(code)
        return if (key == HardwareKey.UNKNOWN) null else doubleHandlers[key]
    }
}
