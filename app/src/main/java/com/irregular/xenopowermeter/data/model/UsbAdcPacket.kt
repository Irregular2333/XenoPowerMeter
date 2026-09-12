package com.irregular.xenopowermeter.data.model

data class UsbAdcPacket(
    val timestamp: Long,
    val samples: List<Sample>
) {
    data class Sample(
        val range: Int,
        val volAdc: Int,
        val curAdc: Int,
        val refAdc: Int
    )

    companion object {
        const val HEADER_0: Byte = 0xAA.toByte()
        const val HEADER_1: Byte = 0x55
        const val SAMPLES_PER_PACKET = 10
        const val BYTES_PER_SAMPLE = 7

        /**
         * Parses one packet starting at [offset]. Auto-ranging shortens chunks
         * on the firmware side, so the actual packet length is 11 + 7*dataCount,
         * not a fixed 81 bytes.
         */
        fun parse(data: ByteArray, offset: Int = 0): UsbAdcPacket? {
            if (data.size - offset < 11) return null
            if (data[offset] != HEADER_0 || data[offset + 1] != HEADER_1) return null

            val timestamp = java.nio.ByteBuffer.wrap(data, offset + 2, 8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).long

            val dataCount = data[offset + 10].toInt() and 0xFF
            val samples = ArrayList<Sample>(dataCount)

            for (i in 0 until dataCount) {
                val off = offset + 11 + i * BYTES_PER_SAMPLE
                if (off + BYTES_PER_SAMPLE > data.size) break

                val range = data[off].toInt() and 0xFF
                val volAdc = readU16(data, off + 1)
                val curAdc = readU16(data, off + 3)
                val refAdc = readU16(data, off + 5)

                samples.add(Sample(range, volAdc, curAdc, refAdc))
            }

            return UsbAdcPacket(timestamp, samples)
        }

        private fun readU16(data: ByteArray, off: Int): Int =
            (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    }
}
