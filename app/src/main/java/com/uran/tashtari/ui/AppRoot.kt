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
import com.uran.tashtari.network.LinkStatus
import com.uran.tashtari.network.UranProtocol

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val state = remember {
        UranAppState(
            BleCommandSender(
                context.applicationContext
            )
        )
    }

    val sender = state.sender
    val link by sender.state.collectAsState()

    val scope = rememberCoroutineScope()

    var tab by remember {
        mutableIntStateOf(0)
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts
                .RequestMultiplePermissions()
        ) { result ->

            if (result.values.all { it }) {
                sender.connect()
            } else {
                state.addLog(
                    "ERR",
                    "Bluetooth permission denied"
                )
            }
        }

    fun connectWithPermission() {
        val needed =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {
                arrayOf(
                    Manifest.permission
                        .BLUETOOTH_SCAN,
                    Manifest.permission
                        .BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(
                    Manifest.permission
                        .ACCESS_FINE_LOCATION
                )
            }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(
                context,
                it
            ) !=
                    PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            sender.connect()
        } else {
            permissionLauncher.launch(
                missing.toTypedArray()
            )
        }
    }

    LaunchedEffect(sender) {
        sender.replies.collect {
            state.handleReply(it)
        }
    }

    LaunchedEffect(sender) {
        sender.writes.collect {
            state.noteBleWrite(it)
        }
    }

    LaunchedEffect(link) {
        state.addLog(
            "BLE",
            link.message
        )

        if (link.status == LinkStatus.READY) {
            state.resetLinkStats()
        } else {
            state.neutralAll()
            state.throttleTransmitted = 0

            state.invalidatePreparation(
                "BLE link lost; preparation invalid"
            )

            state.cellsVerified = false
            state.benchAreaVerified = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            state.now =
                SystemClock.elapsedRealtime()

            if (
                state.preparePhase ==
                UranAppState.PreparePhase.READY &&
                !state.canBenchReady()
            ) {
                state.invalidatePreparation(
                    "Readiness expired or check failed"
                )
            }

            delay(250)
        }
    }

    val manualEnabled =
        link.status == LinkStatus.READY &&
                !state.stopped &&
                state.preparePhase !=
                UranAppState.PreparePhase.PREPARING

    LaunchedEffect(manualEnabled) {
        if (!manualEnabled) {
            return@LaunchedEffect
        }

        var seq = 0

        var lastLogged =
            Triple(Int.MIN_VALUE, 0, 0)

        while (true) {
            seq = (seq + 1) and 0xFF

            val cmd = DroneCommand(
                seq = seq,
                roll = state.roll,
                pitch = state.pitch,
                yaw = state.yaw,
                throttle = state.throttleSelected
            )

            val accepted =
                sender.sendControl(cmd)

            val axes = Triple(
                state.roll,
                state.pitch,
                state.yaw
            )

            if (
                accepted &&
                axes != lastLogged
            ) {
                state.addLog(
                    "QUEUE",
                    UranProtocol.control(cmd).trim()
                )

                lastLogged = axes
            }

            delay(100)
        }
    }

    LaunchedEffect(tab) {
        if (tab != 0) {
            state.neutralAll()
        }
    }

    DisposableEffect(context) {
        val activity =
            context as ComponentActivity

        val observer = LifecycleEventObserver {
                _, event ->

            if (event == Lifecycle.Event.ON_PAUSE) {
                state.neutralAll()
                state.invalidatePreparation(
                    "App left foreground"
                )
            }

            if (event == Lifecycle.Event.ON_STOP) {
                sender.disconnect()
            }
        }

        activity.lifecycle.addObserver(
            observer
        )

        onDispose {
            activity.lifecycle.removeObserver(
                observer
            )

            sender.disconnect()
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ControlScreen(
                    state = state,
                    onConnect = {
                        connectWithPermission()
                    },
                    onPrepare = {
                        scope.launch {
                            runPrepare(state)
                        }
                    }
                )

                1 -> CameraScreen()

                else -> DiagnosticsScreen(
                    state
                )
            }
        }

        BottomTabs(tab) {
            tab = it
        }
    }
}

private suspend fun runPrepare(
    state: UranAppState
) = coroutineScope {

    val sender = state.sender

    if (
        sender.state.value.status !=
        LinkStatus.READY ||
        state.stopped
    ) {
        state.preparePhase =
            UranAppState.PreparePhase.FAILED

        state.prepareMessage =
            "BLE disconnected or E-STOP locked"

        return@coroutineScope
    }

    if (
        state.preparePhase ==
        UranAppState.PreparePhase.PREPARING
    ) {
        return@coroutineScope
    }

    if (
        !state.cellsVerified ||
        !state.benchAreaVerified
    ) {
        state.preparePhase =
            UranAppState.PreparePhase.FAILED

        state.prepareMessage =
            "Complete physical safety confirmations"

        return@coroutineScope
    }

    val token = ++state.prepareToken

    state.neutralAll()

    state.preparePhase =
        UranAppState.PreparePhase.PREPARING

    state.prepareMessage =
        "Starting bench checks"

    fun fail(reason: String) {
        state.preparePhase =
            UranAppState.PreparePhase.FAILED

        state.prepareMessage = reason

        state.addLog(
            "ERR",
            "PREPARE: $reason"
        )
    }

    suspend fun commandStep(
        name: String,
        send: () -> Boolean,
        accepted: (String) -> Boolean
    ): Boolean {

        if (
            token != state.prepareToken ||
            state.stopped ||
            sender.state.value.status !=
            LinkStatus.READY
        ) {
            fail("$name cancelled")
            return false
        }

        state.prepareMessage =
            "Waiting for $name"

        // Subscribe before sending command.
        val response = async(
            start = CoroutineStart.UNDISPATCHED
        ) {
            withTimeoutOrNull(4500L) {
                sender.replies.first { line ->
                    line.startsWith("ERR") ||
                            accepted(line)
                }
            }
        }

        if (!send()) {
            response.cancel()
            fail(
                "$name could not be queued"
            )
            return false
        }

        val line = response.await()

        if (
            token != state.prepareToken ||
            state.stopped ||
            sender.state.value.status !=
            LinkStatus.READY
        ) {
            fail("$name cancelled")
            return false
        }

        if (line == null) {
            fail(
                "$name timed out: no Flight ACK"
            )
            return false
        }

        if (line.startsWith("ERR")) {
            fail(
                "$name rejected: $line"
            )
            return false
        }

        state.addLog(
            "PREP",
            "$name confirmed: $line"
        )

        return true
    }

    if (
        !commandStep(
            "DISARM",
            send = {
                sender.sendDisarm()
            },
            accepted = {
                it == "ACK,DISARMED"
            }
        )
    ) {
        return@coroutineScope
    }

    if (
        !commandStep(
            "SIM_OFF",
            send = {
                sender.sendCommand(
                    "SIM_OFF"
                )
            },
            accepted = {
                it == "ACK,SIM_OFF" ||
                        it == "ACK,SIMULATION_OFF"
            }
        )
    ) {
        return@coroutineScope
    }

    if (
        !commandStep(
            "LEVEL",
            send = {
                sender.sendCommand(
                    "LEVEL"
                )
            },
            accepted = {
                it == "ACK,LEVEL" ||
                        it == "ACK,LEVEL_OK" ||
                        it == "ACK,LEVEL_CALIBRATED"
            }
        )
    ) {
        return@coroutineScope
    }

    val statusRequestedAt =
        SystemClock.elapsedRealtime()

    if (
        !commandStep(
            "STATUS",
            send = {
                sender.sendCommand(
                    "STATUS"
                )
            },
            accepted = {
                it.startsWith(
                    "STATE,MPU="
                )
            }
        )
    ) {
        return@coroutineScope
    }

    delay(100)

    if (
        token != state.prepareToken ||
        state.stopped
    ) {
        fail(
            "Preparation interrupted"
        )
        return@coroutineScope
    }

    if (
        state.flight.lastStateMs <
        statusRequestedAt ||
        !state.canBenchReady()
    ) {
        fail(
            "Fresh checks incomplete: verify " +
                    "MPU, key, battery, LEVEL, mode and link"
        )
        return@coroutineScope
    }

    state.preparePhase =
        UranAppState.PreparePhase.READY

    state.prepareMessage =
        "BENCH PREPARED — NOT FLIGHT READY"

    state.addLog(
        "PREP",
        "Bench checks passed; motors not armed"
    )
}
