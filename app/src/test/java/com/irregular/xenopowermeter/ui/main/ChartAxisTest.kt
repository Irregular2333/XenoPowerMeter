package com.irregular.xenopowermeter.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartAxisTest {
    @Test fun fullRangeVoltageLabelsAndTracesShareTheSameScale() {
        val axis = FullRangeVoltageAxis
        assertTrue(axis.max >= 33f)
        for (tick in 0..axis.steps) {
            assertEquals(tick.toFloat() / axis.steps, axis.fractionOf(axis.tickValue(tick)), 0.00001f)
        }
        assertEquals(35f, axis.max, 0f)
        assertEquals(2f / 7f, axis.fractionOf(10f), 0.00001f)
    }

    @Test fun fullRangeCurrentDisplaysBothDirectionsAndZero() {
        val axis = FullRangeCurrentAxis
        assertEquals(0f, axis.fractionOf(-12_000_000f), 0f)
        assertEquals(0.5f, axis.fractionOf(0f), 0f)
        assertEquals(1f, axis.fractionOf(12_000_000f), 0f)
    }

    @Test fun automaticRangesCoverConstantSmallAndSignedSignals() {
        for ((min, max) in listOf(0f to 0f, 5f to 5f, -100f to 250f, 0.001f to 0.002f)) {
            val axis = computeNiceRange(min, max, 2f)
            assertTrue(axis.min <= min)
            assertTrue(axis.max >= max)
            assertTrue(axis.steps > 0)
            assertEquals(0f, axis.fractionOf(axis.tickValue(0)), 0.00001f)
            assertEquals(1f, axis.fractionOf(axis.tickValue(axis.steps)), 0.00001f)
        }
    }

}
