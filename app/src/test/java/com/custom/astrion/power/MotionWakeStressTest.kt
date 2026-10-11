package com.custom.astrion.power

import android.hardware.TriggerEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MotionWakeStressTest {

    @Test
    fun testRapidStartStopLifecycleSequential() {
        val mw = MotionWake()
        var wakeCalls = 0

        // 1000 sequential rapid cycles
        for (i in 0 until 1000) {
            mw.start { wakeCalls++ }
            mw.stop()
        }

        assertEquals(0, wakeCalls)

        // Verify stopped state via reflection
        val activeJobField = MotionWake::class.java.getDeclaredField("activeJob").apply { isAccessible = true }
        assertNull("activeJob should be null after stop", activeJobField.get(mw))
    }

    @Test
    fun testRapidStartStopLifecycleMultiThreaded() {
        val mw = MotionWake()
        val threadCount = 16
        val cyclesPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)
        val wakeCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until cyclesPerThread) {
                        if (i % 2 == 0) {
                            mw.start { wakeCount.incrementAndGet() }
                        } else {
                            mw.stop()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timeout waiting for threads", latch.await(15, TimeUnit.SECONDS))
        executor.shutdown()
        mw.stop()

        assertEquals("No exceptions should be thrown during concurrent start/stop", 0, errorCount.get())
    }

    @Test
    fun testSensorJitterBelowThresholdExtensive() {
        val random = Random(42)
        val baseGravity = 9.80665f
        var currentLastMag = baseGravity

        // 50,000 jitter samples where delta is strictly below MOTION_THRESHOLD (0.9f)
        for (i in 0 until 50_000) {
            // Random jitter between -0.89f and +0.89f
            val jitter = (random.nextFloat() * 2f - 1f) * 0.89f
            val newMag = currentLastMag + jitter

            val detected = MotionWake.isMotionDetected(newMag, currentLastMag)
            assertFalse(
                "Jitter of $jitter m/s² from $currentLastMag to $newMag should NOT trigger motion detection",
                detected
            )

            // Even under accumulated slow drift, per-sample filtering must hold
            currentLastMag = newMag
            if (currentLastMag < 5f || currentLastMag > 15f) {
                currentLastMag = baseGravity
            }
        }
    }

    @Test
    fun testSensorJitterFloatingPointBoundary() {
        val base = 9.8f
        // Right below threshold: delta = 0.89999f
        assertFalse(MotionWake.isMotionDetected(base + 0.89999f, base))
        assertFalse(MotionWake.isMotionDetected(base - 0.89999f, base))

        // Above threshold: delta = 0.9001f
        assertTrue(MotionWake.isMotionDetected(base + 0.9001f, base))
        assertTrue(MotionWake.isMotionDetected(base - 0.9001f, base))

        // EMPIRICAL FLOATING POINT PHENOMENON:
        // In IEEE 754 single-precision float:
        // (9.8f + 0.9f) - 9.8f evaluates to 0.8999996f (< 0.9f)
        // (9.8f - 0.9f) - 9.8f evaluates to -0.8999996f (< 0.9f in magnitude)
        // Therefore, adding or subtracting exactly 0.9f in Float arithmetic evaluates to < MOTION_THRESHOLD!
        val upDelta = Math.abs((base + 0.9f) - base)
        val downDelta = Math.abs((base - 0.9f) - base)
        println("EMPIRICAL FINDING: base + 0.9f float delta: $upDelta; base - 0.9f float delta: $downDelta")
        assertFalse("Float rounding makes 9.8f + 0.9f delta strictly less than 0.9f", MotionWake.isMotionDetected(base + 0.9f, base))
        assertFalse("Float rounding makes 9.8f - 0.9f delta strictly less than 0.9f", MotionWake.isMotionDetected(base - 0.9f, base))
    }

    @Test
    fun testExtremeSensorValuesAndOverflowSafety() {
        // 1. Zero magnitude (free fall)
        val zeroMag = MotionWake.calculateMagnitude(0f, 0f, 0f)
        assertEquals(0f, zeroMag, 0.00001f)
        assertFalse(MotionWake.isMotionDetected(zeroMag, 0f))
        assertTrue("Dropping to zero from 1g must detect motion", MotionWake.isMotionDetected(zeroMag, 9.8f))

        // 2. High acceleration within physical/sensor limits (e.g. shock/drop)
        val shockMag = MotionWake.calculateMagnitude(100f, 200f, 300f)
        assertTrue(shockMag.isFinite())
        assertTrue(shockMag > 350f)
        assertTrue(MotionWake.isMotionDetected(shockMag, 9.8f))

        // 3. Negative acceleration vectors
        val negMag = MotionWake.calculateMagnitude(-9.8f, 0f, 0f)
        assertEquals(9.8f, negMag, 0.001f)

        // 4. Float.MAX_VALUE edge case: squared float overflows to Infinity
        val maxMag = MotionWake.calculateMagnitude(Float.MAX_VALUE, 0f, 0f)
        assertTrue("Float.MAX_VALUE squared overflows float to Infinity", maxMag.isInfinite())

        // 5. Float.MIN_VALUE edge case
        val minMag = MotionWake.calculateMagnitude(Float.MIN_VALUE, 0f, 0f)
        assertTrue(minMag >= 0f)

        // 6. NaN resilience
        val nanDetected = MotionWake.isMotionDetected(Float.NaN, 9.8f)
        assertFalse("NaN magnitude should not trigger motion", nanDetected)
    }

    @Test
    fun testTriggerEventsOnSignificantMotion() {
        var wakeTriggered = false
        val mw = MotionWake(onMotion = { wakeTriggered = true })

        val triggerListenerField = MotionWake::class.java.getDeclaredField("triggerListener").apply { isAccessible = true }
        val triggerListener = triggerListenerField.get(mw) as TriggerEventListener

        val hwActiveField = MotionWake::class.java.getDeclaredField("hwTriggerActive").apply { isAccessible = true }
        hwActiveField.set(mw, true)
        assertTrue(hwActiveField.get(mw) as Boolean)

        // Trigger significant motion event
        triggerListener.onTrigger(null)

        assertTrue("onMotion callback must be called on trigger", wakeTriggered)
        assertFalse("hwTriggerActive must be reset to false on trigger", hwActiveField.get(mw) as Boolean)
    }

    @Test
    fun testCoroutineCancellationOnStop() {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val mw = MotionWake(scope = testScope)

        mw.start(minutes = 10)

        val activeJobField = MotionWake::class.java.getDeclaredField("activeJob").apply { isAccessible = true }
        val dummyJob = testScope.run { Job() }
        activeJobField.set(mw, dummyJob)
        assertTrue(dummyJob.isActive)

        mw.stop()

        assertTrue("stop() must cancel activeJob", dummyJob.isCancelled)
        assertNull("stop() must clear activeJob field", activeJobField.get(mw))

        testScope.cancel()
    }
}
