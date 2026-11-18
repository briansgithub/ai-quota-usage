package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsedGradientTest {
    @Test
    fun onPaceIsPaleBehindIsRedAheadIsGreen() {
        val on = UsedGradient.fill(50.0, 50.0)
        val behind = UsedGradient.fill(25.0, 50.0)
        val ahead = UsedGradient.fill(75.0, 50.0)
        assertTrue(on.r > 200 && on.g > 200 && on.b > 200)
        assertTrue(behind.r > behind.g && behind.r > behind.b)
        assertTrue(ahead.g > ahead.r && ahead.g > ahead.b)
        assertTrue(UsedGradient.outline(50.0, 50.0).r < 40)
    }

    @Test
    fun fullUsedIsAlwaysGreen() {
        assertEquals(UsedGradient.GREEN, UsedGradient.fill(100.0, 10.0))
        assertEquals(UsedGradient.GREEN, UsedGradient.fill(100.0, 100.0))
        assertEquals(UsedGradient.GREEN, UsedGradient.fill(100.0, null))
    }

    @Test
    fun unknownWindowIsNeutralNotRawUsedGradient() {
        assertEquals(UsedGradient.MID, UsedGradient.fill(0.0, null))
        assertEquals(UsedGradient.MID, UsedGradient.fill(50.0, null))
        assertEquals(UsedGradient.MID, UsedGradient.fill(90.0, null))
        assertNotEquals(UsedGradient.RED, UsedGradient.fill(0.0, null))
    }

    @Test
    fun deltaSaturatesAtTwentyFivePoints() {
        assertEquals(UsedGradient.RED, UsedGradient.fill(0.0, 25.0))
        assertEquals(UsedGradient.RED, UsedGradient.fill(0.0, 80.0))
        assertEquals(UsedGradient.GREEN, UsedGradient.fill(75.0, 50.0))
        assertEquals(UsedGradient.GREEN, UsedGradient.fill(90.0, 50.0))
    }

    @Test
    fun interpolatesTowardTheStops() {
        val mildBehind = UsedGradient.fill(40.0, 50.0)
        val mildAhead = UsedGradient.fill(60.0, 50.0)
        assertTrue(mildBehind.r > mildBehind.g)
        assertTrue(mildAhead.g > mildAhead.r)
        assertNotEquals(UsedGradient.RED, mildBehind)
        assertNotEquals(UsedGradient.GREEN, mildAhead)
    }

    @Test
    fun capFillIsGreenAtZeroWhiteAtHalfRedAtFull() {
        assertEquals(UsedGradient.GREEN, UsedGradient.capFill(0.0))
        assertEquals(UsedGradient.MID, UsedGradient.capFill(50.0))
        assertEquals(UsedGradient.RED, UsedGradient.capFill(100.0))
        assertEquals(UsedGradient.GREEN, UsedGradient.capFill(-10.0))
        assertEquals(UsedGradient.RED, UsedGradient.capFill(140.0))
    }

    @Test
    fun capFillInterpolatesTowardTheStops() {
        val low = UsedGradient.capFill(25.0)
        assertEquals(141, low.r)
        assertEquals(206, low.g)
        assertEquals(166, low.b)
        val high = UsedGradient.capFill(75.0)
        assertEquals(230, high.r)
        assertEquals(152, high.g)
        assertEquals(149, high.b)
        assertTrue(UsedGradient.capOutline(50.0).r < 40)
    }
}
