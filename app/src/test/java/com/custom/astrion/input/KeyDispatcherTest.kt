package com.custom.astrion.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeyDispatcherTest {
    /** Runs posted callbacks when [advance] passes their time. */
    private class FakeTimers : KeyTimers {
        var now = 0L
        private val queue = mutableListOf<Pair<Long, Runnable>>()
        override fun postDelayed(r: Runnable, delayMs: Long) { queue += (now + delayMs) to r }
        override fun remove(r: Runnable) { queue.removeAll { it.second === r } }
        fun advance(ms: Long) {
            now += ms
            while (true) {
                val due = queue.filter { it.first <= now }.minByOrNull { it.first } ?: break
                queue.remove(due)
                due.second.run()
            }
        }
    }

    private val timers = FakeTimers()
    private val router = HardwareKeyRouter()
    private val fired = mutableListOf<String>()
    private lateinit var keys: KeyDispatcher

    private val code = 136 // SCENE

    @Before
    fun setUp() {
        keys = KeyDispatcher(router, timers, onHoldFired = { fired += "haptic" })
    }

    private fun down(repeat: Int = 0) = keys.handle(code, KeyEvent.ACTION_DOWN, repeat)
    private fun up() = keys.handle(code, KeyEvent.ACTION_UP, 0)
    private fun bind(short: Boolean = true, long: Boolean = false, double: Boolean = false) {
        if (short) router.on(HardwareKey.SCENE) { fired += "short"; true }
        if (long) router.onLong(HardwareKey.SCENE) { fired += "long"; true }
        if (double) router.onDouble(HardwareKey.SCENE) { fired += "double"; true }
    }

    @Test
    fun shortOnlyFiresOnEveryDown() {
        bind()
        down(); down(repeat = 1); up()
        assertEquals(listOf("short", "short"), fired)
    }

    @Test
    fun longCapableTapFiresShortOnRelease() {
        bind(long = true)
        down()
        assertTrue(fired.isEmpty())
        timers.advance(100); up()
        assertEquals(listOf("short"), fired)
    }

    @Test
    fun holdFiresLongWithHapticAndNoShort() {
        bind(long = true)
        down(); timers.advance(KeyDispatcher.LONG_PRESS_MS); up()
        assertEquals(listOf("haptic", "long"), fired)
    }

    @Test
    fun doubleTapFiresDoubleOnly() {
        bind(double = true)
        down(); up()
        timers.advance(100)
        down(); up()
        timers.advance(1000)
        assertEquals(listOf("double"), fired)
    }

    @Test
    fun singleTapWaitsForTheDoubleWindow() {
        bind(double = true)
        down(); up()
        assertTrue(fired.isEmpty())
        timers.advance(KeyDispatcher.DOUBLE_TAP_MS)
        assertEquals(listOf("short"), fired)
    }

    @Test
    fun pageJumpRunsOnDownAndNotAgainOnUp() {
        bind(double = true)
        keys.pageJumpKeys += HardwareKey.SCENE
        down()
        assertEquals(listOf("short"), fired)
        up(); timers.advance(1000)
        assertEquals(listOf("short"), fired)
    }

    @Test
    fun unboundKeyIsNotBound() {
        assertFalse(keys.isBound(code))
        bind()
        assertTrue(keys.isBound(code))
    }
}
