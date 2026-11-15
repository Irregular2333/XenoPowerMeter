package com.irregular.xenopowermeter.viewmodel

import com.irregular.xenopowermeter.data.model.UsbAdcPacket
import com.irregular.xenopowermeter.data.usb.ProtocolParser
import com.irregular.xenopowermeter.recording.Recorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class WaveformStartupGateTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun contiguousStartupPacketsDoNotEndWarmupAfterTheSecondPacket() {
        val gate = WaveformStartupGate()
        repeat(100) { packet ->
            assertFalse("startup packet $packet", gate.acceptPacket(1_000_000L + packet * 1_000L))
        }
        assertTrue(gate.acceptPacket(1_100_000L))
    }

    @Test fun severalBufferedPacketsWithShortGapsStillWaitForTheReceiptTimeBoundary() {
        val gate = WaveformStartupGate()
        for (offset in listOf(0L, 3_000L, 10_000L, 19_000L, 28_000L, 99_999L)) {
            assertFalse(gate.acceptPacket(5_000_000L + offset))
        }
        assertTrue(gate.acceptPacket(5_100_000L))
        assertTrue(gate.acceptPacket(5_101_000L))
    }

    @Test fun lateConsumptionCannotPromotePacketsReceivedBeforeWarmupCompleted() {
        val gate = WaveformStartupGate()
        // All of these could already be queued when the collector resumes
        // after a long GC/disk write. Only the captured arrival times matter.
        val queuedReceiptTimes = listOf(0L, 1_000L, 2_000L, 20_000L, 99_999L)
        queuedReceiptTimes.forEach { assertFalse(gate.acceptPacket(it)) }
        assertTrue(gate.acceptPacket(100_000L))
    }

    @Test fun newConnectionWarmsUpAgain() {
        val gate = WaveformStartupGate()
        assertFalse(gate.acceptPacket(1_000_000L))
        assertTrue(gate.acceptPacket(1_100_000L))
        gate.reset()
        assertFalse(gate.acceptPacket(8_000_000L))
        assertFalse(gate.acceptPacket(8_099_999L))
        assertTrue(gate.acceptPacket(8_100_000L))
    }

    @Test fun clearingTheDisplayTimelineDoesNotRestartConnectionWarmup() {
        val gate = WaveformStartupGate()
        val timeline = WaveformTimeline()
        assertFalse(gate.acceptPacket(0L))
        assertTrue(gate.acceptPacket(100_000L))
        timeline.acceptPacket(2_000_000L, 100_000L)
        timeline.reset()
        assertTrue(gate.acceptPacket(101_000L))
        val position = timeline.acceptPacket(2_001_000L, 101_000L)
        assertEquals(0L, position.timeMs)
    }

    @Test fun oldFirstTimestampDoesNotBecomeTheFreshWaveformOrigin() {
        val gate = WaveformStartupGate()
        val timeline = WaveformTimeline()
        assertFalse(gate.acceptPacket(1_000_000L))
        assertFalse(gate.acceptPacket(1_001_000L))
        assertTrue(gate.acceptPacket(1_100_000L))
        val first = timeline.acceptPacket(9_000_000L, 1_100_000L)
        val next = timeline.acceptPacket(9_001_000L, 1_101_000L)
        assertEquals(0L, first.timeMs)
        assertEquals(1L, next.timeMs)
        assertEquals(first.segmentId, next.segmentId)
    }

    @Test fun startupOnlyAffectsPreviewAndPostStartupSpikesRemainMeasurable() = runBlocking {
        val parser = ProtocolParser()
        val gate = WaveformStartupGate()
        val timeline = WaveformTimeline()
        val statistics = PowerStatistics()
        val points = mutableListOf<WaveformPoint>()
        val recorder = Recorder(durationLimitMinutes = { 1 }, logError = { _, _ -> })
        recorder.start(temporaryFolder.root)
        val samplesPerPacket = 10

        repeat(301) { packet ->
            // The first contiguous startup frames contain initial high-range
            // values. A later genuine pulse must still reach the plot.
            val currentCode = if (packet < 5 || packet == 200) 3_048 else 2_048
            val deviceTime = 1_000_000L + packet * 1_000L
            parser.feedPacket(
                UsbAdcPacket(deviceTime, List(samplesPerPacket) {
                    UsbAdcPacket.Sample(3, 1_000, currentCode, 2_048)
                }), connectionId = 1L, receivedAtUs = 5_000_000L + packet * 1_000L
            )
            val batch = parser.sampleFlow.first()
            // Raw recording is independent of the preview startup policy.
            batch.forEach { recorder.addSample(it.voltage, it.current, it.timestamp) }
            if (gate.acceptPacket(batch.first().receivedAtUs)) {
                val position = timeline.acceptPacket(batch.first().timestamp, batch.first().receivedAtUs)
                if (position.resetHistory) points.clear()
                batch.forEach {
                    points += WaveformPoint(it.voltage, it.current, position.timeMs, position.segmentId)
                    statistics.add(it.voltage, it.current)
                }
            }
        }
        recorder.stop()
        assertEquals(3_010, recorder.entryCount.value)
        assertEquals(2_010, points.size)
        assertEquals(0L, points.first().timeMs)
        assertTrue(points.first().currentUa < 0f)
        assertTrue(points.filter { it.timeMs < 100L }.all { it.currentUa < 0f })
        val display = downsampleWaveform(points, 200)
        assertTrue(display.any { it.timeMs == 100L && it.currentUa > 1_000_000f })
        assertEquals(1, display.map { it.segmentId }.distinct().size)

        val expectedMean = points.sumOf {
            it.voltage.toDouble() * it.currentUa.toDouble() / 1_000_000.0
        } / points.size
        assertEquals(expectedMean.toFloat(), statistics.averageWatts, 0.00001f)
        val exported = ByteArrayOutputStream().also { recorder.exportToBin(it) }
        assertEquals(32 + 3_010 * 16, exported.size())
    }
}
