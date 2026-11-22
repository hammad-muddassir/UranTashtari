package com.uran.tashtari.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.uran.tashtari.model.DroneCommand
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleCommandSender(private val context: Context) : CommandSender {

    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private val _state = MutableStateFlow(LinkState(LinkStatus.IDLE, "Not connected"))
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _replies = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val replies: SharedFlow<String> = _replies.asSharedFlow()
    private val _writes = MutableSharedFlow<String>(
        extraBufferCapacity = 64
    )

    override val writes: SharedFlow<String> =
        _writes.asSharedFlow()

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var scanning = false

    // ----- write queue: newest control packet wins; safety commands go first -----
    private val lock = Any()

    private var writing = false
    private var writeStartedAt = 0L

    private var pendingControl: String? = null

    private val pendingSafety = ArrayDeque<String>()
    private val pendingCommands = ArrayDeque<String>()

    private var currentWrite: String? = null

    private val maxPendingCommands = 12

    private val rxBuffer = StringBuilder()


    private fun setState(status: LinkStatus, message: String) {
        _state.value = LinkState(status, message)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun isReady() = _state.value.status == LinkStatus.READY && rxChar != null

    // ------------------------------------------------------------ connect
    override fun connect() {
        if (!hasPermission()) {
            setState(LinkStatus.IDLE, "Bluetooth permission missing"); return
        }
        val a = adapter
        if (a == null || !a.isEnabled) {
            setState(LinkStatus.IDLE, "Turn Bluetooth on"); return
        }
        if (_state.value.status != LinkStatus.IDLE) return

        scanner = a.bluetoothLeScanner
        val filter = ScanFilter.Builder().setDeviceName(UranProtocol.DEVICE_NAME).build()
        val settings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        setState(LinkStatus.SCANNING, "Scanning for ${UranProtocol.DEVICE_NAME}")
        scanner?.startScan(listOf(filter), settings, scanCallback)
        main.postDelayed(scanTimeout, 15_000)
    }

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            setState(LinkStatus.IDLE, "${UranProtocol.DEVICE_NAME} not found")
        }
    }

    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (scanning) {
            scanning = false
            if (hasPermission()) scanner?.stopScan(scanCallback)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            setState(LinkStatus.CONNECTING, "Connecting...")
            gatt = result.device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            main.removeCallbacks(scanTimeout)
            setState(LinkStatus.IDLE, "Scan failed (code $errorCode)")
        }
    }

    override fun disconnect() {
        stopScan()
        val g = gatt
        if (g != null) {
            if (hasPermission()) g.disconnect() else cleanup(g)
        }
        setState(LinkStatus.IDLE, "Disconnected")
    }

    private fun cleanup(g: BluetoothGatt) {
        try {
            g.close()
        } catch (_: Exception) {
        }
        if (gatt !== g) return
        gatt = null
        rxChar = null
        synchronized(rxBuffer) { rxBuffer.clear() }
        synchronized(lock) {
            writing = false
            currentWrite = null
            pendingControl = null
            pendingSafety.clear()
            pendingCommands.clear()
        }
    }

    private fun waitForPairing(g: BluetoothGatt, deadline: Long) {
        if (gatt !== g || _state.value.status != LinkStatus.CONNECTING) return
        when {
            g.device.bondState == BluetoothDevice.BOND_BONDED ->
                setState(LinkStatus.READY, "Paired to ${UranProtocol.DEVICE_NAME}")
            android.os.SystemClock.elapsedRealtime() >= deadline -> {
                setState(LinkStatus.IDLE, "Pairing timed out; reconnect")
                g.disconnect()
            }
            else -> main.postDelayed({ waitForPairing(g, deadline) }, 250)
        }
    }

    // ------------------------------------------------------------ GATT
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                setState(LinkStatus.CONNECTING, "Connected, setting up...")
                // Packets can be up to 22 bytes, so ask for a bigger MTU than the default 23
                if (!g.requestMtu(185)) g.discoverServices()
            } else {
                cleanup(g)
                setState(
                    LinkStatus.IDLE,
                    if (status == BluetoothGatt.GATT_SUCCESS) "Disconnected" else "Disconnected (error $status)"
                )
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(UranProtocol.SERVICE)
            val rx = service?.getCharacteristic(UranProtocol.RX)
            val tx = service?.getCharacteristic(UranProtocol.TX)
            val cccd = tx?.getDescriptor(cccdUuid)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null || cccd == null) {
                setState(LinkStatus.IDLE, "NUS service/characteristics not found")
                g.disconnect()
                return
            }
            rxChar = rx
            g.setCharacteristicNotification(tx, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (gatt !== g || d.uuid != cccdUuid) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                setState(LinkStatus.CONNECTING, "Pair using the passkey shown on COMM USB")
                waitForPairing(g, android.os.SystemClock.elapsedRealtime() + 60_000)
            } else {
                setState(LinkStatus.IDLE, "Could not enable notifications")
                g.disconnect()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt !== g) return

            val completed = synchronized(lock) {
                val packet = currentWrite
                currentWrite = null
                writing = false
                packet
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                completed?.let {
                    _writes.tryEmit(it.trim())
                }

                pump()
            } else {
                _replies.tryEmit("ERR,BLE_WRITE,$status")
                g.disconnect()
            }
        }


        // Android 12 and older
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == UranProtocol.TX) c.value?.let { handleBytes(it) }
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (c.uuid == UranProtocol.TX) handleBytes(value)
        }
    }

    // Notifications can arrive in chunks: only emit complete lines ending in \n
    private fun handleBytes(bytes: ByteArray) {
        synchronized(rxBuffer) {
            rxBuffer.append(String(bytes, Charsets.UTF_8))
            while (true) {
                val i = rxBuffer.indexOf("\n")
                if (i < 0) break
                val line = rxBuffer.substring(0, i).trim()
                rxBuffer.delete(0, i + 1)
                if (line.isNotEmpty()) _replies.tryEmit(line)
            }
            if (rxBuffer.length > 512) rxBuffer.clear()
        }
    }

    // ------------------------------------------------------------ sending
    override fun sendControl(
        command: DroneCommand
    ): Boolean {
        if (!isReady()) return false

        synchronized(lock) {
            pendingControl = UranProtocol.control(command)
        }

        pump()
        return true
    }

    override fun sendCommand(command: String): Boolean {
        val packet = UranProtocol.command(command)
            ?: return false

        if (packet == "MOTOR_TEST\n" || packet == "PREFLIGHT\n") return false

        if (!isReady()) return false

        synchronized(lock) {
            if (pendingCommands.size >= maxPendingCommands) {
                return false
            }

            pendingCommands.addLast(packet)
        }

        pump()
        return true
    }

    override fun sendDisarm(): Boolean =
        sendSafety(UranProtocol.DISARM)

    override fun sendEstop(): Boolean =
        sendSafety(UranProtocol.ESTOP)

    private fun sendSafety(text: String): Boolean {
        if (!isReady()) return false

        synchronized(lock) {
            pendingControl = null
            pendingCommands.clear()

            if (text == UranProtocol.ESTOP) {
                pendingSafety.addFirst(text)
            } else {
                pendingSafety.addLast(text)
            }
        }

        pump()
        return true
    }

    private fun pump() {
        val g = gatt ?: return
        val c = rxChar ?: return

        val packet: String = synchronized(lock) {
            if (writing) return

            val next =
                pendingSafety.removeFirstOrNull()
                    ?: pendingCommands.removeFirstOrNull()
                    ?: pendingControl.also {
                        pendingControl = null
                    }
                    ?: return

            writing = true
            currentWrite = next

            writeStartedAt =
                android.os.SystemClock.elapsedRealtime()

            next
        }

        if (!doWrite(g, c, packet)) {
            synchronized(lock) {
                writing = false
                currentWrite = null
            }

            _replies.tryEmit("ERR,BLE_WRITE_START")
            g.disconnect()
        }
    }

    private fun doWrite(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        text: String
    ): Boolean {
        if (!hasPermission()) return false

        val bytes = text.toByteArray(Charsets.UTF_8)

        val type =
            if (
                (c.properties and
                        BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            ) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            g.writeCharacteristic(c, bytes, type) ==
                    BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = bytes
            g.writeCharacteristic(c)
        }
    }
}
