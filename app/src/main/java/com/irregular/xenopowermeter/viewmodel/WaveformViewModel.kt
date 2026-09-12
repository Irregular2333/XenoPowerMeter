package com.irregular.xenopowermeter.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.irregular.xenopowermeter.data.model.Calibration
import com.irregular.xenopowermeter.data.model.RangeMode
import com.irregular.xenopowermeter.data.usb.ProtocolParser
import com.irregular.xenopowermeter.data.usb.UsbCdcManager
import com.irregular.xenopowermeter.notification.IslandHelper
import com.irregular.xenopowermeter.recording.Recorder
import com.irregular.xenopowermeter.recording.RecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
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

    private val _connectionEvents = Channel<String>(Channel.BUFFERED)
    val connectionEvents = _connectionEvents.receiveAsFlow()

    private var autoConnectJob: Job? = null

    /** True while a plug-in-triggered connect is in flight; its setup errors stay silent. */
    @Volatile
    private var autoConnectPending = false

    private val _currentVoltage = MutableStateFlow(0f)
    val currentVoltage: StateFlow<Float> = _currentVoltage.asStateFlow()

    private val _currentCurrent = MutableStateFlow(0f)
    val currentCurrent: StateFlow<Float> = _currentCurrent.asStateFlow()

    private val _calibration = MutableStateFlow(Calibration.DEFAULT)
    val calibration: StateFlow<Calibration> = _calibration.asStateFlow()

    private val _waveformData = MutableStateFlow<List<Triple<Float, Float, Long>>>(emptyList())
    val waveformData: StateFlow<List<Triple<Float, Float, Long>>> = _waveformData.asStateFlow()

    private val waveBuffer = ArrayDeque<Triple<Float, Float, Long>>(200000)
    // Reusable scratch for the visible-window extraction (single collector thread).
    private val windowScratch = ArrayList<Triple<Float, Float, Long>>(81920)
    private var startTimeMs = 0L
    private var lastUpdateTimeMs = 0L
    private val updateIntervalMs = 250L
    private var lastValuePanelUpdateTimeMs = 0L
    private val valuePanelUpdateIntervalMs = 1000L

    private val _averagePower = MutableStateFlow(0f)
    val averagePower: StateFlow<Float> = _averagePower.asStateFlow()

    private var validPowerSum = 0.0
    private var validPowerCount = 0L

    private val _visibleTimeMs = MutableStateFlow(5000L)
    val visibleTimeMs: StateFlow<Long> = _visibleTimeMs.asStateFlow()

    private var isLandscape = false

    /**
     * False while the user is on Settings/About: waveform bookkeeping is
     * paused (stats and recording keep running) so the 10 kHz pipeline stops
     * competing with composition during page transitions.
     */
    @Volatile
    var mainScreenVisible = true
        private set

    fun setMainScreenVisible(visible: Boolean) {
        mainScreenVisible = visible
    }

    fun setOrientation(landscape: Boolean) {
        if (isLandscape != landscape) {
            isLandscape = landscape
            _visibleTimeMs.value = if (landscape) 8000L else 5000L
        }
    }

    private fun lttbDownsample(
        data: List<Triple<Float, Float, Long>>,
        targetPoints: Int
    ): List<Triple<Float, Float, Long>> {
        if (data.size <= targetPoints || targetPoints < 3) return data

        // Normalize both channels so voltage spikes survive downsampling too.
        var vMin = Float.MAX_VALUE; var vMax = -Float.MAX_VALUE
        var iMin = Float.MAX_VALUE; var iMax = -Float.MAX_VALUE
        for (p in data) {
            if (p.first < vMin) vMin = p.first
            if (p.first > vMax) vMax = p.first
            if (p.second < iMin) iMin = p.second
            if (p.second > iMax) iMax = p.second
        }
        val vSpan = max(1e-6f, vMax - vMin)
        val iSpan = max(1e-6f, iMax - iMin)

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
            val count = nextRangeEnd - nextRangeStart
            if (count <= 0) {
                // Last bucket collapsed: anchor against the final sample instead.
                val last = data.last()
                avgX = last.third.toDouble(); avgV = last.first.toDouble(); avgI = last.second.toDouble()
            } else {
                for (j in nextRangeStart until nextRangeEnd) {
                    avgX += data[j].third; avgV += data[j].first; avgI += data[j].second
                }
                avgX /= count; avgV /= count; avgI /= count
            }

            var maxArea = -1.0; var maxIdx = rangeStart
            for (j in rangeStart until rangeEnd) {
                val p = data[j]
                val areaV = abs((data[a].third - avgX) * (p.first - data[a].first) -
                        (p.third - data[a].third) * (avgV - data[a].first)) / vSpan
                val areaI = abs((data[a].third - avgX) * (p.second - data[a].second) -
                        (p.third - data[a].third) * (avgI - data[a].second)) / iSpan
                val area = areaV + areaI
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
        clearWaveform()

        usbManager.onAdcPacket = { packet ->
            parser.feedPacket(packet)
        }

        usbManager.onConnected = {
            autoConnectPending = false
            viewModelScope.launch(Dispatchers.Main) {
                _isConnected.value = true
                _connectionEvents.send(appString(com.irregular.xenopowermeter.R.string.event_connected))
            }
            loadCalibration()
        }

        usbManager.onDisconnected = {
            viewModelScope.launch(Dispatchers.Main) {
                _isConnected.value = false
            }
        }

        usbManager.onError = { message ->
            // Auto-connect is a convenience the user never explicitly asked
            // for, so its setup failures stay silent (e.g. a premature attempt
            // right at plug-in). Manual connects and everything after a
            // successful connect are always reported.
            val suppress = autoConnectPending && !_isConnected.value
            autoConnectPending = false
            if (!suppress) {
                viewModelScope.launch(Dispatchers.Main) {
                    _connectionEvents.send(message)
                }
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            parser.latestSamples.collect { samples ->
                if (samples.isEmpty()) return@collect

                if (startTimeMs == 0L) {
                    startTimeMs = System.currentTimeMillis()
                }
                val now = System.currentTimeMillis()
                val elapsed = now - startTimeMs
                val mainVisible = mainScreenVisible

                for (s in samples) {
                    if (s.current != 0f && s.voltage != 0f) {
                        validPowerSum += s.voltage.toDouble() * s.current.toDouble() / 1_000_000.0
                        validPowerCount++
                    }

                    if (recorder.isRecording.value) {
                        recorder.addSample(s.voltage, s.current, s.timestamp)
                    }

                    // Waveform bookkeeping only feeds the Main page chart; while
                    // the user is on Settings/About nobody consumes it, and the
                    // 10 kHz churn would fight the UI during page transitions.
                    if (mainVisible) {
                        waveBuffer.addLast(Triple(s.voltage, s.current, elapsed))
                        if (waveBuffer.size > 200000) {
                            waveBuffer.removeFirst()
                        }
                    }
                }

                if (mainVisible && now - lastUpdateTimeMs >= updateIntervalMs) {
                    lastUpdateTimeMs = now

                    if (waveBuffer.isNotEmpty()) {
                        val windowStart = waveBuffer.last().third - _visibleTimeMs.value

                        // The buffer is time-ordered — walk backwards from the
                        // tail instead of scanning all 200k entries.
                        windowScratch.clear()
                        for (i in waveBuffer.size - 1 downTo 0) {
                            val point = waveBuffer[i]
                            if (point.third < windowStart) break
                            windowScratch.add(point)
                        }
                        windowScratch.reverse()

                        _waveformData.value = lttbDownsample(windowScratch, 200)
                    }
                }

                if (now - lastValuePanelUpdateTimeMs >= valuePanelUpdateIntervalMs) {
                    lastValuePanelUpdateTimeMs = now
                    val last = samples.last()
                    _currentVoltage.value = last.voltage
                    _currentCurrent.value = last.current

                    if (validPowerCount > 0) {
                        _averagePower.value = (validPowerSum / validPowerCount).toFloat()
                    }

                    if (recorder.isRecording.value && com.irregular.xenopowermeter.AppSettings.notificationEnabled) {
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
        lastValuePanelUpdateTimeMs = 0L
        validPowerSum = 0.0
        validPowerCount = 0L
        _averagePower.value = 0f
    }

    private fun appString(id: Int): String =
        com.irregular.xenopowermeter.AppSettings.localizedContext(getApplication()).getString(id)

    fun connect() {
        autoConnectPending = false
        val device = usbManager.findDevice()
        if (device != null) {
            usbManager.requestPermission(device)
        } else {
            viewModelScope.launch(Dispatchers.Main) {
                _connectionEvents.send(appString(com.irregular.xenopowermeter.R.string.event_no_device))
            }
        }
    }

    /**
     * Passive variant for plug-in events. Enumeration can lag the attach
     * intent, so poll for the device for a few seconds and swallow all setup
     * errors — the user never explicitly asked for this attempt, so it must
     * never nag. Gated by the auto-connect settings toggle; manual connects
     * bypass it.
     */
    fun connectAuto() {
        if (!com.irregular.xenopowermeter.AppSettings.autoConnect) return
        if (autoConnectJob?.isActive == true) return
        autoConnectJob = viewModelScope.launch(Dispatchers.IO) {
            autoConnectPending = true
            try {
                // A manual connect is always at least a few hundred ms after
                // plug-in; match that so the port never opens while the device
                // is still settling.
                delay(AUTO_CONNECT_SETTLE_MS)
                repeat(AUTO_CONNECT_POLLS) {
                    if (_isConnected.value) return@launch
                    val device = usbManager.findDevice()
                    if (device != null) {
                        usbManager.requestPermission(device)
                        return@launch
                    }
                    delay(AUTO_CONNECT_POLL_INTERVAL_MS)
                }
                autoConnectPending = false
            } catch (e: Exception) {
                autoConnectPending = false
            }
        }
    }

    fun disconnect() {
        usbManager.disconnect()
        _isConnected.value = false
        viewModelScope.launch(Dispatchers.Main) {
            _connectionEvents.send(appString(com.irregular.xenopowermeter.R.string.event_disconnected))
        }
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

    fun setRange(mode: RangeMode) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                usbManager.setRange(mode)
            } catch (e: Exception) {
                Log.e(TAG, "setRange failed", e)
            }
        }
    }

    fun toggleRecording() {
        val ctx = getApplication<Application>()
        if (recorder.isRecording.value) {
            recorder.stop()
            if (com.irregular.xenopowermeter.AppSettings.notificationEnabled) {
                IslandHelper.cancelNotification(ctx)
                ctx.stopService(Intent(ctx, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_STOP
                })
            }
        } else {
            recorder.start(File(getApplication<Application>().filesDir, "recordings"))
            if (com.irregular.xenopowermeter.AppSettings.notificationEnabled) {
                val ctx = getApplication<Application>()
                IslandHelper.showLiveMeasurement(
                    context = ctx,
                    voltage = _currentVoltage.value,
                    current = _currentCurrent.value,
                    power = _currentVoltage.value * _currentCurrent.value / 1_000_000f,
                    avgPower = _averagePower.value,
                    isRecording = true
                )
                ctx.startForegroundService(Intent(ctx, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                })
            }
        }
    }

    fun clearWaveform() {
        waveBuffer.clear()
        _waveformData.value = emptyList()
        resetStats()
    }

    override fun onCleared() {
        super.onCleared()
        val ctx = getApplication<Application>()
        if (com.irregular.xenopowermeter.AppSettings.notificationEnabled) {
            IslandHelper.cancelNotification(ctx)
            if (recorder.isRecording.value) {
                ctx.stopService(Intent(ctx, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_STOP
                })
            }
        }
        usbManager.disconnect()
    }

    companion object {
        private const val TAG = "WaveformVM"
        private const val AUTO_CONNECT_SETTLE_MS = 800L
        private const val AUTO_CONNECT_POLLS = 12
        private const val AUTO_CONNECT_POLL_INTERVAL_MS = 500L
    }
}
