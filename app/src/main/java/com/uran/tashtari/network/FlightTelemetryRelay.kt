package com.uran.tashtari.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TelemetryRelayStatus(val active: Boolean, val message: String)

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class FlightTelemetryRelay(private val context: Context) : AutoCloseable {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val _status = MutableStateFlow(TelemetryRelayStatus(false, "Laptop telemetry off"))
    val status = _status.asStateFlow()

    private data class Sample(val line: String, val receivedAt: Long)

    private val attitude = AtomicReference<Sample?>()
    private val outputs = AtomicReference<Sample?>()
    private val stopFrame = AtomicReference<Sample?>()
    @Volatile private var subscribed = false

    private var generation = 0
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisement: AdvertiseCallback? = null
    private var output: BluetoothGattCharacteristic? = null
    private var observer: BluetoothDevice? = null
    private var publishTask: ScheduledFuture<*>? = null
    private var notificationStartedAt: Long? = null
    private var preferOutputs = false
    private var bluetoothName: String? = null

    fun offer(line: String) {
        if (!subscribed) return
        val slot = when {
            line.startsWith("AT,") -> attitude
            line.startsWith("PWM,") -> outputs
            line.startsWith("STOP,") || line.startsWith("FLIGHT_STOP_REASON,") -> stopFrame
            else -> return
        }
        slot.set(Sample(line, SystemClock.elapsedRealtime()))
    }

    fun clearSamples() {
        attitude.set(null)
        outputs.set(null)
        stopFrame.set(null)
    }

    fun start() = submit {
        if (server != null) return@submit
        try {
            val adapter = manager?.adapter
            advertiser = adapter?.bluetoothLeAdvertiser
            if (advertiser == null) {
                setStatus(false, "Turn on Bluetooth or use a phone that supports BLE advertising.")
                return@submit
            }
            val token = ++generation
            bluetoothName = adapter?.name?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 23 }
            setStatus(true, "Starting laptop telemetry…")
            val stream = BluetoothGattCharacteristic(
                STREAM, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0
            )
            stream.addDescriptor(BluetoothGattDescriptor(
                CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
            output = stream
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(stream)
            val opened = manager?.openGattServer(context, callbacks(token))
            server = opened
            if (opened == null || !opened.addService(service)) {
                stopInternal("Could not start the phone telemetry service.")
                return@submit
            }
            publishTask = worker.scheduleWithFixedDelay({
                try {
                    publish()
                } catch (exception: RuntimeException) {
                    stopInternal("Laptop telemetry stopped. Start it again from Diagnostics.")
                }
            }, 100L, 100L, TimeUnit.MILLISECONDS)
        } catch (exception: RuntimeException) {
            stopInternal("Bluetooth telemetry unavailable. Check Nearby devices permissions.")
        }
    }

    fun stop() = submit { stopInternal("Laptop telemetry off") }

    override fun close() {
        stop()
        worker.shutdown()
    }

    private fun submit(action: () -> Unit) {
        try {
            worker.execute(action)
        } catch (_: RejectedExecutionException) {
            // An Android callback may arrive during disposal.
        }
    }

    private fun ifCurrent(token: Int, action: () -> Unit) = submit {
        if (token != generation || server == null) return@submit
        try {
            action()
        } catch (_: RuntimeException) {
            stopInternal("Laptop Bluetooth telemetry stopped.")
        }
    }

    private fun setStatus(active: Boolean, message: String) {
        _status.value = TelemetryRelayStatus(active, message)
    }

    private fun stopAdvertising() {
        val previous = advertisement
        advertisement = null
        if (previous != null) advertiser?.stopAdvertising(previous)
    }

    private fun stopInternal(message: String) {
        generation++
        subscribed = false
        clearSamples()
        publishTask?.cancel(false)
        publishTask = null
        runCatching { stopAdvertising() }
        runCatching { server?.close() }
        server = null
        output = null
        observer = null
        advertiser = null
        notificationStartedAt = null
        setStatus(false, message)
    }

    private fun advertise(token: Int) {
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = ifCurrent(token) {
                if (advertisement === this && observer == null) {
                    setStatus(true, "Ready for laptop. Select ${bluetoothName ?: "this phone"} in Chrome.")
                }
            }

            override fun onStartFailure(errorCode: Int) = ifCurrent(token) {
                if (advertisement === this) stopInternal("BLE advertising failed ($errorCode).")
            }
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(bluetoothName != null).build()
        advertisement = callback
        advertiser?.startAdvertising(settings, data, scanResponse, callback)
    }

    private fun callbacks(token: Int) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) = ifCurrent(token) {
            if (service.uuid != SERVICE) return@ifCurrent
            if (status == BluetoothGatt.GATT_SUCCESS) advertise(token)
            else stopInternal("Could not register the telemetry service ($status).")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) =
            ifCurrent(token) {
                // The phone also runs the pilot GATT client. Connection events alone
                // do not identify a viewer of this service.
                if (newState != BluetoothProfile.STATE_DISCONNECTED || device != observer) return@ifCurrent
                observer = null
                subscribed = false
                clearSamples()
                notificationStartedAt = null
                advertise(token)
            }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
        ) = ifCurrent(token) {
            val value = if (device == observer && subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            val status = if (descriptor.uuid != CCCD || descriptor.characteristic.uuid != STREAM)
                BluetoothGatt.GATT_FAILURE
                else if (offset !in 0..value.size) BluetoothGatt.GATT_INVALID_OFFSET
                else BluetoothGatt.GATT_SUCCESS
            server?.sendResponse(device, requestId, status, offset,
                if (status == BluetoothGatt.GATT_SUCCESS) value.copyOfRange(offset, value.size) else null)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) = ifCurrent(token) {
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val ownsSubscription = device == observer
            val canSubscribe = enable && (observer == null || ownsSubscription)
            val accepted = (canSubscribe || (disable && ownsSubscription)) && descriptor.uuid == CCCD &&
                descriptor.characteristic.uuid == STREAM && !preparedWrite && offset == 0
            if (responseNeeded) server?.sendResponse(device, requestId,
                if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            if (!accepted) return@ifCurrent
            if (enable) {
                observer = device
                subscribed = true
                stopAdvertising()
                setStatus(true, "Laptop telemetry linked.")
            } else {
                observer = null
                subscribed = false
                clearSamples()
                notificationStartedAt = null
                advertise(token)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = ifCurrent(token) {
            if (device == observer) notificationStartedAt = null
        }
    }

    private fun publish() {
        val device = observer ?: return
        if (!subscribed) return
        val gatt = server ?: return
        val characteristic = output ?: return
        val now = SystemClock.elapsedRealtime()
        val inFlight = notificationStartedAt
        if (inFlight != null) {
            if (now - inFlight >= 1500) {
                stopInternal("Laptop notifications timed out. Restart laptop telemetry.")
            }
            return
        }

        val sample = stopFrame.getAndSet(null) ?: if (preferOutputs) {
            outputs.getAndSet(null) ?: attitude.getAndSet(null)
        } else {
            attitude.getAndSet(null) ?: outputs.getAndSet(null)
        }
        if (sample == null || now - sample.receivedAt > 1000) return
        preferOutputs = !preferOutputs
        val data = TelemetryRelayFrame.encode(sample.line) ?: return
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.notifyCharacteristicChanged(device, characteristic, false, data) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = data
            gatt.notifyCharacteristicChanged(device, characteristic, false)
        }
        if (queued) notificationStartedAt = now
    }

    companion object {
        private val SERVICE = UUID.fromString("75b3fb30-729e-4b3a-89c7-15c424e89d01")
        private val STREAM = UUID.fromString("75b3fb30-729e-4b3a-89c7-15c424e89d02")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
