package com.irregular.xenopowermeter.data.usb

import com.irregular.xenopowermeter.data.model.UsbAdcPacket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolParserTest {
    @Test fun boundedQueueReportsOverflowOnceAndCanStartAFreshConnection() = runBlocking {
        val parser = ProtocolParser()
        val overflowCount = AtomicInteger()
        parser.onSampleQueueOverflow = { overflowCount.incrementAndGet() }
        val samples = listOf(UsbAdcPacket.Sample(1, 1, 2048, 2048))

        repeat(257) { parser.feedPacket(UsbAdcPacket(it.toLong(), samples), connectionId = 1L) }

        assertEquals(1, overflowCount.get())
        repeat(256) { withTimeout(1_000L) { parser.sampleFlow.first() } }

        parser.resetConnectionQueue()
        parser.feedPacket(UsbAdcPacket(999L, samples), connectionId = 2L)
        assertEquals(2L, withTimeout(1_000L) { parser.sampleFlow.first().single().connectionId })
    }

    @Test fun queuedPacketsRetainTheirOriginalConnectionAndDeviceTimestamp() = runBlocking {
        val parser = ProtocolParser()
        val samples = List(10) { UsbAdcPacket.Sample(1, it, 2048 + it, 2048) }
        parser.feedPacket(UsbAdcPacket(100_000L, samples), connectionId = 5L, receivedAtUs = 9_000_000L)
        parser.feedPacket(UsbAdcPacket(5_000_000L, samples), connectionId = 7L, receivedAtUs = 9_001_000L)

        val old = withTimeout(1_000L) { parser.sampleFlow.first() }
        val current = withTimeout(1_000L) { parser.sampleFlow.first() }
        assertEquals(10, old.size)
        assertEquals(10, current.size)
        assertEquals(setOf(5L), old.map { it.connectionId }.toSet())
        assertEquals(setOf(7L), current.map { it.connectionId }.toSet())
        // Display continuity does not interpolate or rewrite recording time.
        assertEquals(setOf(100_000L), old.map { it.timestamp }.toSet())
        assertEquals(setOf(5_000_000L), current.map { it.timestamp }.toSet())
        assertEquals(setOf(9_000_000L), old.map { it.receivedAtUs }.toSet())
        assertEquals(setOf(9_001_000L), current.map { it.receivedAtUs }.toSet())
    }
}
