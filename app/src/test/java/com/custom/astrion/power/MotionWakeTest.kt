package com.custom.astrion.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionWakeTest {

    @Test
    fun testCalculateMagnitudeAtRest() {
        val mag = MotionWake.calculateMagnitude(0f, 0f, 9.8f)
        assertEquals(9.8f, mag, 0.001f)
    }

    @Test
    fun testCalculateMagnitudeHighAcceleration() {
        val mag = MotionWake.calculateMagnitude(35f, 35f, 35f)
        assertTrue(mag > 0f)
        assertTrue(mag.isFinite())
        assertEquals(60.621777f, mag, 0.01f)
    }

    @Test
    fun testMotionDetectionIgnoresSensorJitterBelowThreshold() {
        val lastMag = 9.8f
        val currentMag = 10.3f // delta = 0.5 < 0.9 threshold
        assertFalse(MotionWake.isMotionDetected(currentMag, lastMag))
    }

    @Test
    fun testMotionDetectionTriggersOnSignificantDelta() {
        val lastMag = 9.8f
        val currentMag = 11.2f // delta = 1.4 >= 0.9 threshold
        assertTrue(MotionWake.isMotionDetected(currentMag, lastMag))
    }

    @Test
    fun testMotionDetectionIgnoresInitialBaselineReading() {
        val lastMag = 0f // initial uncalibrated state
        val currentMag = 9.8f
        assertFalse(MotionWake.isMotionDetected(currentMag, lastMag))
    }

    @Test
    fun testMotionDetectionHandlesNegativeDirectionShift() {
        val lastMag = 12.0f
        val currentMag = 10.5f // delta = 1.5 >= 0.9 threshold
        assertTrue(MotionWake.isMotionDetected(currentMag, lastMag))
    }

    @Test
    fun testMotionWakeLifecycleIdempotency() {
        val mw: MotionWakeController = MotionWake()
        assertFalse(mw.isHardwareInterruptSupported())

        // Multiple starts and stops without throwing
        var wakeCount = 0
        mw.start { wakeCount++ }
        mw.start { wakeCount++ }
        assertEquals(0, wakeCount)

        mw.stop()
        mw.stop()
    }

    @Test
    fun testMotionWakeDisabledWhenMinutesZero() {
        val mw = MotionWake()
        var wakeCalled = false
        mw.start(minutes = 0) { wakeCalled = true }
        mw.stop()
        assertFalse(wakeCalled)
    }
}
