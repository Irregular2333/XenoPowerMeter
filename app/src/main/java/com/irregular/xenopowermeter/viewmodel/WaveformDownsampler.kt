package com.irregular.xenopowermeter.viewmodel

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reduces each continuous segment independently. Segment boundaries are part
 * of the data: a selection on either side of a gap must never imply a line
 * across it. The point budget is soft when keeping every segment's endpoints
 * requires more points than requested.
 */
internal fun downsampleWaveform(
    data: List<WaveformPoint>,
    targetPoints: Int
): List<WaveformPoint> {
    // The caller can reuse its scratch list as soon as this function returns.
    if (data.size <= targetPoints || data.size <= 2) return data.toList()

    val segments = ArrayList<WaveformSegment>()
    var start = 0
    for (index in 1 until data.size) {
        if (data[index].segmentId != data[index - 1].segmentId) {
            segments.add(WaveformSegment(start, index))
            start = index
        }
    }
    segments.add(WaveformSegment(start, data.size))

    val minimumPoints = segments.sumOf { min(2, it.size) }
    val interiorPoints = data.size - minimumPoints
    val available = (targetPoints.toLong() - minimumPoints)
        .coerceIn(0L, interiorPoints.toLong())
    val budgets = IntArray(segments.size) { min(2, segments[it].size) }

    if (available > 0 && interiorPoints > 0) {
        val remainders = LongArray(segments.size)
        var distributed = 0
        for (index in segments.indices) {
            val capacity = segments[index].size - budgets[index]
            val weighted = capacity.toLong() * available
            val extra = (weighted / interiorPoints).toInt()
            budgets[index] += extra
            distributed += extra
            remainders[index] = weighted % interiorPoints
        }
        // Largest remainders fill rounding gaps without starving a short
        // segment or giving an already complete segment an extra slot.
        val remainderOrder = segments.indices.sortedByDescending { remainders[it] }
        var remaining = available.toInt() - distributed
        for (index in remainderOrder) {
            if (remaining == 0) break
            if (budgets[index] < segments[index].size) {
                budgets[index]++
                remaining--
            }
        }
    }

    val result = ArrayList<WaveformPoint>(minimumPoints + available.toInt())
    for (index in segments.indices) {
        val segment = segments[index]
        appendSegment(data.subList(segment.start, segment.end), budgets[index], result)
    }
    return result
}

private data class WaveformSegment(val start: Int, val end: Int) {
    val size: Int get() = end - start
}

/** Dual-channel LTTB, with disjoint internal buckets and explicit endpoints. */
private fun appendSegment(
    data: List<WaveformPoint>,
    targetPoints: Int,
    result: MutableList<WaveformPoint>
) {
    if (data.size <= targetPoints) {
        result.addAll(data)
        return
    }

    result.add(data.first())
    if (targetPoints <= 2) {
        if (data.size > 1) result.add(data.last())
        return
    }

    var voltageMin = Float.MAX_VALUE
    var voltageMax = -Float.MAX_VALUE
    var currentMin = Float.MAX_VALUE
    var currentMax = -Float.MAX_VALUE
    for (point in data) {
        voltageMin = min(voltageMin, point.voltage)
        voltageMax = max(voltageMax, point.voltage)
        currentMin = min(currentMin, point.currentUa)
        currentMax = max(currentMax, point.currentUa)
    }
    val voltageSpan = max(1e-6f, voltageMax - voltageMin)
    val currentSpan = max(1e-6f, currentMax - currentMin)
    val bucketSize = (data.size - 2).toDouble() / (targetPoints - 2)

    var previousIndex = 0
    for (bucket in 0 until targetPoints - 2) {
        // Bucket zero starts at sample one. The final sample is reserved as
        // an endpoint and can never be selected again from a candidate bucket.
        val rangeStart = (bucket * bucketSize).toInt() + 1
        val rangeEnd = min(((bucket + 1) * bucketSize).toInt() + 1, data.lastIndex)
        val nextStart = ((bucket + 1) * bucketSize).toInt() + 1
        val nextEnd = min(((bucket + 2) * bucketSize).toInt() + 1, data.size)

        var averageTime = 0.0
        var averageVoltage = 0.0
        var averageCurrent = 0.0
        val count = nextEnd - nextStart
        if (count > 0) {
            for (index in nextStart until nextEnd) {
                val point = data[index]
                averageTime += point.timeMs
                averageVoltage += point.voltage
                averageCurrent += point.currentUa
            }
            averageTime /= count
            averageVoltage /= count
            averageCurrent /= count
        } else {
            val last = data.last()
            averageTime = last.timeMs.toDouble()
            averageVoltage = last.voltage.toDouble()
            averageCurrent = last.currentUa.toDouble()
        }

        val previous = data[previousIndex]
        var selectedIndex = rangeStart
        var largestArea = -1.0
        for (index in rangeStart until rangeEnd) {
            val point = data[index]
            val areaVoltage = abs(
                (previous.timeMs - averageTime) * (point.voltage - previous.voltage) -
                    (point.timeMs - previous.timeMs) * (averageVoltage - previous.voltage)
            ) / voltageSpan
            val areaCurrent = abs(
                (previous.timeMs - averageTime) * (point.currentUa - previous.currentUa) -
                    (point.timeMs - previous.timeMs) * (averageCurrent - previous.currentUa)
            ) / currentSpan
            val area = areaVoltage + areaCurrent
            if (area > largestArea) {
                largestArea = area
                selectedIndex = index
            }
        }
        result.add(data[selectedIndex])
        previousIndex = selectedIndex
    }
    result.add(data.last())
}
