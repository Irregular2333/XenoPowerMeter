package com.irregular.xenopowermeter.data.usb

import com.irregular.xenopowermeter.data.model.CmdResponse
import com.irregular.xenopowermeter.data.model.CmdType
import com.irregular.xenopowermeter.data.model.UsbAdcPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDemuxerTest {

    @Test
    fun decodesCalibrationResponseWithoutTrailingBytes() {
        val capture = Capture()

        capture.demuxer.feed(CALIBRATION_RESPONSE)

        assertEquals(1, capture.responses.size)
        val response = capture.responses.single()
        assertEquals(CmdType.CAL_GET, response.type)
        assertEquals(1, response.sequence)
        assertEquals(CmdResponse.STATUS_OK, response.value.status)
        assertArrayEquals(
            hex("00 00 80 3f 00 00 00 00 00 00 80 3f 00 00 00 00 00 00 80 3f 00 00 00 00"),
            response.value.payload
        )
        assertTrue(capture.adcPackets.isEmpty())
    }

    @Test
    fun decodesMinimumStatusOnlyResponseAtBufferBoundary() {
        val capture = Capture()

        // Place the CRC exactly at the end of the initial 1024-byte buffer.
        capture.demuxer.feed(ByteArray(1024 - STATUS_RESPONSE.size) + STATUS_RESPONSE)

        assertEquals(
            listOf(Response(CmdType.CAL_RESET, 0x1234, CmdResponse(0, byteArrayOf()))),
            capture.responses
        )
    }

    @Test
    fun preservesDeviceErrorStatusWithoutPayload() {
        val capture = Capture()

        capture.demuxer.feed(hex("a5 5a 01 94 34 12 01 00 05 18 95"))

        assertEquals(
            listOf(Response(CmdType.CAL_RESET, 0x1234, CmdResponse(5, byteArrayOf()))),
            capture.responses
        )
    }

    @Test
    fun acceptsMaximumFirmwareResponsePayload() {
        val capture = Capture()
        val frame = hex(
            "a5 5a 01 95 ff ff 30 00 00 " +
                "00 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f " +
                "10 11 12 13 14 15 16 17 18 19 1a 1b 1c 1d 1e 1f " +
                "20 21 22 23 24 25 26 27 28 29 2a 2b 2c 2d 2e 3b 5b"
        )

        capture.demuxer.feed(frame)

        val response = capture.responses.single()
        assertEquals(CmdType.FW_UPDATE, response.type)
        assertEquals(0xffff, response.sequence)
        assertEquals(0, response.value.status)
        assertArrayEquals(ByteArray(47) { it.toByte() }, response.value.payload)
    }

    @Test
    fun acceptsResponseAtEveryUsbSplitBoundary() {
        for (split in 0..CALIBRATION_RESPONSE.size) {
            val capture = Capture()
            capture.demuxer.feed(CALIBRATION_RESPONSE.copyOfRange(0, split))
            assertEquals("split=$split", if (split == CALIBRATION_RESPONSE.size) 1 else 0, capture.responses.size)

            capture.demuxer.feed(CALIBRATION_RESPONSE.copyOfRange(split, CALIBRATION_RESPONSE.size))

            assertEquals("split=$split", 1, capture.responses.size)
            assertEquals("split=$split", CmdType.CAL_GET, capture.responses.single().type)
        }
    }

    @Test
    fun preservesOrderOfCoalescedAdcAndCommandFrames() {
        val capture = Capture()

        capture.demuxer.feed(ADC_PACKET + RANGE_RESPONSE + STATUS_RESPONSE + ADC_PACKET)

        assertEquals(listOf("adc", "response", "response", "adc"), capture.order)
        assertEquals(2, capture.adcPackets.size)
        assertEquals(
            UsbAdcPacket(123456, listOf(UsbAdcPacket.Sample(2, 1000, 2049, 2048))),
            capture.adcPackets.first()
        )
        assertEquals(CmdType.RANGE_SET, capture.responses.first().type)
        assertArrayEquals(byteArrayOf(2, 3), capture.responses.first().value.payload)
        assertEquals(CmdType.CAL_RESET, capture.responses.last().type)
    }

    @Test
    fun acceptsInterleavedFramesOneByteAtATime() {
        val capture = Capture()
        val stream = ADC_PACKET + CALIBRATION_RESPONSE + RANGE_RESPONSE

        for (byte in stream) capture.demuxer.feed(byteArrayOf(byte))

        assertEquals(listOf("adc", "response", "response"), capture.order)
        assertEquals(listOf(CmdType.CAL_GET, CmdType.RANGE_SET), capture.responses.map { it.type })
    }

    @Test
    fun recoversFromCorruptCrcAndNoise() {
        val capture = Capture()
        val corrupt = STATUS_RESPONSE.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        }

        capture.demuxer.feed(hex("00 7f a5 00") + corrupt + hex("55 01") + RANGE_RESPONSE)

        assertEquals(1, capture.responses.size)
        assertEquals(CmdType.RANGE_SET, capture.responses.single().type)
    }

    @Test
    fun rejectsMissingStatusAndOversizedPayloadThenRecovers() {
        val capture = Capture()
        // Both frames have valid CRCs but lengths the firmware cannot emit.
        val missingStatus = hex("a5 5a 01 90 02 00 00 00 52 80")
        val oversized = hex("a5 5a 01 90 02 00 31 00") + ByteArray(49) { 0x7e } + hex("00 40")

        capture.demuxer.feed(missingStatus + oversized + STATUS_RESPONSE)

        assertEquals(1, capture.responses.size)
        assertEquals(CmdType.CAL_RESET, capture.responses.single().type)
    }

    @Test
    fun rejectsUnsupportedHeadersThenRecovers() {
        val capture = Capture()
        val unsupportedVersion = hex("a5 5a 02 92 34 12 03 00 00 02 03 da 20")
        val request = hex("a5 5a 01 12 34 12 03 00 00 02 03 1f f0")
        val unknownType = hex("a5 5a 01 ff 34 12 03 00 00 02 03 42 d6")

        capture.demuxer.feed(unsupportedVersion + request + unknownType + RANGE_RESPONSE)

        assertEquals(1, capture.responses.size)
        assertEquals(CmdType.RANGE_SET, capture.responses.single().type)
    }

    private data class Response(val type: CmdType, val sequence: Int, val value: CmdResponse)

    private class Capture {
        val responses = mutableListOf<Response>()
        val adcPackets = mutableListOf<UsbAdcPacket>()
        val order = mutableListOf<String>()
        val demuxer = FrameDemuxer(
            onAdcPacket = {
                adcPackets += it
                order += "adc"
            },
            onResponse = { type, sequence, response ->
                responses += Response(type, sequence, response)
                order += "response"
            }
        )
    }

    companion object {
        // Fixed wire fixtures use CRC-CCITT-FALSE independently calculated
        // from the firmware layout; tests never encode with production Crc16.
        private val CALIBRATION_RESPONSE = hex(
            "a5 5a 01 90 01 00 19 00 00 " +
                "00 00 80 3f 00 00 00 00 00 00 80 3f 00 00 00 00 " +
                "00 00 80 3f 00 00 00 00 d6 f7"
        )
        private val STATUS_RESPONSE = hex("a5 5a 01 94 34 12 01 00 00 bd c5")
        private val RANGE_RESPONSE = hex("a5 5a 01 92 34 12 03 00 00 02 03 9e 0d")
        private val ADC_PACKET = hex("aa 55 40 e2 01 00 00 00 00 00 01 02 e8 03 01 08 00 08")

        private fun hex(value: String): ByteArray =
            value.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
    }
}
