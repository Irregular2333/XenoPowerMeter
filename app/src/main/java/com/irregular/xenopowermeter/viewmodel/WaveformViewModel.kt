package com.irregular.xenopowermeter.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.irregular.xenopowermeter.data.model.Calibration
import com.irregular.xenopowermeter.data.model.RangeMode
import com.irregular.xenopowermeter.data.usb.ProtocolParser
import com.irregular.xenopowermeter.data.usb.UsbCdcManager
import com.irregular.xenopowermeter.notification.IslandHelper
import com.irregular.xenopowermeter.recording.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class WaveformViewModel(application: Application) : AndroidViewModel(application) {

    val usbManager = UsbCdcManager(application)
    val parser = ProtocolParser()
    val recorder = Recorder()

    init {
        IslandHelper.init(application)
    }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _currentVoltage = MutableStateFlow(0f)
    val currentVoltage: StateFlow<Float> = _currentVoltage.asStateFlow()

    private val _currentCurrent = MutableStateFlow(0f)
    val currentCurrent: StateFlow<Float> = _currentCurrent.asStateFlow()

    private val _currentRange = MutableStateFlow(RangeMode.AUTO)
    val currentRange: StateFlow<RangeMode> = _currentRange.asStateFlow()

    private val _calibration = MutableStateFlow(Calibration.DEFAULT)
    val calibration: StateFlow<Calibration> = _calibration.asStateFlow()

    private val _waveformData = MutableStateFlow<List<Triple<Float, Float, Long>>>(emptyList())
    val waveformData: StateFlow<List<Triple<Float, Float, Long>>> = _waveformData.asStateFlow()

    private val waveBuffer = ArrayDeque<Triple<Float, Float, Long>>(200000)
    private var startTimeMs = 0L
    private var lastUpdateTimeMs = 0L
    private val updateIntervalMs = 250L
    private var lastValuePanelUpdateTimeMs = 0L
    private val valuePanelUpdateIntervalMs = 1000L

    private val _avgVoltage = MutableStateFlow(0f)
    val avgVoltage: StateFlow<Float> = _avgVoltage.asStateFlow()

    private val _avgCurrent = MutableStateFlow(0f)
    val avgCurrent: StateFlow<Float> = _avgCurrent.asStateFlow()

    private val _averagePower = MutableStateFlow(0f)
    val averagePower: StateFlow<Float> = _averagePower.asStateFlow()

    private var sumVoltage = 0.0
    private var sumCurrent = 0.0
    private var sampleCount = 0L
    private var validPowerSum = 0.0
    private var validPowerCount = 0L

    private val _visibleTimeMs = MutableStateFlow(8000L)
    val visibleTimeMs: StateFlow<Long> = _visibleTimeMs.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    fun togglePause() {
        _isPaused.value = !_isPaused.value
    }

    fun updateVisibleTimeMs(ms: Long) {
        _visibleTimeMs.value = ms.coerceIn(1000L, 300000L)
    }

    private fun lttbDownsample(
        data: List<Triple<Float, Float, Long>>,
        targetPoints: Int
    ): List<Triple<Float, Float, Long>> {
        if (data.size <= targetPoints || targetPoints < 3) return data

        val result = mutableListOf<Triple<Float, Float, Long>>()
        result.add(data[0])

        val bucketSize = (data.size - 2).toDouble() / (targetPoints - 2)

        var a = 0
        for (i in 1 until targetPoints - 1) {
            val rangeStart = (i * bucketSize).toInt() + 1
            val rangeEnd = min(((i + 1) * bucketSize).toInt() + 1, data.size)
            val nextRangeStart = ((i + 1) * bucketSize).toInt() + 1
            val nextRangeEnd = min(((i + 2) * bucketSize).toInt() + 1, data.size)

            var avgX = 0.0; var avgV = 0.0; var avgI = 0.0
            val count = rangeEnd - rangeStart
            for (j in rangeStart until rangeEnd) {
                avgX += data[j].third; avgV += data[j].first; avgI += data[j].second
            }
            avgX /= count; avgV /= count; avgI /= count

            var maxArea = -1.0; var maxIdx = rangeStart
            for (j in nextRangeStart until nextRangeEnd) {
                val area = 0.5 * kotlin.math.abs(
                    (data[a].third - avgX) * (data[j].second - data[a].second) -
                    (data[a].third - data[j].third) * (avgI - data[a].second)
                )
                if (area > maxArea) { maxArea = area; maxIdx = j }
            }

            result.add(data[maxIdx])
            a = maxIdx
        }

        if (data.size > 1) {
            result.add(data.last())
        }
        return result
    }

    init {
        usbManager.onPacketReceived = { data ->
            parser.feedRawData(data)
        }

        usbManager.onConnected = {
            viewModelScope.launch(Dispatchers.Main) {
                _isConnected.value = true
                _connectionStatus.value = "Connected"
            }
            loadCalibration()
            loadRange()
        }

        usbManager.onDisconnected = {
            viewModelScope.launch(Dispatchers.Main) {
                _isConnected.value = false
                _connectionStatus.value = "Disconnected"
                waveBuffer.clear()
                _waveformData.value = emptyList()
                resetStats()
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            parser.latestSamples.collect { samples ->
                if (samples.isEmpty()) return@collect

                if (startTimeMs == 0L) {
                    startTimeMs = System.currentTimeMillis()
                }

                for (s in samples) {
                    val now = System.currentTimeMillis()
                    val elapsed = now - startTimeMs
                    waveBuffer.addLast(Triple(s.voltage, s.current, elapsed))
                    if (waveBuffer.size > 200000) {
                        waveBuffer.removeFirst()
                    }

                    sumVoltage += s.voltage
                    sumCurrent += s.current
                    sampleCount++

                    if (s.current != 0f && s.voltage != 0f) {
                        validPowerSum += s.voltage.toDouble() * s.current.toDouble() / 1_000_000.0
                        validPowerCount++
                    }

                    if (recorder.isRecording.value) {
                        recorder.addSample(s.voltage, s.current)
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastUpdateTimeMs >= updateIntervalMs) {
                    lastUpdateTimeMs = now

                    if (waveBuffer.isEmpty()) return@collect

                    if (!_isPaused.value) {
                        val latestTime = waveBuffer.last().third
                        val visibleMs = _visibleTimeMs.value
                        val windowEnd = latestTime
                        val windowStart = windowEnd - visibleMs

                        val windowData = mutableListOf<Triple<Float, Float, Long>>()
                        for (point in waveBuffer) {
                            if (point.third >= windowStart && point.third <= windowEnd) {
                                windowData.add(point)
                            }
                        }

                        val targetPoints = 200
                        val downsampled = lttbDownsample(windowData, targetPoints)
                        _waveformData.value = downsampled
                    }
                }

                if (now - lastValuePanelUpdateTimeMs >= valuePanelUpdateIntervalMs) {
                    lastValuePanelUpdateTimeMs = now
                    val last = samples.last()
                    _currentVoltage.value = last.voltage
                    _currentCurrent.value = last.current

                    if (sampleCount > 0) {
                        _avgVoltage.value = (sumVoltage / sampleCount).toFloat()
                        _avgCurrent.value = (sumCurrent / sampleCount).toFloat()
                    }
                    if (validPowerCount > 0) {
                        _averagePower.value = (validPowerSum / validPowerCount).toFloat()
                    }

                    if (recorder.isRecording.value) {
                        val ctx = getApplication<Application>()
                        IslandHelper.showLiveMeasurement(
                            context = ctx,
                            voltage = last.voltage,
                            current = last.current,
                            power = last.voltage * last.current / 1_000_000f,
                            avgPower = _averagePower.value,
                            isRecording = true
                        )
                    }
                }
            }
        }
    }

    private fun resetStats() {
        startTimeMs = 0L
        lastUpdateTimeMs = 0L
        sumVoltage = 0.0
        sumCurrent = 0.0
        sampleCount = 0L
        validPowerSum = 0.0
        validPowerCount = 0L
        _avgVoltage.value = 0f
        _avgCurrent.value = 0f
        _averagePower.value = 0f
        _isPaused.value = false
    }

    fun connect() {
        val device = usbManager.findDevice()
        if (device != null) {
            _connectionStatus.value = "Requesting permission..."
            usbManager.requestPermission(device)
        } else {
            _connectionStatus.value = "No device found"
        }
    }

    fun disconnect() {
        usbManager.disconnect()
        _isConnected.value = false
        _connectionStatus.value = "Disconnected"
        waveBuffer.clear()
        _waveformData.value = emptyList()
        resetStats()
    }

    fun loadCalibration() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cal = usbManager.getCalibration()
                if (cal != null) {
                    _calibration.value = cal
                    parser.updateCalibration(cal)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadCalibration failed", e)
            }
        }
    }

    fun loadRange() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val range = usbManager.getRange()
                if (range != null) {
                    _currentRange.value = range.mode
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadRange failed", e)
            }
        }
    }

    fun setRange(mode: RangeMode) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = usbManager.setRange(mode)
                if (result != null) {
                    _currentRange.value = result.mode
                }
            } catch (e: Exception) {
                Log.e(TAG, "setRange failed", e)
            }
        }
    }

    fun toggleRecording() {
        if (recorder.isRecording.value) {
            recorder.stop()
            IslandHelper.cancelNotification(getApplication())
        } else {
            recorder.start()
        }
    }

    fun clearWaveform() {
        waveBuffer.clear()
        _waveformData.value = emptyList()
        resetStats()
    }

    override fun onCleared() {
        super.onCleared()
        IslandHelper.cancelNotification(getApplication())
        usbManager.disconnect()
    }

    companion object {
        private const val TAG = "WaveformVM"
    }
}
