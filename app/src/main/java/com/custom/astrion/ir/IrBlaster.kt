package com.custom.astrion.ir

import android.content.Context
import android.hardware.ConsumerIrManager
import android.util.Log
import com.custom.astrion.input.HardwareKey

/**
 * Hardware IR transmitter for the HA100's built-in emitter.
 *
 * The HA100 (MT6580, Android 8.1) does expose a real consumer-IR HAL
 * (`consumerir.mt6580.so`, service `android.hardware.IConsumerIrService`), so
 * this drives it through the standard [ConsumerIrManager] — no vendor SDK and
 * no round-trip through Sanytron's HACS integration.
 *
 * ## Samsung protocol
 * Samsung TVs use a NEC-style 38 kHz encoding, 32 bits MSB-first:
 *
 * ```
 *   header : 4500 us mark, 4500 us space
 *   bit 0  :  560 us mark,  560 us space
 *   bit 1  :  560 us mark, 1690 us space
 *   stop   :  560 us mark
 * ```
 *
 * [ConsumerIrManager.transmit] wants alternating on/off durations in
 * microseconds, which is exactly what [encodeSamsung] produces.
 *
 * ## Codes
 * [DEFAULT_CODES] holds the widely-published Samsung TV values. They are
 * correct for the vast majority of Samsung sets, but Samsung has shipped
 * variants — so every code can be overridden from `dashboard.json` without
 * rebuilding the APK (see [fromConfig]). If a button does nothing, capture the
 * real code with a FLIRC and drop the hex into config; see docs/IR_CAPTURE.md.
 */
class IrBlaster(context: Context) {

    companion object {
        private const val TAG = "IrBlaster"

        const val CARRIER_HZ = 38_000

        // Samsung timing, microseconds.
        private const val HDR_MARK = 4500
        private const val HDR_SPACE = 4500
        private const val BIT_MARK = 560
        private const val ONE_SPACE = 1690
        private const val ZERO_SPACE = 560

        /** Idle gap between repeated frames, milliseconds. */
        private const val FRAME_GAP_MS = 40L

        /**
         * Standard Samsung TV codes (32-bit). Keyed by the logical button so
         * the mapping stays readable.
         */
        val DEFAULT_CODES: Map<HardwareKey, Long> = mapOf(
            HardwareKey.POWER to 0xE0E040BFL,
            HardwareKey.VOLUME_UP to 0xE0E0E01FL,
            HardwareKey.VOLUME_DOWN to 0xE0E0D02FL,
            HardwareKey.MUTE to 0xE0E0F00FL,
            HardwareKey.UP to 0xE0E006F9L,
            HardwareKey.DOWN to 0xE0E08679L,
            HardwareKey.LEFT to 0xE0E0A659L,
            HardwareKey.RIGHT to 0xE0E046B9L,
            HardwareKey.CENTER to 0xE0E016E9L,   // ENTER / OK
            HardwareKey.BACK to 0xE0E01AE5L,     // RETURN
            HardwareKey.HOME to 0xE0E09E61L,     // SMART HUB
            HardwareKey.PAGE_UP to 0xE0E048B7L,  // CH UP
            HardwareKey.PAGE_DOWN to 0xE0E008F7L, // CH DOWN
        )

        /** Extra named codes surfaced as on-screen buttons in the IR popup. */
        val NAMED_CODES: Map<String, Long> = mapOf(
            "SOURCE" to 0xE0E0807FL,
            "MENU" to 0xE0E058A7L,
            "TOOLS" to 0xE0E0D22DL,
            "INFO" to 0xE0E0F807L,
            "GUIDE" to 0xE0E0F20DL,
            "EXIT" to 0xE0E0B44BL,
            "PLAY" to 0xE0E0E21DL,
            "PAUSE" to 0xE0E052ADL,
        )

        /**
         * Build the active code table: defaults overlaid with any
         * `{"POWER": "0xE0E040BF"}`-style overrides from config.
         */
        fun fromConfig(overrides: Map<String, Any?>?): Map<HardwareKey, Long> {
            if (overrides.isNullOrEmpty()) return DEFAULT_CODES
            val table = DEFAULT_CODES.toMutableMap()
            overrides.forEach { (name, raw) ->
                val key = runCatching { HardwareKey.valueOf(name.uppercase()) }.getOrNull()
                    ?: return@forEach
                parseHex(raw)?.let { table[key] = it }
            }
            return table
        }

        /** Accepts 0xE0E040BF, E0E040BF, or a plain decimal number. */
        fun parseHex(raw: Any?): Long? = when (raw) {
            is Number -> raw.toLong()
            is String -> {
                val s = raw.trim().removePrefix("0x").removePrefix("0X")
                s.toLongOrNull(16) ?: raw.trim().toLongOrNull()
            }
            else -> null
        }

        /**
         * Encode a 32-bit Samsung command as an alternating mark/space pattern
         * in microseconds, ready for [ConsumerIrManager.transmit].
         */
        fun encodeSamsung(code: Long, bits: Int = 32): IntArray {
            // header(2) + 2 per bit + trailing mark
            val out = IntArray(2 + bits * 2 + 1)
            var i = 0
            out[i++] = HDR_MARK
            out[i++] = HDR_SPACE
            for (b in bits - 1 downTo 0) {
                val isOne = (code shr b) and 1L == 1L
                out[i++] = BIT_MARK
                out[i++] = if (isOne) ONE_SPACE else ZERO_SPACE
            }
            out[i] = BIT_MARK // stop bit
            return out
        }
    }

    private val manager: ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    /** True when this unit actually has a usable emitter. */
    val available: Boolean = manager?.hasIrEmitter() == true

    /**
     * Carrier ranges the hardware reports it can produce. Used to sanity-check
     * that 38 kHz is actually supported before we bother transmitting.
     */
    val supports38k: Boolean = try {
        manager?.carrierFrequencies?.any { CARRIER_HZ in it.minFrequency..it.maxFrequency } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "carrierFrequencies query failed", e)
        false
    }

    /**
     * Transmit one raw 32-bit Samsung code. Returns false if it couldn't be sent.
     *
     * [repeat] sends the same frame back to back with a short gap. Leave it
     * at 1 for Samsung sets: they treat each complete frame as a separate
     * press (a held button is a train of full frames, not NEC repeat codes),
     * so 2 makes every button act twice. It exists for receivers that
     * genuinely miss an isolated frame.
     *
     * Synchronous on purpose: [ConsumerIrManager.transmit] blocks for the
     * pattern's duration (~68 ms per Samsung frame), and this is called from
     * dispatchKeyEvent, so two frames cost ~170 ms of the main thread. That is
     * cheaper than the machinery needed to report a background transmit's
     * result back into the popup's status line.
     */
    fun blast(code: Long, repeat: Int = 1): Boolean {
        val mgr = manager ?: run {
            Log.w(TAG, "no ConsumerIrManager — cannot transmit")
            return false
        }
        if (!available) {
            Log.w(TAG, "hasIrEmitter() == false — cannot transmit")
            return false
        }
        val pattern = encodeSamsung(code)
        val hex = "0x${code.toString(16).uppercase()}"
        var any = false
        for (i in 0 until repeat.coerceIn(1, 5)) {
            if (i > 0) try { Thread.sleep(FRAME_GAP_MS) } catch (_: InterruptedException) {}
            try {
                mgr.transmit(CARRIER_HZ, pattern)
                any = true
            } catch (e: Exception) {
                // Some MTK HALs throw rather than returning cleanly when busy.
                Log.w(TAG, "IR transmit failed for $hex", e)
            }
        }
        if (any) Log.i(TAG, "IR sent $hex x$repeat @${CARRIER_HZ}Hz (${pattern.size} slots)")
        return any
    }

    /** Human-readable capability line for the popup, so failures are visible. */
    fun statusLine(): String = when {
        manager == null -> "No IR service on this device"
        !available -> "No IR emitter reported"
        !supports38k -> "Emitter present, but 38 kHz not advertised"
        else -> "IR emitter ready · 38 kHz"
    }
}
