package com.irregular.xenopowermeter.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CalibrationTest {

    private fun buildBytes(
        lowScale: Float, lowOffset: Float,
        midScale: Float, midOffset: Float,
        highScale: Float, highOffset: Float
    ): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(lowScale).putFloat(lowOffset)
        buf.putFloat(midScale).putFloat(midOffset)
        buf.putFloat(highScale).putFloat(highOffset)
        return buf.array()
    }

    @Test
    fun `parses six little-endian floats in order`() {
        val cal = Calibration.fromByteArray(
            buildBytes(1.5f, -2.5f, 3.5f, -4.5f, 5.5f, -6.5f)
        )!!
        assertEquals(1.5f, cal.lowScaleMultiplier, 0f)
        assertEquals(-2.5f, cal.lowOffsetUa, 0f)
        assertEquals(3.5f, cal.midScaleMultiplier, 0f)
        assertEquals(-4.5f, cal.midOffsetUa, 0f)
        assertEquals(5.5f, cal.highScaleMultiplier, 0f)
        assertEquals(-6.5f, cal.highOffsetUa, 0f)
    }

    @Test
    fun `a short buffer returns null`() {
        assertNull(Calibration.fromByteArray(ByteArray(23)))
    }

    @Test
    fun `defaults are the identity calibration`() {
        val cal = Calibration.DEFAULT
        assertEquals(1.0f, cal.lowScaleMultiplier, 0f)
        assertEquals(1.0f, cal.midScaleMultiplier, 0f)
        assertEquals(1.0f, cal.highScaleMultiplier, 0f)
        assertEquals(0.0f, cal.lowOffsetUa, 0f)
        assertEquals(0.0f, cal.midOffsetUa, 0f)
        assertEquals(0.0f, cal.highOffsetUa, 0f)
    }
}
