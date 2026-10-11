package com.custom.astrion.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class GridDimensionCalculationTest {

    private fun calculateGridHeight(
        itemCount: Int,
        columns: Int,
        tileHeight: Dp,
        spacing: Dp,
    ): Dp {
        if (itemCount == 0) return 0.dp
        val rowCount = (itemCount + columns - 1) / columns
        return (tileHeight * rowCount) + (spacing * (rowCount - 1).coerceAtLeast(0))
    }

    @Test
    fun emptyGridHeightIsZero() {
        val height = calculateGridHeight(0, 3, 68.dp, 10.dp)
        assertEquals(0.dp, height)
    }

    @Test
    fun singleRowGridHeightHasNoInterRowSpacing() {
        val height = calculateGridHeight(3, 3, 68.dp, 10.dp)
        assertEquals(68.dp, height)
    }

    @Test
    fun multipleRowsGridHeightIncludesInterRowSpacing() {
        // 5 items in 3 columns = 2 rows
        // 2 rows * 68dp + 1 spacing (10dp) = 146dp
        val height = calculateGridHeight(5, 3, 68.dp, 10.dp)
        assertEquals(146.dp, height)
    }

    @Test
    fun partialLastRowSizing() {
        // 7 items in 3 columns = 3 rows
        // 3 rows * 53dp (faceH 48dp + lip 5dp) + 2 spacing (6dp) = 159dp + 12dp = 171dp
        val height = calculateGridHeight(7, 3, 53.dp, 6.dp)
        assertEquals(171.dp, height)
    }
}
