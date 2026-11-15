package com.irregular.xenopowermeter.recording

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams samples straight to a temp .bin file instead of holding them in
 * memory: at the firmware's 10 kHz sample rate an in-memory list grows by
 * ~400 KB/s and OOMs within minutes, and a process death would lose everything.
 *
 * File layout: 32-byte header (magic "XPMB", u16 format version, u32 sample
 * rate, s64 start wall-clock millis, 14 reserved bytes) followed by fixed
 * 16-byte little-endian entries (f64 timestamp seconds, f32 voltage V,
 * f32 current uA) — same entry layout as the pre-1.6 exportToBin output.
 */
class Recorder internal constructor(
    // The recorder owns buffering so failed writes can be rolled back to the
    // last acknowledged complete batch. This factory must return an unbuffered stream.
    private val outputFactory: (File) -> OutputStream = { FileOutputStream(it) },
    private val durationLimitMinutes: () -> Int = {
        com.irregular.xenopowermeter.AppSettings.recordingDurationLimitMin
    },
    private val logError: (String, Exception) -> Unit = { message, error ->
        Log.e(TAG, message, error)
    }
) : AutoCloseable {
    enum class FailureStage { START, WRITE, FINALIZE, SAMPLE_OVERFLOW }

    sealed interface RecordingEvent {
        data class Failed(val stage: FailureStage) : RecordingEvent
        data object Completed : RecordingEvent
    }

    @Volatile
    var failureStage: FailureStage? = null
        private set

    private var stream: OutputStream? = null
    private var closed = false
    private var file: File? = null
    private var deviceTimestampOriginUs = -1L
    private var lastDeviceTimestampUs = -1L
    private var fallbackStartNs = 0L
    private var fallbackBaseElapsed = 0.0
    private var usingFallbackClock = false
    private var totalEntries = 0
    private var lastElapsed = 0.0
    private var persistedEntries = 0
    private var persistedElapsed = 0.0

    /** Identifies the recording targeted by an asynchronous UI operation. */
    @Volatile
    var sessionId: Long = 0L
        private set

    @Volatile
    var startMillis: Long = 0L
        private set
    @Volatile
    var endMillis: Long = 0L
        private set

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Set when start or a mid-session write fails so the ViewModel can tell
    // the user the recording didn't happen / died, instead of just flipping
    // the record button state.
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    // One-shot events retain the failure stage even if a new session starts
    // before the UI handles it. StateFlows alone can conflate quick retries.
    private val eventChannel = Channel<RecordingEvent>(Channel.BUFFERED)
    val events: Flow<RecordingEvent> = eventChannel.receiveAsFlow()

    // Set once when the configured duration limit is reached and the session
    // ends by design — kept separate from [failed] so the ViewModel can show
    // "completed" instead of alarming the user with a data-loss message.
    private val _completed = MutableStateFlow(false)
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    private val _duration = MutableStateFlow(0.0)
    val duration: StateFlow<Double> = _duration.asStateFlow()

    // All lifecycle operations and writes share this object's monitor. The
    // reusable batch avoids per-sample I/O/allocation and, unlike an opaque
    // BufferedOutputStream, never retries an uncertain partially written batch.
    private val writeBuffer = ByteBuffer.allocate(WRITE_BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    @Synchronized
    fun start(recordingsDir: File) {
        if (closed || _isRecording.value) return
        sessionId++
        failureStage = null
        _failed.value = false
        _completed.value = false
        totalEntries = 0
        lastElapsed = 0.0
        persistedEntries = 0
        persistedElapsed = 0.0
        writeBuffer.clear()
        file = null
        deviceTimestampOriginUs = -1L
        lastDeviceTimestampUs = -1L
        fallbackStartNs = 0L
        fallbackBaseElapsed = 0.0
        usingFallbackClock = false
        startMillis = System.currentTimeMillis()
        endMillis = 0L
        _entryCount.value = 0
        _duration.value = 0.0
        try {
            if (!recordingsDir.isDirectory && !recordingsDir.mkdirs()) {
                throw IOException("Cannot create recordings directory")
            }
            // A stopped session can be restarted within the same millisecond.
            val outFile = File.createTempFile("recording_${startMillis}_", ".bin", recordingsDir)
            file = outFile
            val output = outputFactory(outFile)
            stream = output
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.put(MAGIC)
            header.putShort(FORMAT_VERSION.toShort())
            header.putInt(SAMPLE_RATE_HZ)
            header.putLong(startMillis)
            output.write(header.array())
            _isRecording.value = true
            // Only the newest session stays on disk; the old temp file was
            // in-memory data before, so nothing exportable is lost here.
            recordingsDir.listFiles { f -> f.name.startsWith("recording_") && f != outFile }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            finish(initialFailure = e, stage = FailureStage.START)
        }
    }

    @Synchronized
    fun stop() {
        if (!_isRecording.value) return
        finish()
    }

    /** Permanent lifecycle teardown; queued start calls cannot reopen this recorder. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (_isRecording.value) finish()
        eventChannel.close()
    }

    /**
     * Called once per sample (10 kHz) from the collector coroutine. The same
     * lock as start/stop prevents a writer from retaining a closed stream.
     * Timestamps come from the firmware's µs uptime counter so USB
     * packet gaps don't stretch the recorded timeline.
     *
     * Automatically stops recording at the configured duration limit.
     */
    @Synchronized
    fun addSample(voltage: Float, current: Float, deviceTimestampUs: Long) {
        if (!_isRecording.value) return
        val elapsed = elapsedTime(deviceTimestampUs)

        // Enforce recording duration limit from settings
        val limitMs = durationLimitMinutes() * 60L * 1000
        if (elapsed * 1000.0 > limitMs) {
            finish(autoCompleted = true)
            return
        }

        try {
            writeBuffer.putDouble(elapsed)
            writeBuffer.putFloat(voltage)
            writeBuffer.putFloat(current)
            totalEntries++
            lastElapsed = elapsed
            if (!writeBuffer.hasRemaining()) flushEntries()
        } catch (e: Exception) {
            finish(initialFailure = e, stage = FailureStage.WRITE)
            return
        }

        if (totalEntries % 1000 == 0) {
            _entryCount.value = totalEntries
            _duration.value = elapsed
        }
    }

    /** Ends a session explicitly when bounded sample delivery loses integrity. */
    @Synchronized
    fun failDueToSampleOverflow() {
        if (!_isRecording.value) return
        finish(
            initialFailure = IOException("Sample queue overflow; recording is incomplete"),
            stage = FailureStage.SAMPLE_OVERFLOW,
            flushAcceptedEntries = true
        )
    }

    private fun elapsedTime(deviceTimestampUs: Long): Double {
        if (!usingFallbackClock && deviceTimestampUs > 0L) {
            if (deviceTimestampOriginUs < 0L) {
                deviceTimestampOriginUs = deviceTimestampUs
            } else if (deviceTimestampUs < lastDeviceTimestampUs) {
                startFallbackClock()
            }
            if (!usingFallbackClock) {
                lastDeviceTimestampUs = deviceTimestampUs
                return fallbackBaseElapsed +
                    (deviceTimestampUs - deviceTimestampOriginUs) / 1_000_000.0
            }
        } else if (!usingFallbackClock) {
            startFallbackClock()
        }
        return fallbackBaseElapsed + (System.nanoTime() - fallbackStartNs) / 1_000_000_000.0
    }

    private fun startFallbackClock() {
        usingFallbackClock = true
        fallbackBaseElapsed = lastElapsed
        fallbackStartNs = System.nanoTime()
    }

    /** Copies the temp file (header included) verbatim. */
    fun exportToBin(outputStream: OutputStream) {
        val snapshot = openExport()
        snapshot.input.use { input ->
            val buf = ByteArray(COPY_BUFFER_BYTES)
            var remaining = HEADER_BYTES + snapshot.entryCount.toLong() * ENTRY_BYTES
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                if (n < 0) throw IOException("Recording was truncated during export")
                outputStream.write(buf, 0, n)
                remaining -= n
            }
            outputStream.flush()
        }
    }

    /** Streams the temp file through a CSV transcode without loading it whole. */
    fun exportToCsv(outputStream: OutputStream) {
        val snapshot = openExport()
        snapshot.input.use { input ->
            val writer = outputStream.bufferedWriter()
            writer.write("timestamp_s,voltage_V,current_uA\n")
            var skipped = 0
            while (skipped < HEADER_BYTES) {
                val n = input.skip((HEADER_BYTES - skipped).toLong())
                if (n <= 0) break
                skipped += n.toInt()
            }

            val entry = ByteArray(ENTRY_BYTES)
            repeat(snapshot.entryCount) {
                var off = 0
                var eof = false
                while (off < ENTRY_BYTES) {
                    val n = input.read(entry, off, ENTRY_BYTES - off)
                    if (n < 0) {
                        eof = true
                        break
                    }
                    off += n
                }
                if (eof || off < ENTRY_BYTES) throw IOException("Recording contains a truncated entry")

                val bb = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
                writer.write(
                    String.format(
                        java.util.Locale.US, "%.6f,%.6f,%.4f\n",
                        bb.double, bb.float, bb.float
                    )
                )
            }
            writer.flush()
        }
    }

    /** Reject a delayed clear if a newer recording has started in the meantime. */
    @Synchronized
    fun clearRecordingIfStopped(expectedSessionId: Long): Boolean {
        if (_isRecording.value || sessionId != expectedSessionId) return false
        clearRecording()
        return true
    }

    /** Deletes the temp recording file and resets all counters. */
    @Synchronized
    fun clearRecording() {
        if (_isRecording.value) finish()
        file?.delete()
        file = null
        totalEntries = 0
        lastElapsed = 0.0
        persistedEntries = 0
        persistedElapsed = 0.0
        writeBuffer.clear()
        deviceTimestampOriginUs = -1L
        lastDeviceTimestampUs = -1L
        fallbackStartNs = 0L
        fallbackBaseElapsed = 0.0
        usingFallbackClock = false
        startMillis = 0L
        endMillis = 0L
        _entryCount.value = 0
        _duration.value = 0.0
        _isRecording.value = false
        failureStage = null
        _failed.value = false
        _completed.value = false
    }

    private fun flushEntries() {
        val bytes = writeBuffer.position()
        if (bytes == 0) return
        checkNotNull(stream).write(writeBuffer.array(), 0, bytes)
        persistedEntries += bytes / ENTRY_BYTES
        persistedElapsed = lastElapsed
        writeBuffer.clear()
    }

    /** Must be called with the recorder monitor held. */
    private fun finish(
        autoCompleted: Boolean = false,
        initialFailure: Exception? = null,
        stage: FailureStage = FailureStage.FINALIZE,
        flushAcceptedEntries: Boolean = false
    ) {
        var failure = initialFailure
        val output = stream
        if (failure == null || flushAcceptedEntries) {
            try {
                flushEntries()
                output?.flush()
            } catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        // Detach before closing. A failed batch is never submitted a second time.
        stream = null
        try {
            output?.close()
        } catch (e: Exception) {
            if (failure == null) failure = e else failure.addSuppressed(e)
        }

        if (failure != null) {
            restorePersistedPrefix(failure)
            logError("recording failed; retained complete records only", failure)
        }
        writeBuffer.clear()
        endMillis = System.currentTimeMillis()
        _entryCount.value = totalEntries
        _duration.value = lastElapsed
        failureStage = if (failure != null) stage else null
        _failed.value = failure != null
        _completed.value = autoCompleted && failure == null
        _isRecording.value = false
        if (failure != null) {
            eventChannel.trySend(RecordingEvent.Failed(stage))
        } else if (autoCompleted) {
            eventChannel.trySend(RecordingEvent.Completed)
        }
    }

    private fun restorePersistedPrefix(failure: Exception) {
        totalEntries = persistedEntries
        lastElapsed = persistedElapsed
        val f = file ?: return
        try {
            // Discard an uncertain tail from a write that transferred some
            // bytes and then threw. Only acknowledged batches are exportable.
            if (f.length() < HEADER_BYTES) {
                f.delete()
                file = null
                totalEntries = 0
                lastElapsed = 0.0
            } else {
                RandomAccessFile(f, "rw").use {
                    totalEntries = minOf(persistedEntries.toLong(), (it.length() - HEADER_BYTES) / ENTRY_BYTES).toInt()
                    it.setLength(HEADER_BYTES + totalEntries.toLong() * ENTRY_BYTES)
                    if (totalEntries == 0) {
                        lastElapsed = 0.0
                    } else if (totalEntries != persistedEntries) {
                        val timestamp = ByteArray(java.lang.Double.BYTES)
                        it.seek(HEADER_BYTES + (totalEntries - 1L) * ENTRY_BYTES)
                        it.readFully(timestamp)
                        lastElapsed = ByteBuffer.wrap(timestamp).order(ByteOrder.LITTLE_ENDIAN).double
                    }
                }
            }
        } catch (e: Exception) {
            failure.addSuppressed(e)
        }
    }

    private data class ExportSnapshot(val input: InputStream, val entryCount: Int)

    // Open the old session while protected, then let its independent handle
    // outlive clear/start/close without holding up lifecycle operations during
    // a potentially long CSV conversion or slow document-provider write.
    @Synchronized
    private fun openExport(): ExportSnapshot {
        check(!_isRecording.value) { "Stop the recording before exporting" }
        val f = file?.takeIf { it.isFile } ?: throw IOException("No recording is available")
        if (f.length() < HEADER_BYTES + totalEntries.toLong() * ENTRY_BYTES) {
            throw IOException("Recording contains a truncated entry")
        }
        return ExportSnapshot(f.inputStream(), totalEntries)
    }

    companion object {
        private const val TAG = "Recorder"
        private val MAGIC = byteArrayOf(0x58, 0x50, 0x4D, 0x42) // "XPMB"
        private const val FORMAT_VERSION = 1
        private const val SAMPLE_RATE_HZ = 10_000
        private const val HEADER_BYTES = 32
        private const val ENTRY_BYTES = 16
        private const val WRITE_BUFFER_BYTES = 64 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
