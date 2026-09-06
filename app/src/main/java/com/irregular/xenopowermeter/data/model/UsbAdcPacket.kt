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
        const val PACKET_SIZE = 81
        const val SAMPLES_PER_PACKET = 10
        const val BYTES_PER_SAMPLE = 7

        fun parse(data: ByteArray): UsbAdcPacket? {
            if (data.size < PACKET_SIZE) return null
            if (data[0] != HEADER_0 || data[1] != HEADER_1) return null

            val timestamp = java.nio.ByteBuffer.wrap(data, 2, 8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).long

            val dataCount = data[10].toInt() and 0xFF
            val samples = mutableListOf<Sample>()

            for (i in 0 until dataCount) {
                val offset = 11 + i * BYTES_PER_SAMPLE
                if (offset + BYTES_PER_SAMPLE > data.size) break

                val range = data[offset].toInt() and 0xFF
                val volAdc = java.nio.ByteBuffer.wrap(data, offset + 1, 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                val curAdc = java.nio.ByteBuffer.wrap(data, offset + 3, 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                val refAdc = java.nio.ByteBuffer.wrap(data, offset + 5, 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

                samples.add(Sample(range, volAdc, curAdc, refAdc))
            }

            return UsbAdcPacket(timestamp, samples)
        }
    }
}
