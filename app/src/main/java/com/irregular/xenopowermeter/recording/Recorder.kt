package com.irregular.xenopowermeter.recording

import com.irregular.xenopowermeter.data.model.RecordEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Recorder {

    private val entries = mutableListOf<RecordEntry>()
    private var startTimeNs: Long = 0L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    private val _duration = MutableStateFlow(0.0)
    val duration: StateFlow<Double> = _duration.asStateFlow()

    fun start() {
        entries.clear()
        startTimeNs = System.nanoTime()
        _isRecording.value = true
        _entryCount.value = 0
        _duration.value = 0.0
    }

    fun stop() {
        _isRecording.value = false
    }

    fun addSample(voltage: Float, current: Float) {
        if (!_isRecording.value) return
        val elapsed = (System.nanoTime() - startTimeNs) / 1_000_000_000.0
        entries.add(RecordEntry(elapsed, voltage, current))
        _entryCount.value = entries.size
        _duration.value = elapsed
    }

    fun exportToBin(outputStream: OutputStream) {
        val dos = DataOutputStream(outputStream)
        for (entry in entries) {
            val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            buf.putDouble(entry.timestamp)
            buf.putFloat(entry.voltage)
            buf.putFloat(entry.current)
            dos.write(buf.array())
        }
        dos.flush()
    }

    fun exportToCsv(outputStream: OutputStream) {
        val writer = outputStream.bufferedWriter()
        writer.write("timestamp_s,voltage_V,current_uA\n")
        for (entry in entries) {
            writer.write("${String.format(java.util.Locale.US, "%.6f", entry.timestamp)}," +
                    "${String.format(java.util.Locale.US, "%.6f", entry.voltage)}," +
                    "${String.format(java.util.Locale.US, "%.4f", entry.current)}\n")
        }
        writer.flush()
    }

    fun getEntryCount(): Int = entries.size

    fun getDuration(): Double {
        return if (entries.isEmpty()) 0.0 else entries.last().timestamp
    }
}
