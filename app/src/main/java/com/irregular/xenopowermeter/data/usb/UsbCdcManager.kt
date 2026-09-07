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
import com.irregular.xenopowermeter.data.model.Calibration
import com.irregular.xenopowermeter.data.model.CmdResponse
import com.irregular.xenopowermeter.data.model.CmdType
import com.irregular.xenopowermeter.data.model.RangeMode
import com.irregular.xenopowermeter.data.model.RangeStatus
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbCdcManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var readJob: Job? = null
    private var receiverRegistered = false
    private var pendingDevice: UsbDevice? = null

    var isConnected = false
        private set

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onPacketReceived: ((ByteArray) -> Unit)? = null
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
                            onError?.invoke("USB permission denied")
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        disconnect()
                        onDisconnected?.invoke()
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
                    context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
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
        try {
            val driver = CdcAcmSerialDriver(device)
            if (driver.ports.isEmpty()) {
                Log.e(TAG, "No ports on CDC device")
                onError?.invoke("No serial port found")
                return
            }

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                Log.e(TAG, "Failed to open USB device")
                onError?.invoke("Failed to open USB device")
                return
            }

            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true

            serialPort = port
            isConnected = true
            startReading()
            onConnected?.invoke()
            Log.i(TAG, "USB connected")
        } catch (e: Exception) {
            Log.e(TAG, "connectToDevice failed", e)
            isConnected = false
            onError?.invoke("Connection failed: ${e.message}")
        }
    }

    private fun startReading() {
        readJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(256)
            while (isActive && isConnected) {
                try {
                    val port = serialPort ?: break
                    val len = port.read(buffer, 100)
                    if (len > 0) {
                        val data = buffer.copyOf(len)
                        onPacketReceived?.invoke(data)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Read error", e)
                    if (isConnected) {
                        withContext(Dispatchers.Main) {
                            onError?.invoke("Read error: ${e.message}")
                            disconnect()
                            onDisconnected?.invoke()
                        }
                    }
                    break
                }
            }
        }
    }

    fun sendCommand(type: CmdType, payload: ByteArray? = null): CmdResponse? {
        val port = serialPort ?: return null
        val payloadLen = payload?.size ?: 0
        if (payloadLen > 48) return null

        val frameLen = 10 + payloadLen
        val frame = ByteBuffer.allocate(frameLen).order(ByteOrder.LITTLE_ENDIAN)

        frame.put(0xA5.toByte())
        frame.put(0x5A)
        frame.put(1)
        frame.put(type.code.toByte())
        frame.putShort(0)
        frame.putShort(payloadLen.toShort())
        if (payload != null) {
            frame.put(payload)
        }

        val crc = calculateCrc16(frame.array(), 2, 8 + payloadLen)
        frame.putShort(crc)

        return try {
            port.write(frame.array(), 1000)
            Thread.sleep(50)
            val response = ByteArray(64)
            val len = port.read(response, 500)
            if (len >= 10) parseResponse(response.copyOf(len)) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseResponse(data: ByteArray): CmdResponse? {
        if (data.size < 10) return null
        if (data[0] != 0xA5.toByte() || data[1] != 0x5A.toByte()) return null

        val payloadLen = ByteBuffer.wrap(data, 6, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

        if (payloadLen + 10 > data.size) return null

        val status = data[8].toInt() and 0xFF
        val payload = if (payloadLen > 1) data.copyOfRange(9, 9 + payloadLen - 1) else ByteArray(0)

        return CmdResponse(status, payload)
    }

    fun getCalibration(): Calibration? {
        val resp = sendCommand(CmdType.CAL_GET) ?: return null
        if (resp.status != CmdResponse.STATUS_OK) return null
        return Calibration.fromByteArray(resp.payload)
    }

    fun setCalibration(cal: Calibration): Boolean {
        val resp = sendCommand(CmdType.CAL_SET, cal.toByteArray()) ?: return false
        return resp.status == CmdResponse.STATUS_OK
    }

    fun resetCalibration(): Calibration? {
        val resp = sendCommand(CmdType.CAL_RESET, byteArrayOf(0xC3.toByte(), 0x3C)) ?: return null
        if (resp.status != CmdResponse.STATUS_OK) return null
        return Calibration.fromByteArray(resp.payload)
    }

    fun getRange(): RangeStatus? {
        val resp = sendCommand(CmdType.RANGE_GET) ?: return null
        if (resp.status != CmdResponse.STATUS_OK || resp.payload.size < 2) return null
        return RangeStatus(
            mode = RangeMode.fromCode(resp.payload[0].toInt() and 0xFF),
            hardwareRange = RangeMode.fromCode(resp.payload[1].toInt() and 0xFF)
        )
    }

    fun setRange(mode: RangeMode): RangeStatus? {
        val resp = sendCommand(CmdType.RANGE_SET, byteArrayOf(mode.code.toByte())) ?: return null
        if (resp.status != CmdResponse.STATUS_OK || resp.payload.size < 2) return null
        return RangeStatus(
            mode = RangeMode.fromCode(resp.payload[0].toInt() and 0xFF),
            hardwareRange = RangeMode.fromCode(resp.payload[1].toInt() and 0xFF)
        )
    }

    fun disconnect() {
        isConnected = false
        readJob?.cancel()
        readJob = null
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
    }

    private fun calculateCrc16(data: ByteArray, offset: Int, length: Int): Short {
        var crc = 0xFFFF.toInt()
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            for (j in 0 until 8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc.toShort()
    }

    companion object {
        private const val TAG = "UsbCdcManager"
        const val ACTION_USB_PERMISSION = "com.irregular.xenopowermeter.USB_PERMISSION"
    }
}
