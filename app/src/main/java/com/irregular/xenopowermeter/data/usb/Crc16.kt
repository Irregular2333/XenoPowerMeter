package com.irregular.xenopowermeter.data.usb

/**
 * CRC16-CCITT-FALSE (poly 0x1021, init 0xFFFF, MSB first).
 * Must stay identical to CmdStrategy_Crc16 in User/user_CmdStrategy.c, where
 * the covered range is offset 2, length 6 + payloadLen (header minus magic,
 * plus payload, excluding the CRC field itself).
 */
object Crc16 {

    fun ccittFalse(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
