package com.irregular.xenopowermeter.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.irregular.xenopowermeter.R
import com.irregular.xenopowermeter.data.model.Calibration
import com.irregular.xenopowermeter.data.model.CmdResponse
import com.irregular.xenopowermeter.data.model.CmdType
import com.irregular.xenopowermeter.data.model.RangeMode
import com.irregular.xenopowermeter.data.model.RangeStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UsbCdcManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var readJob: Job? = null
    private var receiverRegistered = false
    private val disconnectHandled = AtomicBoolean(false)
    private val connectInFlight = AtomicBoolean(false)

    private val commandMutex = Mutex()
    private val nextSequence = AtomicInteger(1)
    private val pendingResponses = ConcurrentHashMap<Int, PendingCommand>()

    private class PendingCommand(val type: CmdType) {
        val deferred = CompletableDeferred<CmdResponse>()
    }

    /**
     * The only consumer of the port: read-loop bytes go through the demuxer,
     * which routes ADC packets to onAdcPacket and completes pending command
     * requests (matched by echoed sequence number).
     */
    private val demuxer = FrameDemuxer(
        onAdcPacket = { packet -> onAdcPacket?.invoke(packet) },
        onResponse = { type, sequence, response ->
            val pending = pendingResponses.remove(sequence)
            if (pending != null && pending.type == type) {
                pending.deferred.complete(response)
            }
        }
    )

    private var isConnected = false

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onAdcPacket: ((com.irregular.xenopowermeter.data.model.UsbAdcPacket) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            try {
                when (intent.action) {
                    ACTION_USB_PERMISSION -> {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && device != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                connectToDevice(device)
                            }
                        } else {
                            Log.w(TAG, "USB permission denied")
                            onError?.invoke(context.getString(R.string.event_permission_denied))
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        handleDisconnect()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "BroadcastReceiver error", e)
            }
        }
    }

    fun findDevice(): UsbDevice? {
        for (device in usbManager.deviceList.values) {
            if (isPowerPicoDevice(device)) {
                return device
            }
        }
        return null
    }

    private fun isPowerPicoDevice(device: UsbDevice): Boolean {
        val vid = device.vendorId
        val pid = device.productId
        return (vid == 0x0483 && pid == 0x5740) ||
               (vid == 0x2E8A && pid == 0x000A) ||
               (vid == 0x0483 && pid == 0x572B)
    }

    fun requestPermission(device: UsbDevice) {
        try {
            if (usbManager.hasPermission(device)) {
                Log.i(TAG, "USB permission already granted, connecting...")
                CoroutineScope(Dispatchers.IO).launch {
                    connectToDevice(device)
                }
                return
            }

            if (!receiverRegistered) {
                val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(usbReceiver, filter)
                }
                receiverRegistered = true
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val usbIntent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, usbIntent, flags
            )
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    private suspend fun connectToDevice(device: UsbDevice) {
        // HyperOS can deliver the attach intent / grant broadcast more than
        // once; overlapping attempts race for the USB interface and the loser
        // reports a bogus failure. Only one attempt may run at a time.
        if (isConnected) return
        if (!connectInFlight.compareAndSet(false, true)) return
        try {
            try {
                val driver = CdcAcmSerialDriver(device)
                if (driver.ports.isEmpty()) {
                    Log.e(TAG, "No ports on CDC device")
                    onError?.invoke(context.getString(R.string.event_no_port))
                    return
                }

                // Right after the grant dialog, openDevice can transiently
                // return null; retry before reporting a failure.
                var connection = usbManager.openDevice(device)
                var attempt = 1
                while (connection == null && attempt < OPEN_DEVICE_ATTEMPTS) {
                    delay(OPEN_DEVICE_RETRY_MS)
                    connection = usbManager.openDevice(device)
                    attempt++
                }
                if (connection == null) {
                    Log.e(TAG, "Failed to open USB device")
                    onError?.invoke(context.getString(R.string.event_open_failed))
                    return
                }

                val port = driver.ports[0]
                port.open(connection)
                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                port.dtr = true
                port.rts = true

                disconnectHandled.set(false)
                serialPort = port
                isConnected = true
                startReading()
                onConnected?.invoke()
                Log.i(TAG, "USB connected")
            } catch (e: Exception) {
                Log.e(TAG, "connectToDevice failed", e)
                isConnected = false
                onError?.invoke(context.getString(R.string.event_connect_failed, e.message ?: ""))
            }
        } finally {
            connectInFlight.set(false)
        }
    }

    private fun startReading() {
        readJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(256)
            var failures = 0
            while (isActive && isConnected) {
                try {
                    val port = serialPort ?: break
                    val len = port.read(buffer, 100)
                    failures = 0
                    if (len > 0) {
                        demuxer.feed(buffer.copyOf(len))
                    }
                } catch (e: Exception) {
                    if (!isConnected) break
                    failures++
                    if (failures >= READ_FAILURE_LIMIT) {
                        // Raw library exception text stays in logcat — the
                        // user-facing toast is fully localized.
                        Log.e(TAG, "Read error", e)
                        onError?.invoke(context.getString(R.string.event_read_error))
                        handleDisconnect()
                        break
                    }
                    // A freshly attached device can transiently fail its first
                    // control transfers while the stack settles — retry before
                    // tearing the connection down.
                    delay(READ_RETRY_DELAY_MS)
                }
            }
        }
    }

    suspend fun sendCommand(type: CmdType, payload: ByteArray? = null): CmdResponse? {
        val payloadLen = payload?.size ?: 0
        if (payloadLen > MAX_PAYLOAD) return null

        val sequence = nextSequence.getAndUpdate { if (it >= 0xFFFF) 1 else it + 1 }
        val frame = buildFrame(type, sequence, payload)

        return commandMutex.withLock {
            val port = serialPort ?: return@withLock null
            if (!isConnected) return@withLock null

            val pending = PendingCommand(type)
            pendingResponses[sequence] = pending
            try {
                port.write(frame, 1000)
                withTimeoutOrNull(COMMAND_TIMEOUT_MS) { pending.deferred.await() }
            } catch (e: Exception) {
                Log.e(TAG, "sendCommand failed", e)
                null
            } finally {
                pendingResponses.remove(sequence)
            }
        }
    }

    private fun buildFrame(type: CmdType, sequence: Int, payload: ByteArray?): ByteArray {
        val payloadLen = payload?.size ?: 0
        val frame = ByteBuffer.allocate(CMD_HEADER_SIZE + payloadLen + 2).order(ByteOrder.LITTLE_ENDIAN)

        frame.put(0xA5.toByte())
        frame.put(0x5A)
        frame.put(PROTOCOL_VERSION.toByte())
        frame.put(type.code.toByte())
        frame.putShort(sequence.toShort())
        frame.putShort(payloadLen.toShort())
        if (payload != null) {
            frame.put(payload)
        }

        // Firmware (user_CmdStrategy.c) CRCs offset 2, length 6 + payloadLen:
        // version + type + sequence + length + payload, excluding the CRC field.
        val crc = Crc16.ccittFalse(frame.array(), 2, 6 + payloadLen)
        frame.putShort(crc.toShort())

        return frame.array()
    }

    suspend fun getCalibration(): Calibration? {
        val resp = sendCommand(CmdType.CAL_GET) ?: return null
        if (resp.status != CmdResponse.STATUS_OK) return null
        return Calibration.fromByteArray(resp.payload)
    }

    suspend fun getRange(): RangeStatus? {
        val resp = sendCommand(CmdType.RANGE_GET) ?: return null
        if (resp.status != CmdResponse.STATUS_OK || resp.payload.size < 2) return null
        return RangeStatus(
            mode = RangeMode.fromCode(resp.payload[0].toInt() and 0xFF),
            hardwareRange = RangeMode.fromCode(resp.payload[1].toInt() and 0xFF)
        )
    }

    suspend fun setRange(mode: RangeMode): RangeStatus? {
        val resp = sendCommand(CmdType.RANGE_SET, byteArrayOf(mode.code.toByte())) ?: return null
        if (resp.status != CmdResponse.STATUS_OK || resp.payload.size < 2) return null
        return RangeStatus(
            mode = RangeMode.fromCode(resp.payload[0].toInt() and 0xFF),
            hardwareRange = RangeMode.fromCode(resp.payload[1].toInt() and 0xFF)
        )
    }

    fun disconnect() {
        handleDisconnect()
    }

    /** Idempotent: broadcast, read-loop failure and manual disconnect all funnel here once. */
    private fun handleDisconnect() {
        if (!disconnectHandled.compareAndSet(false, true)) return
        isConnected = false
        readJob?.cancel()
        readJob = null
        pendingResponses.values.forEach { it.deferred.cancel() }
        pendingResponses.clear()
        try {
            serialPort?.close()
        } catch (_: Exception) {}
        serialPort = null
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
        onDisconnected?.invoke()
    }

    companion object {
        private const val TAG = "UsbCdcManager"
        const val ACTION_USB_PERMISSION = "com.irregular.xenopowermeter.USB_PERMISSION"
        private const val PROTOCOL_VERSION = 1
        // 2 magic + 1 version + 1 type + 2 sequence + 2 payloadLen (CRC excluded)
        private const val CMD_HEADER_SIZE = 10
        private const val MAX_PAYLOAD = 48
        private const val COMMAND_TIMEOUT_MS = 500L
        private const val OPEN_DEVICE_ATTEMPTS = 3
        private const val OPEN_DEVICE_RETRY_MS = 200L
        private const val READ_FAILURE_LIMIT = 3
        private const val READ_RETRY_DELAY_MS = 120L
    }
}
