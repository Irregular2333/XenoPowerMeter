package com.irregular.xenopowermeter.recording

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecorderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val recordingsDir: File get() = temporaryFolder.root

    private fun recorder(outputFactory: (File) -> OutputStream = { FileOutputStream(it) }): Recorder =
        Recorder(outputFactory = outputFactory, durationLimitMinutes = { 1 }, logError = { _, _ -> })

    @Test
    fun stoppedRecordingContainsTheHeaderAndEveryAcceptedSample() {
        val recorder = recorder()
        recorder.start(recordingsDir)
        recorder.addSample(5f, 100f, 1_000_000L)
        recorder.addSample(6f, -200f, 1_001_000L)
        recorder.stop()

        val output = ByteArrayOutputStream()
        recorder.exportToBin(output)
        val bytes = output.toByteArray()
        assertEquals(HEADER_BYTES + 2 * ENTRY_BYTES, bytes.size)
        assertArrayEquals(byteArrayOf(0x58, 0x50, 0x4D, 0x42), bytes.copyOfRange(0, 4))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header.position(4)
        assertEquals(1, header.short.toInt())
        assertEquals(10_000, header.int)
        assertEquals(recorder.startMillis, header.long)
        val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        samples.position(HEADER_BYTES)
        assertEquals(0.0, samples.double, 0.0)
        assertEquals(5f, samples.float, 0f)
        assertEquals(100f, samples.float, 0f)
        assertEquals(0.001, samples.double, 0.0)
        assertEquals(6f, samples.float, 0f)
        assertEquals(-200f, samples.float, 0f)
        assertEquals(2, recorder.entryCount.value)
        assertEquals(0.001, recorder.duration.value, 0.0)
        assertFalse(recorder.isRecording.value)
        assertFalse(recorder.failed.value)
    }

    @Test
    fun deviceTimestampResetSwitchesToMonotonicElapsedTime() {
        val recorder = recorder()
        recorder.start(recordingsDir)
        recorder.addSample(1f, 1f, 10_000_000L)
        recorder.addSample(2f, 2f, 11_000_000L)
        recorder.addSample(3f, 3f, 100L)
        Thread.sleep(3)
        recorder.addSample(4f, 4f, 200L)
        recorder.stop()

        val bytes = ByteArrayOutputStream().also { recorder.exportToBin(it) }.toByteArray()
        val entries = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        entries.position(HEADER_BYTES)
        val elapsed = List(4) {
            val timestamp = entries.double
            entries.float
            entries.float
            timestamp
        }

        assertEquals(listOf(0.0, 1.0), elapsed.take(2))
        assertTrue(elapsed[2] >= elapsed[1])
        assertTrue(elapsed[3] > elapsed[2])
        assertEquals(elapsed.last(), recorder.duration.value, 0.0)
    }

    @Test
    fun sampleQueueOverflowFailsRecordingButFlushesAcceptedSamples() = runBlocking {
        val recorder = recorder()
        recorder.start(recordingsDir)
        recorder.addSample(5f, 100f, 1_000_000L)
        recorder.failDueToSampleOverflow()

        assertFalse(recorder.isRecording.value)
        assertTrue(recorder.failed.value)
        assertEquals(Recorder.FailureStage.SAMPLE_OVERFLOW, recorder.failureStage)
        assertEquals(1, recorder.entryCount.value)
        assertEquals(
            Recorder.RecordingEvent.Failed(Recorder.FailureStage.SAMPLE_OVERFLOW),
            withTimeout(1_000L) { recorder.events.first() }
        )
        val bytes = ByteArrayOutputStream().also { recorder.exportToBin(it) }.toByteArray()
        assertEquals(HEADER_BYTES + ENTRY_BYTES, bytes.size)
    }

    @Test
    fun delayedClearCannotDeleteANewerStoppedSession() {
        val recorder = recorder()
        recorder.start(recordingsDir)
        recorder.addSample(5f, 100f, 1_000_000L)
        recorder.stop()
        val oldSession = recorder.sessionId

        recorder.start(recordingsDir)
        recorder.addSample(12f, -200f, 2_000_000L)
        recorder.stop()
        assertFalse(recorder.clearRecordingIfStopped(oldSession))

        val csv = ByteArrayOutputStream().also { recorder.exportToCsv(it) }.toString("UTF-8")
        assertEquals("timestamp_s,voltage_V,current_uA\n0.000000,12.000000,-200.0000\n", csv)
        assertTrue(recorder.clearRecordingIfStopped(recorder.sessionId))
        assertEquals(0, recorder.entryCount.value)
        assertEquals(0, recordingsDir.listFiles()!!.size)
    }

    @Test
    fun asynchronousClearDoesNotInterruptAnActiveRecording() {
        val recorder = recorder()
        recorder.start(recordingsDir)
        recorder.addSample(5f, 100f, 1_000_000L)
        assertFalse(recorder.clearRecordingIfStopped(recorder.sessionId))
        assertTrue(recorder.isRecording.value)
        recorder.stop()
        assertEquals(1, recorder.entryCount.value)
    }

    @Test
    fun repeatedStartDoesNotReplaceAnActiveSession() {
        var openedStreams = 0
        val recorder = recorder { openedStreams++; FileOutputStream(it) }
        recorder.start(recordingsDir)
        val firstStart = recorder.startMillis
        recorder.addSample(5f, 100f, 1_000_000L)
        recorder.start(recordingsDir)
        recorder.addSample(5f, 200f, 2_000_000L)
        recorder.stop()

        assertEquals(1, openedStreams)
        assertEquals(firstStart, recorder.startMillis)
        assertEquals(1, recordingsDir.listFiles()!!.size)
        assertEquals(2, recorder.entryCount.value)
        assertEquals(1.0, recorder.duration.value, 0.0)
    }

    @Test
    fun repeatedStopAndLateSamplesLeaveTheFinishedSessionUnchanged() {
        val recorder = recorder()
        recorder.start(recordingsDir)
        recorder.addSample(5f, 100f, 1_000_000L)
        recorder.stop()
        val firstEnd = recorder.endMillis
        recorder.addSample(5f, 200f, 2_000_000L)
        recorder.stop()

        assertEquals(firstEnd, recorder.endMillis)
        assertEquals(1, recorder.entryCount.value)
        assertEquals((HEADER_BYTES + ENTRY_BYTES).toLong(), recordingsDir.listFiles()!!.single().length())
        assertFalse(recorder.failed.value)
    }

    @Test
    fun durationLimitFlushesTheSessionAndEmitsOneCompletedEvent() = runBlocking {
        val recorder = recorder()
        recorder.start(recordingsDir)
        recorder.addSample(5f, 100f, 1_000_000L)
        recorder.addSample(5f, 200f, 61_000_000L)
        recorder.addSample(5f, 300f, 61_000_001L)
        recorder.stop()

        assertFalse(recorder.isRecording.value)
        assertFalse(recorder.failed.value)
        assertTrue(recorder.completed.value)
        assertEquals(2, recorder.entryCount.value)
        assertEquals(60.0, recorder.duration.value, 0.0)
        assertEquals(Recorder.RecordingEvent.Completed, withTimeout(1_000) { recorder.events.first() })
        assertNull(withTimeoutOrNull(50) { recorder.events.first() })
    }

    @Test
    fun flushFailureAtDurationLimitEmitsFailedInsteadOfCompleted() = runBlocking {
        lateinit var output: ControlledOutput
        val recorder = recorder { ControlledOutput(it).also { created -> output = created } }
        recorder.start(recordingsDir)
        recorder.addSample(5f, 100f, 1_000_000L)
        output.failFlush = true
        recorder.addSample(5f, 200f, 61_000_001L)

        assertFalse(recorder.isRecording.value)
        assertTrue(recorder.failed.value)
        assertFalse(recorder.completed.value)
        assertEquals(Recorder.FailureStage.FINALIZE, recorder.failureStage)
        assertEquals(1, recorder.entryCount.value)
        assertEquals(
            Recorder.RecordingEvent.Failed(Recorder.FailureStage.FINALIZE),
            withTimeout(1_000) { recorder.events.first() }
        )
        assertNull(withTimeoutOrNull(50) { recorder.events.first() })
        val salvaged = ByteArrayOutputStream()
        recorder.exportToBin(salvaged)
        assertEquals(HEADER_BYTES + ENTRY_BYTES, salvaged.size())
    }

    @Test
    fun partiallyWrittenFailedBatchIsNotRetriedOrCounted() {
        lateinit var output: ControlledOutput
        val recorder = recorder { ControlledOutput(it).also { created -> output = created } }
        recorder.start(recordingsDir)
        repeat(BATCH_ENTRIES) { recorder.addSample(5f, it.toFloat(), 1_000_000L + it * 100L) }
        output.partialWriteBytes = 11
        output.failWrites = true
        repeat(BATCH_ENTRIES) { recorder.addSample(5f, -1f, 2_000_000L + it * 100L) }

        assertTrue(recorder.failed.value)
        assertFalse(recorder.completed.value)
        assertFalse(recorder.isRecording.value)
        assertEquals(Recorder.FailureStage.WRITE, recorder.failureStage)
        assertEquals(BATCH_ENTRIES, recorder.entryCount.value)
        assertEquals((BATCH_ENTRIES - 1) / 10_000.0, recorder.duration.value, 0.0)
        assertEquals(1, output.failedWriteCalls)
        assertEquals(
            HEADER_BYTES + BATCH_ENTRIES.toLong() * ENTRY_BYTES,
            recordingsDir.listFiles()!!.single().length()
        )
        val bytes = ByteArrayOutputStream().also { recorder.exportToBin(it) }.toByteArray()
        val last = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        last.position(bytes.size - ENTRY_BYTES)
        assertEquals((BATCH_ENTRIES - 1) / 10_000.0, last.double, 0.0)
        assertEquals(5f, last.float, 0f)
        assertEquals((BATCH_ENTRIES - 1).toFloat(), last.float, 0f)
    }

    @Test
    fun pendingEntriesAreRemovedFromCountWhenFinalWriteFails() {
        lateinit var output: ControlledOutput
        val recorder = recorder { ControlledOutput(it).also { created -> output = created } }
        recorder.start(recordingsDir)
        recorder.addSample(5f, 100f, 1_000_000L)
        output.failWrites = true
        output.partialWriteBytes = 7
        recorder.stop()

        assertTrue(recorder.failed.value)
        assertEquals(Recorder.FailureStage.FINALIZE, recorder.failureStage)
        assertEquals(0, recorder.entryCount.value)
        assertEquals(0.0, recorder.duration.value, 0.0)
        assertEquals(HEADER_BYTES.toLong(), recordingsDir.listFiles()!!.single().length())
        assertEquals(1, output.failedWriteCalls)
    }

    @Test
    fun closeFailureIsReportedAndTheCompletePrefixRemainsExportable() {
        lateinit var output: ControlledOutput
        val recorder = recorder { ControlledOutput(it).also { created -> output = created } }
        recorder.start(recordingsDir)
        recorder.addSample(5f, 100f, 1_000_000L)
        output.failClose = true
        recorder.stop()
        recorder.stop()

        assertTrue(recorder.failed.value)
        assertEquals(Recorder.FailureStage.FINALIZE, recorder.failureStage)
        assertEquals(1, recorder.entryCount.value)
        val csv = ByteArrayOutputStream().also { recorder.exportToCsv(it) }.toString("UTF-8")
        assertEquals("timestamp_s,voltage_V,current_uA\n0.000000,5.000000,100.0000\n", csv)
    }

    @Test
    fun consecutiveFailedStartsRetainTheirOwnFailureEvents() = runBlocking {
        val recorder = recorder { throw IOException("Cannot open output") }
        repeat(3) { recorder.start(recordingsDir) }

        assertTrue(recorder.failed.value)
        assertFalse(recorder.isRecording.value)
        assertEquals(Recorder.FailureStage.START, recorder.failureStage)
        repeat(3) {
            assertEquals(
                Recorder.RecordingEvent.Failed(Recorder.FailureStage.START),
                withTimeout(1_000) { recorder.events.first() }
            )
        }
    }

    @Test
    fun clearDuringRecordingClosesTheSessionAndResetsAllState() {
        val recorder = recorder()
        recorder.start(recordingsDir)
        recorder.addSample(5f, 100f, 1_000_000L)
        recorder.clearRecording()
        recorder.addSample(5f, 200f, 2_000_000L)

        assertFalse(recorder.isRecording.value)
        assertFalse(recorder.failed.value)
        assertFalse(recorder.completed.value)
        assertNull(recorder.failureStage)
        assertEquals(0, recorder.entryCount.value)
        assertEquals(0L, recorder.startMillis)
        assertEquals(0L, recorder.endMillis)
        assertEquals(0, recordingsDir.listFiles()!!.size)
    }

    @Test
    fun closeFlushesOnceAndPreventsQueuedStartsFromReopeningTheRecorder() {
        var openedStreams = 0
        val recorder = recorder { openedStreams++; FileOutputStream(it) }
        recorder.start(recordingsDir)
        recorder.addSample(5f, 100f, 1_000_000L)
        recorder.close()
        val firstEnd = recorder.endMillis
        recorder.close()
        recorder.start(recordingsDir)
        recorder.addSample(5f, 200f, 2_000_000L)

        assertFalse(recorder.isRecording.value)
        assertFalse(recorder.failed.value)
        assertEquals(1, openedStreams)
        assertEquals(firstEnd, recorder.endMillis)
        assertEquals(1, recorder.entryCount.value)
        assertEquals((HEADER_BYTES + ENTRY_BYTES).toLong(), recordingsDir.listFiles()!!.single().length())
    }

    @Test
    fun closeBeforeAnyStartPreventsCreatingARecording() {
        val recorder = recorder()
        recorder.close()
        recorder.start(recordingsDir)

        assertFalse(recorder.isRecording.value)
        assertEquals(0, recordingsDir.listFiles()!!.size)
    }

    @Test(timeout = 10_000)
    fun slowBinExportDoesNotBlockLifecycleAndKeepsTheOriginalSession() {
        assertSlowExportKeepsItsSnapshot(csv = false)
    }

    @Test(timeout = 10_000)
    fun slowCsvExportDoesNotBlockLifecycleAndKeepsTheOriginalSession() {
        assertSlowExportKeepsItsSnapshot(csv = true)
    }

    private fun assertSlowExportKeepsItsSnapshot(csv: Boolean) {
        val recorder = recorder()
        recorder.start(recordingsDir)
        // More than one BIN copy buffer and one CSV writer buffer: the export
        // must keep reading the original handle after its session is cleared.
        repeat(5_000) { recorder.addSample(5f, it.toFloat(), 1_000_000L + it * 100L) }
        recorder.stop()
        val expected = ByteArrayOutputStream().also {
            if (csv) recorder.exportToCsv(it) else recorder.exportToBin(it)
        }.toByteArray()
        val outputEntered = CountDownLatch(1)
        val allowOutput = CountDownLatch(1)
        val actual = ByteArrayOutputStream()
        val slowOutput = object : OutputStream() {
            private var blocked = false

            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (!blocked) {
                    blocked = true
                    outputEntered.countDown()
                    check(allowOutput.await(5, TimeUnit.SECONDS))
                }
                actual.write(bytes, offset, length)
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val exporting = executor.submit {
                if (csv) recorder.exportToCsv(slowOutput) else recorder.exportToBin(slowOutput)
            }
            assertTrue(outputEntered.await(2, TimeUnit.SECONDS))
            val lifecycle = executor.submit {
                recorder.clearRecording()
                recorder.start(recordingsDir)
                recorder.addSample(9f, -100f, 2_000_000L)
                recorder.stop()
                recorder.clearRecording()
                recorder.start(recordingsDir)
                recorder.addSample(12f, -200f, 3_000_000L)
                recorder.close()
            }
            // None of these operations may wait for the slow destination.
            lifecycle.get(2, TimeUnit.SECONDS)
            assertFalse(recorder.isRecording.value)
            assertFalse(recorder.failed.value)
            assertEquals(1, recorder.entryCount.value)
            allowOutput.countDown()
            exporting.get(3, TimeUnit.SECONDS)
            assertArrayEquals(expected, actual.toByteArray())
        } finally {
            allowOutput.countDown()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 10_000)
    fun stopWaitsForAnInFlightWriteAndClosesExactlyOnce() {
        val writeEntered = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        var closeCalls = 0
        val recorder = recorder { file ->
            object : OutputStream() {
                private val delegate = FileOutputStream(file)
                override fun write(value: Int) = delegate.write(value)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (length == BATCH_ENTRIES * ENTRY_BYTES) {
                        writeEntered.countDown()
                        check(allowWrite.await(5, TimeUnit.SECONDS))
                    }
                    delegate.write(bytes, offset, length)
                }
                override fun close() {
                    closeCalls++
                    delegate.close()
                }
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            recorder.start(recordingsDir)
            val writer = executor.submit {
                repeat(BATCH_ENTRIES) { recorder.addSample(5f, 100f, 1_000_000L + it * 100L) }
            }
            assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
            val stopper = executor.submit {
                stopEntered.countDown()
                recorder.stop()
                stopReturned.countDown()
            }
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
            assertFalse(stopReturned.await(50, TimeUnit.MILLISECONDS))
            allowWrite.countDown()
            writer.get(2, TimeUnit.SECONDS)
            stopper.get(2, TimeUnit.SECONDS)

            assertFalse(recorder.isRecording.value)
            assertFalse(recorder.failed.value)
            assertEquals(BATCH_ENTRIES, recorder.entryCount.value)
            assertEquals(1, closeCalls)
            assertEquals(
                HEADER_BYTES + BATCH_ENTRIES.toLong() * ENTRY_BYTES,
                recordingsDir.listFiles()!!.single().length()
            )
        } finally {
            allowWrite.countDown()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private class ControlledOutput(file: File) : OutputStream() {
        private val delegate = FileOutputStream(file)
        var failWrites = false
        var partialWriteBytes = 0
        var failedWriteCalls = 0
        var failFlush = false
        var failClose = false

        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (failWrites) {
                failedWriteCalls++
                delegate.write(bytes, offset, minOf(partialWriteBytes, length))
                throw IOException("Injected partial write failure")
            }
            delegate.write(bytes, offset, length)
        }

        override fun flush() {
            if (failFlush) throw IOException("Injected flush failure")
            delegate.flush()
        }

        override fun close() {
            delegate.close()
            if (failClose) throw IOException("Injected close failure")
        }
    }

    private companion object {
        const val HEADER_BYTES = 32
        const val ENTRY_BYTES = 16
        const val BATCH_ENTRIES = 4096
    }
}
