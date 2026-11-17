package com.irregular.xenopowermeter.data.usb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Kotlin CRC implementation to the standard CRC-16/CCITT-FALSE check
 * values, so it can never drift away from the firmware's CmdStrategy_Crc16.
 * The "123456789" check value (0x29B1) comes from the CRC catalogue, not from
 * this implementation — it is an independent ground truth.
 */
class Crc16Test {

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun `standard check value of 123456789`() {
        assertEquals(0x29B1, Crc16.ccittFalse(ascii("123456789"), 0, 9))
    }

    @Test
    fun `empty range returns the init value`() {
        assertEquals(0xFFFF, Crc16.ccittFalse(ByteArray(8), 0, 0))
    }

    @Test
    fun `single zero byte matches the known value`() {
        // Independently computed by hand for the init-0xFFFF poly-0x1021 chain.
        assertEquals(0xE1F0, Crc16.ccittFalse(byteArrayOf(0x00), 0, 1))
    }

    @Test
    fun `offset and length select a sub-range of the buffer`() {
        val frame = ascii("....123456789....")
        assertEquals(0x29B1, Crc16.ccittFalse(frame, 4, 9))
    }

    @Test
    fun `result only depends on the selected bytes`() {
        val a = byteArrayOf(0x01.toByte(), 0x02, 0x03, 0x04)
        val b = byteArrayOf(0xFF.toByte(), 0x02, 0x03, 0xEE.toByte())
        assertEquals(
            Crc16.ccittFalse(a, 1, 2),
            Crc16.ccittFalse(b, 1, 2)
        )
    }
}
