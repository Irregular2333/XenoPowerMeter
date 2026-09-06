package com.irregular.xenopowermeter.data.usb

import com.irregular.xenopowermeter.data.converter.DataConverter
import com.irregular.xenopowermeter.data.model.Calibration
import com.irregular.xenopowermeter.data.model.UsbAdcPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProtocolParser {

    private val receiveBuffer = mutableListOf<Byte>()

    private var _calibration = Calibration.DEFAULT
    val calibration: Calibration get() = _calibration

    data class ParsedSample(
        val voltage: Float,
        val current: Float,
        val range: Int,
        val timestamp: Long
    )

    private val _latestSamples = MutableStateFlow<List<ParsedSample>>(emptyList())
    val latestSamples: StateFlow<List<ParsedSample>> = _latestSamples.asStateFlow()

    fun feedRawData(data: ByteArray) {
        receiveBuffer.addAll(data.toList())
        processBuffer()
    }

    private fun processBuffer() {
        while (receiveBuffer.size >= UsbAdcPacket.PACKET_SIZE) {
            val idx = findHeader()
            if (idx < 0) {
                receiveBuffer.clear()
                return
            }
            if (idx > 0) {
                repeat(idx) { receiveBuffer.removeAt(0) }
            }
            if (receiveBuffer.size < UsbAdcPacket.PACKET_SIZE) return

            val packetData = ByteArray(UsbAdcPacket.PACKET_SIZE)
            for (i in 0 until UsbAdcPacket.PACKET_SIZE) {
                packetData[i] = receiveBuffer[i]
            }

            for (i in 0 until UsbAdcPacket.PACKET_SIZE) {
                receiveBuffer.removeAt(0)
            }

            val packet = UsbAdcPacket.parse(packetData) ?: continue
            processPacket(packet)
        }
    }

    private fun findHeader(): Int {
        for (i in 0 until receiveBuffer.size - 1) {
            if (receiveBuffer[i] == UsbAdcPacket.HEADER_0 &&
                receiveBuffer[i + 1] == UsbAdcPacket.HEADER_1) {
                return i
            }
        }
        return -1
    }

    private fun processPacket(packet: UsbAdcPacket) {
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
