package com.irregular.xenopowermeter.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.irregular.xenopowermeter.R
import com.irregular.xenopowermeter.data.model.Calibration
import com.irregular.xenopowermeter.data.model.RangeMode
import com.irregular.xenopowermeter.data.usb.ProtocolParser
import com.irregular.xenopowermeter.data.usb.UsbCdcManager
import com.irregular.xenopowermeter.notification.IslandHelper
import com.irregular.xenopowermeter.notification.RecordingForegroundService
import com.irregular.xenopowermeter.recording.Recorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

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

    private val recordingControlMutex = Mutex()
    private var recordingActionJob: Job? = null
    private val connectionEpoch = AtomicLong()
    // Accessed under recordingControlMutex. A delayed disconnect may only
    // stop the recording belonging to that connection, never a reconnect.
    private var recordingConnectionEpoch = -1L
    @Volatile
    private var cleared = false

    /** True while a plug-in-triggered connect is in flight; its setup errors stay silent. */
    @Volatile
    private var autoConnectPending = false

    private val _currentVoltage = MutableStateFlow(0f)
    val currentVoltage: StateFlow<Float> = _currentVoltage.asStateFlow()

    private val _currentCurrent = MutableStateFlow(0f)
    val currentCurrent: StateFlow<Float> = _currentCurrent.asStateFlow()

    private val _calibration = MutableStateFlow(Calibration.DEFAULT)
    val calibration: StateFlow<Calibration> = _calibration.asStateFlow()

    private val _waveformData = MutableStateFlow<List<WaveformPoint>>(emptyList())
    val waveformData: StateFlow<List<WaveformPoint>> = _waveformData.asStateFlow()

    private val waveBuffer = WaveformRingBuffer(WAVEFORM_BUFFER_SIZE)
    // Guards waveBuffer and the average-power accumulators: the collector
    // thread writes them, clearWaveform() (main thread) may clear them.
    private val bufferLock = Any()
    // Reusable scratch for the visible-window extraction (single collector thread).
    private val windowScratch = ArrayList<WaveformPoint>(81920)
    private val waveformTimeline = WaveformTimeline()
    private val waveformStartupGate = WaveformStartupGate()
    // Reject an in-flight window extraction when clear/reconnect resets it.
    private var waveformRevision = 0L
    private var lastUpdateTimeMs = 0L
    private val updateIntervalMs = 250L
    private var lastValuePanelUpdateTimeMs = 0L
    private val valuePanelUpdateIntervalMs = 1000L

    private val _averagePower = MutableStateFlow(0f)
    val averagePower: StateFlow<Float> = _averagePower.asStateFlow()

    private val powerStatistics = PowerStatistics()

    private val _visibleTimeMs = MutableStateFlow(5000L)
    val visibleTimeMs: StateFlow<Long> = _visibleTimeMs.asStateFlow()

    private var isLandscape = false

    /**
     * False while the user is on Settings/About: waveform republishing
     * (window extraction + downsampling) is paused so it doesn't compete
     * with composition during page transitions. The ring buffer itself keeps
     * recording the whole time, so the chart is continuous on return; stats
     * and recording also keep running.
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

    init {
        clearWaveform()

        parser.onSampleQueueOverflow = {
            viewModelScope.launch(Dispatchers.IO) {
                recordingControlMutex.withLock {
                    val wasRecording = recorder.isRecording.value
                    recorder.failDueToSampleOverflow()
                    usbManager.disconnect()
                    if (!wasRecording) {
                        _connectionEvents.send(appString(R.string.event_sample_queue_overflow))
                    }
                }
            }
        }

        usbManager.onConnected = {
            autoConnectPending = false
            parser.resetConnectionQueue()
            val connectedEpoch = synchronized(bufferLock) {
                val epoch = connectionEpoch.incrementAndGet()
                resetWaveformLocked()
                waveformStartupGate.reset()
                _isConnected.value = true
                epoch
            }
            // The reader captures this callback, including its fixed epoch.
            // Even a late callback from an old read stays tagged as old data.
            usbManager.onAdcPacket = { packet ->
                parser.feedPacket(packet, connectedEpoch)
            }
            viewModelScope.launch(Dispatchers.Main) {
                _connectionEvents.send(appString(R.string.event_connected))
            }
            loadCalibration()
        }

        usbManager.onDisconnected = {
            _isConnected.value = false
            val disconnectedEpoch = connectionEpoch.getAndIncrement()
            viewModelScope.launch(Dispatchers.IO) {
                recordingControlMutex.withLock {
                    if (recordingConnectionEpoch != disconnectedEpoch) return@withLock
                    val wasRecording = recorder.isRecording.value
                    recorder.stop()
                    cancelRecordingNotification()
                    if (wasRecording && !recorder.failed.value) {
                        _connectionEvents.send(appString(R.string.recording_disconnected))
                    }
                }
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
            parser.sampleFlow.collect { samples ->
                if (samples.isEmpty()) return@collect

                val now = System.currentTimeMillis()
                val mainVisible = mainScreenVisible

                val packetConnection = samples.first().connectionId
                val previewReady = synchronized(bufferLock) {
                    // A queued batch from the old USB stream cannot become
                    // the first point of the new connection's timeline.
                    if (packetConnection != connectionEpoch.get()) return@collect
                    if (!waveformStartupGate.acceptPacket(samples.first().receivedAtUs)) {
                        false
                    } else {
                        val position = waveformTimeline.acceptPacket(
                            samples.first().timestamp, samples.first().receivedAtUs
                        )
                        if (position.resetHistory) {
                            waveBuffer.clear()
                            _waveformData.value = emptyList()
                            waveformRevision++
                            lastUpdateTimeMs = 0L
                            lastValuePanelUpdateTimeMs = 0L
                        }
                        for (s in samples) {
                            waveBuffer.addLast(s.voltage, s.current, position.timeMs, position.segmentId)
                            powerStatistics.add(s.voltage, s.current)
                        }
                        true
                    }
                }

                for (s in samples) {
                    if (packetConnection != connectionEpoch.get()) break
                    if (recorder.isRecording.value) {
                        recorder.addSample(s.voltage, s.current, s.timestamp)
                    }
                }

                // Initial USB/ADC startup data remains available to the raw
                // recorder but does not set the chart scale or live readings.
                if (!previewReady) return@collect

                if (mainVisible && now - lastUpdateTimeMs >= updateIntervalMs) {
                    lastUpdateTimeMs = now

                    val revision = synchronized(bufferLock) {
                        if (waveBuffer.count > 0) {
                            val windowStart = waveBuffer.lastTimeMs() - _visibleTimeMs.value

                            // The buffer is time-ordered — walk backwards from the
                            // tail instead of scanning all 200k entries.
                            waveBuffer.copyWindowNewestFirst(windowStart, windowScratch)
                        } else {
                            windowScratch.clear()
                        }
                        waveformRevision
                    }
                    windowScratch.reverse()

                    val displayed = downsampleWaveform(windowScratch, 200)
                    synchronized(bufferLock) {
                        if (revision == waveformRevision) _waveformData.value = displayed
                    }
                }

                if (now - lastValuePanelUpdateTimeMs >= valuePanelUpdateIntervalMs) {
                    lastValuePanelUpdateTimeMs = now
                    val last = samples.last()
                    _currentVoltage.value = last.voltage
                    _currentCurrent.value = last.current

                    synchronized(bufferLock) {
                        _averagePower.value = powerStatistics.averageWatts
                    }

                    recordingControlMutex.withLock {
                        showRecordingNotification()
                    }
                }
            }
        }

        // This collector runs alongside sample processing, not after the
        // infinite sample flow. Events retain their reason across retries.
        viewModelScope.launch(Dispatchers.Main) {
            recorder.events.collect { event ->
                recordingControlMutex.withLock {
                    if (!recorder.isRecording.value) cancelRecordingNotification()
                }
                val message = when (event) {
                    is Recorder.RecordingEvent.Failed -> {
                        when (event.stage) {
                            Recorder.FailureStage.START -> R.string.recording_start_failed
                            Recorder.FailureStage.SAMPLE_OVERFLOW -> R.string.event_sample_queue_overflow
                            else -> R.string.recording_lost
                        }
                    }
                    Recorder.RecordingEvent.Completed -> R.string.recording_auto_stopped
                }
                _connectionEvents.send(appString(message))
            }
        }
    }

    /** Caller holds bufferLock; recording and its timestamps are independent. */
    private fun resetWaveformLocked() {
        waveBuffer.clear()
        waveformTimeline.reset()
        waveformRevision++
        _waveformData.value = emptyList()
        lastUpdateTimeMs = 0L
        lastValuePanelUpdateTimeMs = 0L
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
                _connectionEvents.send(appString(R.string.event_no_device))
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
            } catch (e: CancellationException) {
                autoConnectPending = false
                throw e
            } catch (e: Exception) {
                autoConnectPending = false
            }
        }
    }

    fun disconnect() {
        autoConnectJob?.cancel()
        usbManager.disconnect()
        _isConnected.value = false
        viewModelScope.launch(Dispatchers.Main) {
            _connectionEvents.send(appString(R.string.event_disconnected))
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "loadCalibration failed", e)
            }
        }
    }

    fun setRange(mode: RangeMode) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                usbManager.setRange(mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "setRange failed", e)
            }
        }
    }

    fun toggleRecording() {
        // The button may be tapped again before its IO job publishes a new
        // state. Drop duplicate requests rather than starting two sessions.
        if (recordingActionJob?.isActive == true) return
        val ctx = getApplication<Application>()
        val shouldStop = recorder.isRecording.value
        val requestedEpoch = connectionEpoch.get()
        recordingActionJob = viewModelScope.launch(Dispatchers.IO) {
            recordingControlMutex.withLock {
                if (shouldStop) {
                    recorder.stop()
                    cancelRecordingNotification()
                } else if (_isConnected.value && !cleared && connectionEpoch.get() == requestedEpoch) {
                    recordingConnectionEpoch = requestedEpoch
                    recorder.start(File(ctx.filesDir, "recordings"))
                    if (!_isConnected.value || connectionEpoch.get() != requestedEpoch) {
                        recorder.stop()
                        cancelRecordingNotification()
                    } else {
                        showRecordingNotification()
                        // The foreground service is what actually protects the
                        // recording from battery savers; the notification it
                        // reuses is guarded by the same preference.
                        if (com.irregular.xenopowermeter.AppSettings.notificationEnabled &&
                            !cleared && recorder.isRecording.value
                        ) {
                            RecordingForegroundService.start(ctx)
                        }
                    }
                }
            }
        }
    }

    private fun showRecordingNotification() {
        if (cleared || !recorder.isRecording.value ||
            !com.irregular.xenopowermeter.AppSettings.notificationEnabled
        ) return
        // A notification failure must never kill the sample collector.
        try {
            IslandHelper.showLiveMeasurement(
                context = getApplication(),
                voltage = _currentVoltage.value,
                current = _currentCurrent.value,
                power = _currentVoltage.value * _currentCurrent.value / 1_000_000f,
                avgPower = _averagePower.value,
                isRecording = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "notification update failed", e)
        } finally {
            // Teardown or a preference change can race with notification
            // construction. Undo a late notify after the earlier cancellation.
            if (cleared || !recorder.isRecording.value ||
                !com.irregular.xenopowermeter.AppSettings.notificationEnabled
            ) cancelRecordingNotification()
        }
    }

    private fun cancelRecordingNotification() {
        try {
            IslandHelper.cancelNotification(getApplication())
        } catch (e: Exception) {
            Log.e(TAG, "notification cleanup failed", e)
        }
        // Every "recording is no longer active" path funnels through here, so
        // the service's lifecycle rides on it. stopService is a no-op when the
        // service is not running.
        try {
            RecordingForegroundService.stop(getApplication())
        } catch (e: Exception) {
            Log.e(TAG, "foreground service stop failed", e)
        }
    }

    fun clearWaveform() {
        synchronized(bufferLock) {
            resetWaveformLocked()
            powerStatistics.clear()
            _averagePower.value = 0f
        }
    }

    override fun onCleared() {
        cleared = true
        super.onCleared()
        // Also rejects an IO start that was queued before this ViewModel died.
        recorder.close()
        cancelRecordingNotification()
        usbManager.disconnect()
    }

    companion object {
        private const val TAG = "WaveformVM"
        private const val AUTO_CONNECT_SETTLE_MS = 800L
        private const val AUTO_CONNECT_POLLS = 12
        private const val AUTO_CONNECT_POLL_INTERVAL_MS = 500L
        // 20 s of waveform history at the firmware's 10 kHz sample rate.
        private const val WAVEFORM_BUFFER_SIZE = 200_000
    }
}

/**
 * Fixed-capacity FIFO ring for waveform samples in flat primitive arrays.
 * Replaces a Triple-boxing ArrayDeque that allocated ~10k objects/s and held
 * ~17 MB of heap for the same window; this holds 200k samples in ~4.8 MB of
 * flat arrays with zero per-sample allocation.
 *
 * Single writer (the collector coroutine); cross-thread [clear] and the
 * average-power accumulators are guarded by WaveformViewModel.bufferLock.
 */
private class WaveformRingBuffer(private val capacity: Int) {
    private val voltage = FloatArray(capacity)
    private val current = FloatArray(capacity)
    private val timeMs = LongArray(capacity)
    private val segmentId = LongArray(capacity)
    private var head = 0

    var count = 0
        private set

    fun addLast(v: Float, i: Float, t: Long, segment: Long) {
        val pos = (head + count) % capacity
        if (count == capacity) {
            head = (head + 1) % capacity
        } else {
            count++
        }
        voltage[pos] = v
        current[pos] = i
        timeMs[pos] = t
        segmentId[pos] = segment
    }

    fun lastTimeMs(): Long = timeMs[(head + count - 1) % capacity]

    fun clear() {
        head = 0
        count = 0
    }

    /**
     * Appends newest-first entries with time >= windowStartMs to [out]. The
     * buffer is time-ordered, so the backward walk stops at the first older
     * entry; the caller reverses [out] afterwards. Entries are boxed into
     * display points here, including continuity IDs from the original packets.
     */
    fun copyWindowNewestFirst(windowStartMs: Long, out: MutableList<WaveformPoint>) {
        out.clear()
        var idx = count - 1
        while (idx >= 0) {
            val pos = (head + idx) % capacity
            if (timeMs[pos] < windowStartMs) break
            out.add(WaveformPoint(voltage[pos], current[pos], timeMs[pos], segmentId[pos]))
            idx--
        }
    }
}
