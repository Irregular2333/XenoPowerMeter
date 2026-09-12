package com.irregular.xenopowermeter.data.usb

import com.irregular.xenopowermeter.data.model.CmdResponse
import com.irregular.xenopowermeter.data.model.CmdType
import com.irregular.xenopowermeter.data.model.UsbAdcPacket

/**
 * Splits the single CDC byte stream into ADC data packets (0xAA 0x55) and
 * command response frames (0xA5 0x5A). The firmware interleaves both on one
 * endpoint and never pauses the sample stream, so every byte must pass
 * through here exactly once — reading the port from two places at once
 * tears frames apart.
 *
 * ADC packets carry no CRC; auto-ranging can shorten a chunk, so the length
 * is taken from dataCount (11 + 7*dataCount) instead of assumed constant.
 * Response frames are CRC16 protected; a failed CRC only advances one byte
 * so the scanner resynchronizes on the next frame boundary.
 *
 * Not thread-safe: feed() must be called from a single thread (the USB read
 * loop).
 */
class FrameDemuxer(
    private val onAdcPacket: (UsbAdcPacket) -> Unit,
    private val onResponse: (requestType: CmdType, sequence: Int, response: CmdResponse) -> Unit
) {

    private var buffer = ByteArray(1024)
    private var length = 0

    fun feed(data: ByteArray) {
        ensureCapacity(length + data.size)
        System.arraycopy(data, 0, buffer, length, data.size)
        length += data.size

        var pos = 0
        scan@ while (length - pos >= 2) {
            when {
                buffer[pos] == CMD_HEADER_0 && buffer[pos + 1] == CMD_HEADER_1 -> {
                    if (length - pos < CMD_HEADER_SIZE) break@scan

                    val version = buffer[pos + 2].toInt() and 0xFF
                    val typeCode = buffer[pos + 3].toInt() and 0xFF
                    val payloadLen = readU16(pos + 6)
                    val type = CmdType.fromCode(typeCode and 0x7F)

                    if (version != PROTOCOL_VERSION || (typeCode and 0x80) == 0 ||
                        type == null || payloadLen > MAX_RESPONSE_PAYLOAD
                    ) {
                        pos++
                        continue@scan
                    }

                    val frameLen = CMD_HEADER_SIZE + payloadLen
                    if (length - pos < frameLen) break@scan

                    val expectedCrc = readU16(pos + CMD_HEADER_SIZE + payloadLen)
                    val actualCrc = Crc16.ccittFalse(buffer, pos + 2, 6 + payloadLen)
                    if (expectedCrc != actualCrc) {
                        pos++
                        continue@scan
                    }

                    val status = buffer[pos + 8].toInt() and 0xFF
                    val payload = if (payloadLen > 1) {
                        buffer.copyOfRange(pos + 9, pos + 8 + payloadLen)
                    } else {
                        ByteArray(0)
                    }
                    onResponse(type, readU16(pos + 4), CmdResponse(status, payload))
                    pos += frameLen
                }

                buffer[pos] == UsbAdcPacket.HEADER_0 && buffer[pos + 1] == UsbAdcPacket.HEADER_1 -> {
                    if (length - pos < ADC_HEADER_SIZE) break@scan

                    val dataCount = buffer[pos + 10].toInt() and 0xFF
                    if (dataCount > UsbAdcPacket.SAMPLES_PER_PACKET) {
                        pos++
                        continue@scan
                    }

                    val packetLen = ADC_HEADER_SIZE + dataCount * UsbAdcPacket.BYTES_PER_SAMPLE
                    if (length - pos < packetLen) break@scan

                    UsbAdcPacket.parse(buffer, pos)?.let(onAdcPacket)
                    pos += packetLen
                }

                else -> pos++
            }
        }

        if (pos > 0) {
            System.arraycopy(buffer, pos, buffer, 0, length - pos)
            length -= pos
        }
    }

    private fun readU16(offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)

    private fun ensureCapacity(required: Int) {
        if (buffer.size >= required) return
        var size = buffer.size
        while (size < required) size *= 2
        buffer = buffer.copyOf(size)
    }

    companion object {
        private const val CMD_HEADER_0: Byte = 0xA5.toByte()
        private const val CMD_HEADER_1: Byte = 0x5A.toByte()
        // 2 magic + 1 version + 1 type + 2 sequence + 2 payloadLen (CRC excluded)
        private const val CMD_HEADER_SIZE = 10
        // 1 status + CMD_PROTOCOL_MAX_PAYLOAD, mirroring user_CmdStrategy.c
        private const val MAX_RESPONSE_PAYLOAD = 49
        // 2 header + 8 timestamp + 1 dataCount
        private const val ADC_HEADER_SIZE = 11
        private const val PROTOCOL_VERSION = 1
    }
}
