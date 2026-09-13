package com.irregular.xenopowermeter.data.usb

import com.irregular.xenopowermeter.data.converter.DataConverter
import com.irregular.xenopowermeter.data.model.Calibration
import com.irregular.xenopowermeter.data.model.UsbAdcPacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class ProtocolParser {

    // Written from the calibration-loading coroutine, read from the USB read
    // thread — volatile so a freshly loaded calibration is always visible.
    @Volatile
    private var _calibration = Calibration.DEFAULT

    data class ParsedSample(
        val voltage: Float,
        val current: Float,
        val range: Int,
        val timestamp: Long
    )

    // Unbounded: a conflated flow would silently drop sample batches whenever
    // the collector (which also writes recordings to disk) briefly falls
    // behind, making recordings lossy. trySend on an unbounded channel never
    // blocks the USB read thread.
    private val _sampleChannel = Channel<List<ParsedSample>>(Channel.UNLIMITED)
    val sampleFlow: Flow<List<ParsedSample>> = _sampleChannel.receiveAsFlow()

    fun feedPacket(packet: UsbAdcPacket) {
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
                timestamp = packet.timestamp
            )
        }
        _sampleChannel.trySend(parsed)
    }

    fun updateCalibration(cal: Calibration) {
        _calibration = cal
    }
}
