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
import kotlinx.coroutines.delay

@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = remember { UranAppState(BleCommandSender(context.applicationContext)) }
    val sender = state.sender
    val link by sender.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    // ---------- permissions ----------
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) sender.connect()
        else state.addLog("ERR", "Bluetooth permission denied")
    }

    fun connectWithPermission() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) sender.connect() else permissionLauncher.launch(missing.toTypedArray())
    }

    // ---------- COMM replies ----------
    LaunchedEffect(sender) { sender.replies.collect { state.handleReply(it) } }

    // ---------- link changes ----------
    LaunchedEffect(link) {
        state.addLog("BLE", link.message)
        if (link.status == LinkStatus.READY) state.resetLinkStats() else state.neutralAll()
    }

    // ---------- clock for staleness checks ----------
    LaunchedEffect(Unit) {
        while (true) {
            state.now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    // ---------- 100 ms control stream, on every tab ----------
    val manualEnabled = link.status == LinkStatus.READY && !state.stopped
    LaunchedEffect(manualEnabled) {
        if (!manualEnabled) return@LaunchedEffect
        var seq = 0
        var lastLogged = Triple(Int.MIN_VALUE, 0, 0)
        while (true) {
            seq = (seq + 1) and 0xFF
            val cmd = DroneCommand(seq, state.roll, state.pitch, state.yaw)
            state.noteRcSent(seq)
            sender.sendControl(cmd)
            val axes = Triple(state.roll, state.pitch, state.yaw)
            if (axes != lastLogged) {              // log changes only, never every heartbeat
                state.addLog("TX", UranProtocol.control(cmd).trim())
                lastLogged = axes
            }
            delay(100)
        }
    }

    // ---------- leaving the Control tab returns the sticks to neutral ----------
    LaunchedEffect(tab) { if (tab != 0) state.neutralAll() }

    // ---------- backgrounding: neutral, then disconnect ----------
    DisposableEffect(context) {
        val activity = context as ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) state.neutralAll()
            if (event == Lifecycle.Event.ON_STOP) sender.disconnect()
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            sender.disconnect()
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ControlScreen(state, onConnect = { connectWithPermission() })
                1 -> CameraScreen()
                else -> DiagnosticsScreen(state)
            }
        }
        BottomTabs(tab) { tab = it }
    }
}