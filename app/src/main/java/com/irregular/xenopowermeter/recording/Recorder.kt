package com.irregular.xenopowermeter.recording

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
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
class Recorder {

    private var stream: DataOutputStream? = null
    private var file: File? = null
    private var firstDeviceTimestampUs = -1L
    private var fallbackStartNs = 0L
    private var totalEntries = 0
    private var lastElapsed = 0.0

    private val entryBuffer = ByteBuffer.allocate(ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    var startMillis: Long = 0L
        private set
    var endMillis: Long = 0L
        private set

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Set when start or a mid-session write fails so the ViewModel can tell
    // the user the recording didn't happen / died, instead of just flipping
    // the record button state.
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    private val _duration = MutableStateFlow(0.0)
    val duration: StateFlow<Double> = _duration.asStateFlow()

    fun start(recordingsDir: File) {
        _failed.value = false
        closeStream()
        totalEntries = 0
        lastElapsed = 0.0
        firstDeviceTimestampUs = -1L
        fallbackStartNs = 0L
        startMillis = System.currentTimeMillis()
        endMillis = 0L
        _entryCount.value = 0
        _duration.value = 0.0
        try {
            recordingsDir.mkdirs()
            val outFile = File(recordingsDir, "recording_$startMillis.bin")
            val dos = DataOutputStream(BufferedOutputStream(FileOutputStream(outFile), WRITE_BUFFER_BYTES))
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.put(MAGIC)
            header.putShort(FORMAT_VERSION.toShort())
            header.putInt(SAMPLE_RATE_HZ)
            header.putLong(startMillis)
            dos.write(header.array())
            stream = dos
            file = outFile
            _isRecording.value = true
            // Only the newest session stays on disk; the old temp file was
            // in-memory data before, so nothing exportable is lost here.
            recordingsDir.listFiles { f -> f.name.startsWith("recording_") && f != outFile }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "recording start failed", e)
            closeStream()
            file = null
            _isRecording.value = false
            _failed.value = true
        }
    }

    fun stop() {
        endMillis = System.currentTimeMillis()
        closeStream()
        _entryCount.value = totalEntries
        _duration.value = lastElapsed
        _isRecording.value = false
    }

    /**
     * Called once per sample (10 kHz) from the collector coroutine — the only
     * writer. Timestamps come from the firmware's µs uptime counter so USB
     * packet gaps don't stretch the recorded timeline.
     */
    fun addSample(voltage: Float, current: Float, deviceTimestampUs: Long) {
        val dos = stream ?: return
        val elapsed: Double = if (deviceTimestampUs > 0) {
            if (firstDeviceTimestampUs < 0) firstDeviceTimestampUs = deviceTimestampUs
            (deviceTimestampUs - firstDeviceTimestampUs) / 1_000_000.0
        } else {
            if (fallbackStartNs == 0L) fallbackStartNs = System.nanoTime()
            (System.nanoTime() - fallbackStartNs) / 1_000_000_000.0
        }

        try {
            entryBuffer.rewind()
            entryBuffer.putDouble(elapsed)
            entryBuffer.putFloat(voltage)
            entryBuffer.putFloat(current)
            dos.write(entryBuffer.array())
        } catch (e: Exception) {
            Log.e(TAG, "recording write failed, stopping session", e)
            closeStream()
            _isRecording.value = false
            _entryCount.value = totalEntries
            _duration.value = lastElapsed
            _failed.value = true
            return
        }

        totalEntries++
        lastElapsed = elapsed
        if (totalEntries % 1000 == 0) {
            _entryCount.value = totalEntries
            _duration.value = elapsed
        }
    }

    /** Copies the temp file (header included) verbatim. */
    fun exportToBin(outputStream: OutputStream) {
        val f = file ?: return
        f.inputStream().use { input ->
            val buf = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                outputStream.write(buf, 0, n)
            }
            outputStream.flush()
        }
    }

    /** Streams the temp file through a CSV transcode without loading it whole. */
    fun exportToCsv(outputStream: OutputStream) {
        val f = file ?: return
        val writer = outputStream.bufferedWriter()
        writer.write("timestamp_s,voltage_V,current_uA\n")

        f.inputStream().use { input ->
            var skipped = 0
            while (skipped < HEADER_BYTES) {
                val n = input.skip((HEADER_BYTES - skipped).toLong())
                if (n <= 0) break
                skipped += n.toInt()
            }

            val entry = ByteArray(ENTRY_BYTES)
            while (true) {
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
                if (eof) break
                if (off < ENTRY_BYTES) break

                val bb = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
                writer.write(
                    String.format(
                        java.util.Locale.US, "%.6f,%.6f,%.4f\n",
                        bb.double, bb.float, bb.float
                    )
                )
            }
        }
        writer.flush()
    }

    private fun closeStream() {
        try {
            stream?.flush()
            stream?.close()
        } catch (_: Exception) {}
        stream = null
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
