package com.irregular.xenopowermeter.viewmodel

/**
 * Detect continuity at the original packet cadence, before downsampling.
 * Firmware normally emits one packet per 1 ms; allow 10 ms for task jitter,
 * commands and range switching. A longer device-time gap contains no samples
 * and must be a break in the displayed trace, irrespective of signal amplitude.
 *
 * USB can retain an old IN packet until the app opens the port. If that first
 * packet is isolated from the next one, replace only that startup preview and
 * start the displayed clock at the fresh packet. Raw recording is unchanged.
 */
internal class WaveformTimeline {
    data class Position(val timeMs: Long, val segmentId: Long, val resetHistory: Boolean)

    private var firstUs = -1L
    private var lastUs = -1L
    private var segmentId = 0L
    private var awaitingSecondPacket = true
    private var usingDeviceTime: Boolean? = null

    fun acceptPacket(deviceTimestampUs: Long, monotonicTimeUs: Long): Position {
        val deviceTime = deviceTimestampUs > 0L
        val timeUs = if (deviceTime) deviceTimestampUs else monotonicTimeUs
        val clockChanged = usingDeviceTime != null && usingDeviceTime != deviceTime
        val first = firstUs < 0L
        val backwards = !first && timeUs < lastUs
        val gap = !first && timeUs - lastUs > MAX_PACKET_GAP_US
        val staleStartup = awaitingSecondPacket && gap
        val reset = first || clockChanged || backwards || staleStartup

        if (reset) {
            if (!first) segmentId++
            firstUs = timeUs
            awaitingSecondPacket = true
        } else {
            if (gap) segmentId++
            awaitingSecondPacket = false
        }
        lastUs = timeUs
        usingDeviceTime = deviceTime
        return Position((timeUs - firstUs) / 1000L, segmentId, reset)
    }

    fun reset() {
        firstUs = -1L
        lastUs = -1L
        segmentId = 0L
        awaitingSecondPacket = true
        usingDeviceTime = null
    }

    private companion object {
        const val MAX_PACKET_GAP_US = 10_000L
    }
}
