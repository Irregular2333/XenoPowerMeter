package com.irregular.xenopowermeter.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class WaveformDownsamplerTest {
    @Test
    fun continuousFiveSecondWindowRetainsOneSegmentAndTwoHundredPoints() {
        val data = List(50_001) { index ->
            WaveformPoint(5f + sin(index / 100.0).toFloat(), index.toFloat(), index / 10L, 7L)
        }

        val result = downsampleWaveform(data, 200)

        assertEquals(200, result.size)
        assertEquals(setOf(7L), result.map { it.segmentId }.toSet())
        assertSame(data.first(), result.first())
        assertSame(data.last(), result.last())
        assertTrue(result.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
    }

    @Test
    fun twoSecondGapPreservesBothSegmentEndpoints() {
        assertGapBoundaries(startOfSecondSegmentMs = 3_000L)
    }

    @Test
    fun shortFiftyMillisecondGapSurvivesDownsampling() {
        assertGapBoundaries(startOfSecondSegmentMs = 1_050L)
    }

    private fun assertGapBoundaries(startOfSecondSegmentMs: Long) {
        val before = List(10_000) { index ->
            WaveformPoint(1f, index.toFloat(), index / 10L, 11L)
        }
        val after = List(10_000) { index ->
            WaveformPoint(5f, -index.toFloat(), startOfSecondSegmentMs + index / 10L, 12L)
        }

        val result = downsampleWaveform(before + after, 200)
        val firstSegment = result.filter { it.segmentId == 11L }
        val secondSegment = result.filter { it.segmentId == 12L }

        assertEquals(200, result.size)
        assertSame(before.first(), firstSegment.first())
        assertSame(before.last(), firstSegment.last())
        assertSame(after.first(), secondSegment.first())
        assertSame(after.last(), secondSegment.last())
        val boundary = result.zipWithNext().single { (a, b) -> a.segmentId != b.segmentId }
        assertSame(before.last(), boundary.first)
        assertSame(after.first(), boundary.second)
    }

    @Test
    fun segmentEndpointsTakePrecedenceOverBudgetIncludingSingletonSegments() {
        val data = (1L..8L).flatMap { segment ->
            val size = if (segment % 2L == 0L) 1 else 5
            List(size) { index -> WaveformPoint(index.toFloat(), 0f, segment * 100 + index, segment) }
        }

        val result = downsampleWaveform(data, 4)

        assertEquals(12, result.size)
        for (segment in 1L..8L) {
            val original = data.filter { it.segmentId == segment }
            val selected = result.filter { it.segmentId == segment }
            assertSame(original.first(), selected.first())
            assertSame(original.last(), selected.last())
        }
        assertEquals((1L..8L).toList(), result.map { it.segmentId }.distinct())
    }

    @Test
    fun remainingBudgetIsSharedWithoutAddingDuplicatesToShortSegments() {
        val singleton = WaveformPoint(1f, 0f, 0L, 1L)
        val pair = listOf(WaveformPoint(2f, 0f, 100L, 2L), WaveformPoint(3f, 0f, 101L, 2L))
        val longSegment = List(1_000) { index -> WaveformPoint(index.toFloat(), 0f, 200L + index, 3L) }

        val result = downsampleWaveform(listOf(singleton) + pair + longSegment, 200)

        assertEquals(200, result.size)
        assertEquals(1, result.count { it.segmentId == 1L })
        assertEquals(2, result.count { it.segmentId == 2L })
        assertEquals(197, result.count { it.segmentId == 3L })
    }

    @Test
    fun loadStepDoesNotCreateANewSegment() {
        val data = List(2_000) { index ->
            WaveformPoint(if (index < 1_000) 0f else 5f, 0f, index.toLong(), 9L)
        }

        val result = downsampleWaveform(data, 50)

        assertEquals(setOf(9L), result.map { it.segmentId }.toSet())
        assertTrue(result.any { it.voltage == 0f })
        assertTrue(result.any { it.voltage == 5f })
        assertEquals(50, result.size)
    }

    @Test
    fun spikeInFirstInternalBucketIsEligibleForSelection() {
        val data = List(50_001) { index ->
            WaveformPoint(if (index == 100) 10f else 0f, 0f, index.toLong(), 1L)
        }

        val result = downsampleWaveform(data, 200)

        assertTrue(result.any { it === data[100] })
        assertEquals(200, result.size)
        assertEquals(1, result.count { it === data.last() })
    }

    @Test
    fun candidateBucketsNeverSelectTheLastEndpointTwice() {
        val data = List(1_001) { index -> WaveformPoint(index.toFloat(), 0f, index.toLong(), 1L) }

        val result = downsampleWaveform(data, 200)

        assertEquals(200, result.size)
        assertEquals(200, result.map { it.timeMs }.distinct().size)
        assertSame(data.first(), result.first())
        assertSame(data.last(), result.last())
    }

    @Test
    fun bothChannelsRemainEligibleDespiteDifferentPhysicalUnits() {
        val data = List(2_000) { index ->
            WaveformPoint(if (index == 20) 0.01f else 0f,
                if (index == 1_020) 1_000_000f else 0f, index.toLong(), 1L)
        }

        val result = downsampleWaveform(data, 50)

        assertTrue(result.any { it === data[20] })
        assertTrue(result.any { it === data[1_020] })
    }

    @Test
    fun shortSequenceIsCopiedBeforeCallerReusesScratchStorage() {
        val data = mutableListOf(
            WaveformPoint(1f, 2f, 0L, 1L),
            WaveformPoint(2f, 3f, 1L, 1L),
            WaveformPoint(3f, 4f, 2L, 1L)
        )

        val result = downsampleWaveform(data, 200)

        assertNotSame(data, result)
        assertEquals(data, result)
        data.clear()
        assertEquals(3, result.size)
    }

    @Test
    fun tinyBudgetStillPreservesSingleSegmentEndpoints() {
        val data = List(100) { index -> WaveformPoint(index.toFloat(), 0f, index.toLong(), 1L) }

        val result = downsampleWaveform(data, 1)

        assertEquals(listOf(data.first(), data.last()), result)
    }
}
