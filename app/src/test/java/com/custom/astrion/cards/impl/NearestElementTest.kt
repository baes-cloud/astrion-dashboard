package com.custom.astrion.cards.impl

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Floorplan taps go to the closest icon, not the one drawn last. */
class NearestElementTest {
    private val centres = mapOf(0 to Offset(20f, 20f), 1 to Offset(50f, 20f), 2 to Offset(200f, 100f))

    @Test fun aTapBetweenTwoIconsGoesToTheCloserOne() {
        assertEquals(0, nearestElement(centres, 30f, 20f))
        assertEquals(1, nearestElement(centres, 40f, 22f))
        assertEquals(2, nearestElement(centres, 190f, 90f))
    }

    @Test fun aDeadHeatGoesToTheLowerIndex() {
        assertEquals(0, nearestElement(centres, 35f, 20f))
    }

    @Test fun noIconsNoTarget() {
        assertNull(nearestElement(emptyMap(), 1f, 1f))
    }
}
