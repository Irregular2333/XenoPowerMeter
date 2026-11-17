package com.irregular.xenopowermeter.data.usb

import com.irregular.xenopowermeter.data.model.UsbAdcPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packet framing is the contract with the firmware's ADC stream: header
 * (2) + little-endian timestamp (8) + dataCount (1) + 7 bytes per sample.
 */
class UsbAdcPacketTest {

    data class Spec(val range: Int, val vol: Int, val cur: Int, val ref: Int)

    private fun putU16(out: ByteArray, off: Int, v: Int) {
        out[off] = (v and 0xFF).toByte()
        out[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun buildPacket(timestamp: Long, samples: List<Spec>, declaredCount: Int = samples.size): ByteArray {
        // The buffer holds exactly the bytes that were written; declaredCount
        // only feeds the count byte, so tests can model a truncated stream.
        val out = ByteArray(11 + samples.size * UsbAdcPacket.BYTES_PER_SAMPLE)
        out[0] = UsbAdcPacket.HEADER_0
        out[1] = UsbAdcPacket.HEADER_1
        ByteBuffer.wrap(out, 2, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(timestamp)
        out[10] = declaredCount.toByte()
        samples.forEachIndexed { i, s ->
            val off = 11 + i * UsbAdcPacket.BYTES_PER_SAMPLE
            out[off] = s.range.toByte()
            putU16(out, off + 1, s.vol)
            putU16(out, off + 3, s.cur)
            putU16(out, off + 5, s.ref)
        }
        return out
    }

    @Test
    fun `parses a full packet with little-endian fields`() {
        val raw = buildPacket(
            timestamp = 0x0102030405060708L,
            samples = listOf(
                Spec(range = 2, vol = 0x1234, cur = 0xABCD, ref = 0x00FF),
                Spec(range = 1, vol = 0x0500, cur = 0x7FFF, ref = 0x8000)
            )
        )

        val packet = UsbAdcPacket.parse(raw)!!

        assertEquals(0x0102030405060708L, packet.timestamp)
        assertEquals(2, packet.samples.size)
        assertEquals(2, packet.samples[0].range)
        assertEquals(0x1234, packet.samples[0].volAdc)
        assertEquals(0xABCD, packet.samples[0].curAdc)
        assertEquals(0x00FF, packet.samples[0].refAdc)
        assertEquals(1, packet.samples[1].range)
        assertEquals(0x7FFF, packet.samples[1].curAdc)
        assertEquals(0x8000, packet.samples[1].refAdc)
    }

    @Test
    fun `zero dataCount yields an empty sample list`() {
        val raw = buildPacket(timestamp = 42L, samples = emptyList())
        val packet = UsbAdcPacket.parse(raw)!!
        assertEquals(42L, packet.timestamp)
        assertEquals(0, packet.samples.size)
    }

    @Test
    fun `declared count larger than the buffer parses the samples that fit`() {
        // Auto-ranging shortens chunks on the firmware side; the parser must
        // take what is there instead of throwing.
        val raw = buildPacket(
            timestamp = 7L,
            samples = listOf(Spec(1, 10, 20, 30), Spec(2, 40, 50, 60)),
            declaredCount = 3
        )
        val packet = UsbAdcPacket.parse(raw)!!
        assertEquals(2, packet.samples.size)
    }

    @Test
    fun `wrong header returns null`() {
        val raw = buildPacket(1L, listOf(Spec(1, 2, 3, 4)))
        raw[0] = 0xA5.toByte()
        assertNull(UsbAdcPacket.parse(raw))
    }

    @Test
    fun `buffer shorter than the header returns null`() {
        assertNull(UsbAdcPacket.parse(ByteArray(10)))
    }

    @Test
    fun `parses at a non-zero offset`() {
        val junk = byteArrayOf(0x00, 0x11, 0x22)
        val raw = buildPacket(99L, listOf(Spec(3, 1, 2, 3)))
        val combined = junk + raw

        val packet = UsbAdcPacket.parse(combined, junk.size)!!

        assertEquals(99L, packet.timestamp)
        assertEquals(1, packet.samples.size)
    }
}
