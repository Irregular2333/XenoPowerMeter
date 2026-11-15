package com.irregular.xenopowermeter.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerStatisticsTest {
    @Test fun intermittentLoadIncludesZeroPowerSamples() {
        val statistics = PowerStatistics()
        statistics.add(5f, 1_000_000f)
        statistics.add(5f, 0f)
        assertEquals(2.5f, statistics.averageWatts, 0.00001f)
    }

    @Test fun zeroVoltageIsValidAndReverseCurrentKeepsItsSign() {
        val statistics = PowerStatistics()
        statistics.add(5f, -1_000_000f)
        statistics.add(0f, 1_000_000f)
        assertEquals(-2.5f, statistics.averageWatts, 0.00001f)
    }

    @Test fun invalidSamplesAreExcludedAndClearStartsANewAverage() {
        val statistics = PowerStatistics()
        statistics.add(Float.NaN, 1f)
        statistics.add(5f, Float.POSITIVE_INFINITY)
        statistics.add(5f, 1_000_000f)
        assertEquals(5f, statistics.averageWatts, 0f)
        statistics.clear()
        assertEquals(0f, statistics.averageWatts, 0f)
        statistics.add(3f, 0f)
        assertEquals(0f, statistics.averageWatts, 0f)
    }
}
