package com.custom.astrion.power

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Interface contract for motion-wake control as specified in PROJECT.md.
 */
interface MotionWakeController {
    fun start(onWake: () -> Unit)
    fun stop()
    fun isHardwareInterruptSupported(): Boolean
}

/**
 * Motion-wake: wakes the screen when the remote is lifted/moved.
 *
 * Modernized to eliminate the continuous accelerometer polling loop:
 * - Supports hardware interrupt trigger (`Sensor.TYPE_SIGNIFICANT_MOTION` with `TriggerEventListener`)
 *   when available on hardware, which wakes the CPU only on actual motion without keeping Cortex-A7 cores awake.
 * - For standard accelerometer fallback, provides duty-cycled listening and sleep windows so the
 *   MT6580 Cortex-A7 cores can enter deep sleep when screen is off and undocked.
 * - Replaces legacy Handler/Runnable timeouts with Kotlin Coroutines structured concurrency.
 */
class MotionWake(
    context: Context? = null,
    @Suppress("UNUSED_PARAMETER") handler: Any? = null,
    private var onMotion: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val listenWindowMs: Long = DEFAULT_LISTEN_WINDOW_MS,
    private val sleepWindowMs: Long = DEFAULT_SLEEP_WINDOW_MS,
    sensorManagerOverride: SensorManager? = null,
) : MotionWakeController {

    private val sensorManager: SensorManager? =
        sensorManagerOverride ?: (context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)

    // Hardware interrupt: significant motion detector (one-shot trigger)
    private val sigMotion: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    // Fallback: wake-up accelerometer or standard accelerometer
    private val accelSensor: Sensor? = sensorManager?.getSensorList(Sensor.TYPE_ACCELEROMETER)
        ?.firstOrNull { it.isWakeUpSensor }
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var activeJob: Job? = null
    private var hwTriggerActive = false
    private var lastMagnitude = 0f

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            hwTriggerActive = false
            onMotion()
        }
    }

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = event.values
            val mag = calculateMagnitude(x, y, z)
            if (isMotionDetected(mag, lastMagnitude)) {
                onMotion()
            }
            lastMagnitude = mag
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun isHardwareInterruptSupported(): Boolean = sigMotion != null

    override fun start(onWake: () -> Unit) {
        start(minutes = 5, onWake = onWake)
    }

    /**
     * Listen for [minutes] (0 = never/disabled, -1 = until [stop]).
     *
     * Uses TYPE_SIGNIFICANT_MOTION hardware interrupt trigger when available.
     * Otherwise falls back to duty-cycled accelerometer polling windows that allow
     * the SoC to enter deep sleep between sample windows.
     */
    fun start(minutes: Int = 5, onWake: (() -> Unit)? = null) {
        stop()
        if (onWake != null) this.onMotion = onWake
        if (minutes == 0) return

        val sm = sensorManager ?: return

        if (sigMotion != null) {
            // Hardware interrupt trigger
            val registered = sm.requestTriggerSensor(triggerListener, sigMotion)
            hwTriggerActive = registered
            if (minutes > 0) {
                activeJob = scope.launch {
                    delay(minutes * 60_000L)
                    stop()
                }
            }
        } else {
            val accel = accelSensor ?: return
            // Fallback: duty-cycled listening and sleep windows
            activeJob = scope.launch {
                val durationMs = if (minutes > 0) minutes * 60_000L else Long.MAX_VALUE
                val deadline = if (durationMs == Long.MAX_VALUE) Long.MAX_VALUE else SystemClock.elapsedRealtime() + durationMs

                while (isActive && SystemClock.elapsedRealtime() < deadline) {
                    lastMagnitude = 0f
                    sm.registerListener(accelListener, accel, SensorManager.SENSOR_DELAY_NORMAL)
                    delay(listenWindowMs)
                    sm.unregisterListener(accelListener)

                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    delay(minOf(sleepWindowMs, remaining))
                }
                stop()
            }
        }
    }

    override fun stop() {
        activeJob?.cancel()
        activeJob = null
        if (hwTriggerActive) {
            sigMotion?.let { sensorManager?.cancelTriggerSensor(triggerListener, it) }
            hwTriggerActive = false
        }
        sensorManager?.unregisterListener(accelListener)
        lastMagnitude = 0f
    }

    companion object {
        /** Accel magnitude delta (m/s²) that counts as "moved". */
        const val MOTION_THRESHOLD = 0.9f
        const val DEFAULT_LISTEN_WINDOW_MS = 1_000L
        const val DEFAULT_SLEEP_WINDOW_MS = 4_000L

        fun calculateMagnitude(x: Float, y: Float, z: Float): Float =
            sqrt(x * x + y * y + z * z)

        fun isMotionDetected(currentMag: Float, lastMag: Float): Boolean =
            lastMag != 0f && abs(currentMag - lastMag) >= MOTION_THRESHOLD
    }
}
