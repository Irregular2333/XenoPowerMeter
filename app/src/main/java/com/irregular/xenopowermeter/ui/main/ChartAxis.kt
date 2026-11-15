package com.irregular.xenopowermeter.ui.main

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** The top value is derived from the ticks, so labels and traces cannot drift. */
data class NiceAxisRange(val min: Float, val step: Float, val steps: Int) {
    val max: Float get() = min + step * steps

    fun tickValue(index: Int): Float = min + step * index

    fun fractionOf(value: Float): Float = (value - min) / (max - min)
}

// Leave headroom above the 33 V ADC range while keeping 5 V ticks.
internal val FullRangeVoltageAxis = NiceAxisRange(0f, 5f, 7)
internal val FullRangeCurrentAxis = NiceAxisRange(-12_000_000f, 4_000_000f, 6)

internal fun computeNiceRange(dataMin: Float, dataMax: Float, minMarginFraction: Float): NiceAxisRange {
    val span = (dataMax - dataMin).coerceAtLeast(0.0001f)
    val margin = span * minMarginFraction
    val rawMin = dataMin - margin
    val rawMax = dataMax + margin
    val niceSpan = rawMax - rawMin

    val exponent = floor(log10(niceSpan.toDouble())).toInt()
    val fraction = niceSpan / 10.0.pow(exponent.toDouble()).toFloat()
    val niceFraction = when {
        fraction <= 1.5f -> 1.0f
        fraction <= 3.0f -> 2.0f
        fraction <= 7.0f -> 5.0f
        else -> 10.0f
    }

    val step = (niceFraction * 10.0.pow(exponent.toDouble())).toFloat() / 5f
    val niceMin = floor(rawMin / step) * step
    val niceMax = ceil(rawMax / step) * step
    val steps = ((niceMax - niceMin) / step).roundToInt().coerceAtLeast(1)
    return NiceAxisRange(niceMin, step, steps)
}

