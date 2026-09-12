package com.irregular.xenopowermeter.data.model

data class Calibration(
    val lowScaleMultiplier: Float = 1.0f,
    val lowOffsetUa: Float = 0.0f,
    val midScaleMultiplier: Float = 1.0f,
    val midOffsetUa: Float = 0.0f,
    val highScaleMultiplier: Float = 1.0f,
    val highOffsetUa: Float = 0.0f
) {
    companion object {
        fun fromByteArray(data: ByteArray): Calibration? {
            if (data.size < 24) return null
            val buf = java.nio.ByteBuffer.wrap(data)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return Calibration(
                lowScaleMultiplier = buf.float,
                lowOffsetUa = buf.float,
                midScaleMultiplier = buf.float,
                midOffsetUa = buf.float,
                highScaleMultiplier = buf.float,
                highOffsetUa = buf.float
            )
        }

        val DEFAULT = Calibration()
    }
}
