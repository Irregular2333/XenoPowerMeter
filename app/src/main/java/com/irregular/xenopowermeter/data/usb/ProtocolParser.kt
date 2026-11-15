package com.irregular.xenopowermeter.data.usb

import com.irregular.xenopowermeter.data.converter.DataConverter
import com.irregular.xenopowermeter.data.model.Calibration
import com.irregular.xenopowermeter.data.model.UsbAdcPacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean

class ProtocolParser {

    // Written from the calibration-loading coroutine, read from the USB read
    // thread — volatile so a freshly loaded calibration is always visible.
    @Volatile
    private var _calibration = Calibration.DEFAULT

    data class ParsedSample(
        val voltage: Float,
        val current: Float,
        val range: Int,
        val timestamp: Long,
        val connectionId: Long = 0L,
        val receivedAtUs: Long = 0L
    )

    // Keep the USB reader non-blocking while bounding backlog to roughly a
    // quarter second at the device's normal packet cadence. Overflow is a
    // recording-integrity failure, not a silent drop; the owner disconnects
    // and closes any active recording with an explicit error.
    private val _sampleChannel = Channel<List<ParsedSample>>(SAMPLE_QUEUE_CAPACITY)
    private val sampleQueueOverflowed = AtomicBoolean(false)
    val sampleFlow: Flow<List<ParsedSample>> = _sampleChannel.receiveAsFlow()

    @Volatile
    var onSampleQueueOverflow: (() -> Unit)? = null

    fun feedPacket(
        packet: UsbAdcPacket,
        connectionId: Long = 0L,
        receivedAtUs: Long = System.nanoTime() / 1000L
    ) {
        if (sampleQueueOverflowed.get()) return
        val parsed = packet.samples.map { sample ->
            val rawVoltage = DataConverter.convertVoltage(sample.volAdc)
            val rawCurrent = DataConverter.convertCurrent(
                sample.curAdc, sample.refAdc, sample.range, _calibration
            )
            val voltageMv = rawVoltage * 1000f
            val correctedCurrent = DataConverter.correctCurrent(rawCurrent, voltageMv)

            ParsedSample(
                voltage = rawVoltage,
                current = correctedCurrent,
                range = sample.range,
                timestamp = packet.timestamp,
                connectionId = connectionId,
                receivedAtUs = receivedAtUs
            )
        }
        if (parsed.isEmpty()) return
        if (_sampleChannel.trySend(parsed).isFailure && sampleQueueOverflowed.compareAndSet(false, true)) {
            onSampleQueueOverflow?.invoke()
        }
    }

    /** Starts a fresh stream after the previous reader has been stopped. */
    fun resetConnectionQueue() {
        while (_sampleChannel.tryReceive().isSuccess) { }
        sampleQueueOverflowed.set(false)
    }

    fun updateCalibration(cal: Calibration) {
        _calibration = cal
    }

    private companion object {
        const val SAMPLE_QUEUE_CAPACITY = 256
    }
}
