package com.irregular.xenopowermeter.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformTimelineTest {
    @Test fun delayedManualConnectionReplacesIsolatedUsbStartupPacket() {
        val timeline = WaveformTimeline()
        timeline.acceptPacket(100_000L, 1_000_000L)
        val fresh = timeline.acceptPacket(2_000_000L, 1_000_100L)
        val next = timeline.acceptPacket(2_001_000L, 1_001_100L)

        assertTrue(fresh.resetHistory)
        assertEquals(0L, fresh.timeMs)
        assertEquals(1L, next.timeMs)
        assertEquals(fresh.segmentId, next.segmentId)
        assertFalse(next.resetHistory)
    }

    @Test fun automaticConnectionAlsoRejectsShortIsolatedStartupGap() {
        val timeline = WaveformTimeline()
        timeline.acceptPacket(100_000L, 1_000_000L)
        val fresh = timeline.acceptPacket(150_000L, 1_000_100L)
        assertTrue(fresh.resetHistory)
        assertEquals(0L, fresh.timeMs)
    }

    @Test fun normalStartupKeepsTheFirstRealPacketAndDoesNotInventAnOriginSample() {
        val timeline = WaveformTimeline()
        val first = timeline.acceptPacket(9_000_000L, 100_000L)
        val second = timeline.acceptPacket(9_001_000L, 800_000L)
        assertTrue(first.resetHistory)
        assertEquals(0L, first.timeMs)
        assertEquals(1L, second.timeMs)
        assertFalse(second.resetHistory)
        assertEquals(first.segmentId, second.segmentId)
    }

    @Test fun laterMissingPacketsPreserveElapsedTimeButStartANewSegment() {
        val timeline = WaveformTimeline()
        timeline.acceptPacket(100_000L, 0L)
        val before = timeline.acceptPacket(101_000L, 0L)
        val after = timeline.acceptPacket(2_000_000L, 0L)
        assertFalse(after.resetHistory)
        assertEquals(1900L, after.timeMs)
        assertTrue(before.segmentId != after.segmentId)
        assertEquals(after.segmentId, timeline.acceptPacket(2_001_000L, 0L).segmentId)
    }

    @Test fun normalRangeSwitchGapsRemainContinuous() {
        val timeline = WaveformTimeline()
        val first = timeline.acceptPacket(1_000L, 0L)
        val second = timeline.acceptPacket(4_000L, 0L)
        val third = timeline.acceptPacket(5_000L, 0L)
        assertFalse(second.resetHistory)
        assertEquals(first.segmentId, second.segmentId)
        assertEquals(first.segmentId, third.segmentId)
    }

    @Test fun deviceRestartAndReconnectStartFreshTimelines() {
        val timeline = WaveformTimeline()
        timeline.acceptPacket(9_000_000L, 0L)
        timeline.acceptPacket(9_001_000L, 0L)
        val reboot = timeline.acceptPacket(1_000L, 0L)
        assertTrue(reboot.resetHistory)
        assertEquals(0L, reboot.timeMs)

        timeline.reset()
        val reconnect = timeline.acceptPacket(30_000_000L, 0L)
        assertTrue(reconnect.resetHistory)
        assertEquals(0L, reconnect.timeMs)
        assertFalse(timeline.acceptPacket(30_001_000L, 0L).resetHistory)
    }

    @Test fun missingDeviceTimestampsUseMonotonicTimeAndClockChangesRebase() {
        val timeline = WaveformTimeline()
        timeline.acceptPacket(0L, 1_000_000L)
        val next = timeline.acceptPacket(0L, 1_001_000L)
        assertFalse(next.resetHistory)
        assertEquals(1L, next.timeMs)
        val deviceClock = timeline.acceptPacket(50_000_000L, 1_002_000L)
        assertTrue(deviceClock.resetHistory)
        assertEquals(0L, deviceClock.timeMs)
    }

    @Test fun packetContinuitySurvivesTheWholeDownsamplingPipeline() {
        val timeline = WaveformTimeline()
        val points = mutableListOf<WaveformPoint>()
        fun packet(timestamp: Long, voltage: Float) {
            val position = timeline.acceptPacket(timestamp, 0L)
            if (position.resetHistory) points.clear()
            repeat(10) {
                points += WaveformPoint(voltage, it.toFloat(), position.timeMs, position.segmentId)
            }
        }

        packet(1_000L, 20f) // The stale, initially high-range USB frame.
        repeat(500) { packet(1_901_000L + it * 1000L, 5f) }
        repeat(500) { packet(3_000_000L + it * 1000L, 6f) }
        val display = downsampleWaveform(points, 200)

        assertEquals(0L, display.first().timeMs)
        assertTrue(display.none { it.voltage == 20f })
        val segments = display.groupBy { it.segmentId }.values.toList()
        assertEquals(2, segments.size)
        assertEquals(499L, segments[0].last().timeMs)
        assertEquals(1099L, segments[1].first().timeMs)
        assertEquals(1598L, segments[1].last().timeMs)
    }
}
