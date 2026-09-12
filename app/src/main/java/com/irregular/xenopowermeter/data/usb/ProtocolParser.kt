package com.irregular.xenopowermeter.data.usb

import com.irregular.xenopowermeter.data.converter.DataConverter
import com.irregular.xenopowermeter.data.model.Calibration
import com.irregular.xenopowermeter.data.model.UsbAdcPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProtocolParser {

    private var _calibration = Calibration.DEFAULT

    data class ParsedSample(
        val voltage: Float,
        val current: Float,
        val range: Int,
        val timestamp: Long
    )

    private val _latestSamples = MutableStateFlow<List<ParsedSample>>(emptyList())
    val latestSamples: StateFlow<List<ParsedSample>> = _latestSamples.asStateFlow()

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
        _latestSamples.value = parsed
    }

    fun updateCalibration(cal: Calibration) {
        _calibration = cal
    }
}
