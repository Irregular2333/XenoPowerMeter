package com.irregular.xenopowermeter.data.converter

import com.irregular.xenopowermeter.data.model.Calibration

object DataConverter {

    private const val SCALE_LOW = 3.0f / 4095.0f / 50.0f / 50.0f * 1_000_000f
    private const val SCALE_MID = 3.0f / 4095.0f / 50.0f / 0.5f * 1_000_000f
    private const val SCALE_HIGH = 3.0f / 4095.0f / 50.0f / 0.005f * 1_000_000f

    fun convertVoltage(volAdc: Int): Float {
        return volAdc * (3.0f / 4095.0f * 11.0f)
    }

    fun convertCurrent(curAdc: Int, refAdc: Int, range: Int, calibration: Calibration): Float {
        val deltaLsb = (curAdc - refAdc).toFloat()
        return when (range) {
            1 -> deltaLsb * SCALE_LOW * calibration.lowScaleMultiplier + calibration.lowOffsetUa
            2 -> deltaLsb * SCALE_MID * calibration.midScaleMultiplier + calibration.midOffsetUa
            3 -> deltaLsb * SCALE_HIGH * calibration.highScaleMultiplier + calibration.highOffsetUa
            else -> 0.0f
        }
    }

    fun correctCurrent(currentUa: Float, voltageMv: Float): Float {
        val errorCurrent10na = voltageMv / 11.0f
        return currentUa - errorCurrent10na / 100.0f
    }

    fun formatVoltage(v: Float): String {
        return String.format("%.2f V", v)
    }

    fun formatCurrent(ua: Float): String {
        val abs = kotlin.math.abs(ua)
        return when {
            abs >= 1_000_000f -> String.format("%.2f A", ua / 1_000_000f)
            abs >= 1_000f -> String.format("%.2f mA", ua / 1_000f)
            abs >= 1f -> String.format("%.2f \u03BCA", ua)
            else -> String.format("%.2f nA", ua * 1000f)
        }
    }

    fun formatPower(watts: Float): String {
        val abs = kotlin.math.abs(watts)
        return when {
            abs >= 1.0f -> String.format("%.2f W", watts)
            else -> String.format("%.2f mW", watts * 1000f)
        }
    }
}
