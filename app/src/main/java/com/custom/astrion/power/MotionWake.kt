package com.custom.astrion.power

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Motion-wake: an accelerometer wakes the screen when the remote is
 * lifted/moved. NOT cheap at rest: a wake-up accelerometer wakes the SoC for
 * every reading (~5 a second), moving or not, so the CPU can never suspend
 * while it's registered. Hence only for `power.motion_wake_minutes` after the
 * screen goes off — when a pick-up is most likely — and never on the dock,
 * where the screen is kept on anyway.
 */
class MotionWake(
    context: Context,
    private val handler: Handler,
    private val onMotion: () -> Unit,
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    // Prefer a wake-up accelerometer so events still arrive with the screen
    // off; fall back to the normal one (which only helps while awake).
    private val sensor: Sensor? = sensorManager?.getSensorList(Sensor.TYPE_ACCELEROMETER)
        ?.firstOrNull { it.isWakeUpSensor }
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastMagnitude = 0f

    /** elapsedRealtime after which motion-wake gives up; Long.MAX_VALUE = never. */
    private var until = 0L

    /** Ends the motion-wake window; see [until]. */
    private val timeout = Runnable { stop() }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Checked here too, not just by the timer: uptime-based timers
            // stall while the SoC sleeps, the sensor's own wake-ups don't.
            if (SystemClock.elapsedRealtime() > until) {
                handler.post { stop() }
                return
            }
            val (x, y, z) = event.values
            val mag = sqrt(x * x + y * y + z * z)
            if (lastMagnitude != 0f && abs(mag - lastMagnitude) > MOTION_THRESHOLD) {
                onMotion()
            }
            lastMagnitude = mag
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Listen for [minutes] (0 = never, -1 = until [stop]). Callers only start
     * it with the screen off — with it on there is nothing to wake — and off
     * the dock.
     */
    fun start(minutes: Int) {
        stop()
        val sm = sensorManager ?: return
        val s = sensor ?: return
        if (minutes == 0) return
        until = if (minutes < 0) Long.MAX_VALUE else SystemClock.elapsedRealtime() + minutes * 60_000L
        if (minutes > 0) handler.postDelayed(timeout, minutes * 60_000L)
        // A fresh baseline, so a reading from before the screen went off
        // can't register as movement.
        lastMagnitude = 0f
        sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
        handler.removeCallbacks(timeout)
    }

    private companion object {
        /** Accel magnitude delta (m/s²) that counts as "moved". */
        const val MOTION_THRESHOLD = 0.9f
    }
}
