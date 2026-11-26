package com.uran.tashtari.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.uran.tashtari.model.DroneCommand
import com.uran.tashtari.network.BleCommandSender
import com.uran.tashtari.network.FlightTelemetryRelay
import com.uran.tashtari.network.LinkStatus
import com.uran.tashtari.network.UranProtocol
import kotlinx.coroutines.delay

private const val FLIGHT_TAB = 0
private const val CAMERA_TAB = 1

@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val state = remember { UranAppState(BleCommandSender(context.applicationContext)) }
    val sender = state.sender
    val link by sender.state.collectAsState()
    val relay = remember { FlightTelemetryRelay(context.applicationContext) }
    val relayStatus by relay.status.collectAsState()

    var tab by remember { mutableIntStateOf(FLIGHT_TAB) }
    var foreground by remember(activity) {
        mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.isNotEmpty() && result.values.all { it }) {
            sender.connect()
        } else {
            state.addLog("ERR", "Bluetooth permission denied")
        }
    }

    val relayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.isNotEmpty() && result.values.all { it } &&
            !state.flightArmed && !state.flightArmPending) {
            relay.start()
        } else {
            state.addLog("ERR", "Laptop telemetry permission denied or aircraft armed")
        }
    }

    fun toggleRelay() {
        if (state.flightArmed || state.flightArmPending) return
        if (relayStatus.active) {
            relay.stop()
            return
        }
        val missing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        } else emptyList()
        if (missing.isEmpty()) relay.start()
        else relayPermissionLauncher.launch(missing.toTypedArray())
    }

    fun connectWithPermission() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            sender.connect()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun leaveManualFlight() {
        if (state.flightTestSelected) {
            state.disarm()
        } else {
            state.neutralAll()
        }
    }

    LaunchedEffect(sender) {
        sender.replies.collect {
            state.handleReply(it)
            if (sender.state.value.status == LinkStatus.READY) relay.offer(it)
        }
    }

    LaunchedEffect(sender) {
        sender.writes.collect { state.noteBleWrite(it) }
    }

    LaunchedEffect(link) {
        state.addLog("BLE", link.message)
        if (link.status == LinkStatus.READY) {
            state.resetLinkStats()
        } else {
            state.disarm()
            state.resetLiveFlight("DISCONNECTED")
            relay.clearSamples()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            state.now = SystemClock.elapsedRealtime()
            state.checkFlightStatus(state.now)
            delay(250)
        }
    }

    val linkReady = link.status == LinkStatus.READY && !state.stopped
    LaunchedEffect(linkReady) {
        if (!linkReady) return@LaunchedEffect

        var seq = 0
        var lastLogged = Triple(Int.MIN_VALUE, 0, 0)

        while (true) {
            seq = (seq + 1) and 0xFF
            val manual = state.flightTestSelected && tab == FLIGHT_TAB && foreground && !state.stopped
            val cmd = DroneCommand(
                seq = seq,
                roll = if (manual) state.roll else 0,
                pitch = if (manual) state.pitch else 0,
                yaw = if (manual) state.yaw else 0,
                throttle = if (manual && state.flightArmed) state.throttleSelected else 0,
                flightMode = manual
            )
            val accepted = sender.sendControl(cmd)
            val axes = Triple(cmd.roll, cmd.pitch, cmd.yaw)
            if (accepted && axes != lastLogged) {
                state.addLog("QUEUE", UranProtocol.control(cmd).trim())
                lastLogged = axes
            }
            delay(if (manual) 50L else 100L)
        }
    }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground = true
                Lifecycle.Event.ON_PAUSE -> {
                    foreground = false
                    leaveManualFlight()
                }
                Lifecycle.Event.ON_STOP -> {
                    sender.disconnect()
                    relay.stop()
                }
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            sender.disconnect()
            relay.close()
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                FLIGHT_TAB -> ControlScreen(
                    state = state,
                    onConnect = { connectWithPermission() }
                )
                CAMERA_TAB -> CameraScreen()
                else -> DiagnosticsScreen(
                    state,
                    relayActive = relayStatus.active,
                    relayMessage = relayStatus.message,
                    onToggleRelay = { toggleRelay() }
                )
            }
        }
        BottomTabs(tab, navigationEnabled = !state.flightArmed && !state.flightArmPending) {
            if (state.flightArmed || state.flightArmPending) return@BottomTabs
            if (it != FLIGHT_TAB) leaveManualFlight()
            tab = it
        }
    }
}
