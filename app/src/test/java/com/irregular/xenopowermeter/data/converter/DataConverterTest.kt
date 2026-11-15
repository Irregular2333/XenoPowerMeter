package com.irregular.xenopowermeter.data.converter

import com.irregular.xenopowermeter.data.model.Calibration
import org.junit.Assert.assertEquals
import org.junit.Test

class DataConverterTest {
    @Test
    fun currentRangesUseFirmwareTwelveBitLsbScale() {
        val delta = 100
        val expected = listOf(
            1 to delta * (3.0f / 4096.0f / 50.0f / 50.0f * 1_000_000f),
            2 to delta * (3.0f / 4096.0f / 50.0f / 0.5f * 1_000_000f),
            3 to delta * (3.0f / 4096.0f / 50.0f / 0.005f * 1_000_000f)
        )

        expected.forEach { (range, value) ->
            assertEquals(value, DataConverter.convertCurrent(2148, 2048, range, Calibration.DEFAULT), 0.0001f)
        }
    }

    @Test
    fun voltageConversionRetainsFirmware4095EndpointFormula() {
        assertEquals(0f, DataConverter.convertVoltage(0), 0f)
        assertEquals(33f, DataConverter.convertVoltage(4095), 0.0001f)
    }
}
