package com.uran.tashtari.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.uran.tashtari.camera.CameraPreview
import com.uran.tashtari.model.DroneCommand
import com.uran.tashtari.network.*
import kotlinx.coroutines.delay

private val PanelColor = Color(0xFF131C2E)
private val LineColor = Color(0xFF22304A)
private val Muted = Color(0xFF94A3B8)
private val Danger = Color(0xFFEF4444)
private val Ok = Color(0xFF22C55E)
private val Warn = Color(0xFFF59E0B)

private const val STEP = 20   // v0.1 limit is -20..+20

@Composable
fun ControlScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sender: CommandSender = remember { BleCommandSender(context.applicationContext) }
    // For UI work without hardware, use this line instead:
    // val sender: CommandSender = remember { FakeCommandSender() }

    val link by sender.state.collectAsState()
    var roll by remember { mutableIntStateOf(0) }
    var pitch by remember { mutableIntStateOf(0) }
    var yaw by remember { mutableIntStateOf(0) }
    var stopped by remember { mutableStateOf(false) }
    var lastReply by remember { mutableStateOf("-") }
    val log = remember { mutableStateListOf<String>() }

    fun addLog(line: String) {
        log.add(0, line)
        if (log.size > 50) log.removeAt(log.lastIndex)
    }

    // ---------- permissions ----------
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) sender.connect() else addLog("Bluetooth permission denied")
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

    // ---------- replies and link changes ----------
    LaunchedEffect(sender) {
        sender.replies.collect {
            lastReply = it
            addLog("COMM: $it")
        }
    }
    LaunchedEffect(link) { addLog("LINK: ${link.message}") }

    // ---------- 100 ms control stream (only while connected and not e-stopped) ----------
    val manualEnabled = link.status == LinkStatus.READY && !stopped

    LaunchedEffect(manualEnabled) {
        if (!manualEnabled) return@LaunchedEffect
        var seq = 0
        var lastLogged = Triple(Int.MIN_VALUE, 0, 0)
        while (true) {
            seq = (seq + 1) and 0xFF
            val cmd = DroneCommand(seq, roll, pitch, yaw)
            sender.sendControl(cmd)
            val axes = Triple(roll, pitch, yaw)
            if (axes != lastLogged) {            // log changes only, not all 10 packets per second
                addLog("TX ${UranProtocol.control(cmd).trim()}")
                lastLogged = axes
            }
            delay(100)
        }
    }

    // ---------- safety when the app leaves the foreground ----------
    DisposableEffect(context) {
        val activity = context as ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { roll = 0; pitch = 0; yaw = 0 }
            if (event == Lifecycle.Event.ON_STOP) sender.disconnect()
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            sender.disconnect()
        }
    }

    val (pillText, pillColor) = when {
        stopped -> "E-STOP" to Danger
        link.status == LinkStatus.READY -> "BLE READY" to Ok
        link.status == LinkStatus.IDLE -> "BLE OFF" to Muted
        else -> "BLE ${link.status.name}" to Warn
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------- Header ----------
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "URAN TASHTRI",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("BLE Simulation v0.1 - no motors", fontSize = 12.sp, color = Muted)
            }
            StatusPill(pillText, pillColor)
        }

        // ---------- Video ----------
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black)
                .border(1.dp, LineColor, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            // Replace this Text with your CameraPreview(...) when video is ready
            CameraPreview(url = "http://192.168.4.1/stream", modifier = Modifier.fillMaxSize())
        }

        // ---------- Connection ----------
        Panel {
            SectionLabel("COMM LINK")
            Spacer(Modifier.height(8.dp))
            Text(link.message, color = Color.White, fontSize = 14.sp)
            Text("Last reply: $lastReply", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (link.status == LinkStatus.IDLE) connectWithPermission() else sender.disconnect()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (link.status == LinkStatus.IDLE) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary,
                    contentColor = if (link.status == LinkStatus.IDLE) Color(0xFF04121A) else Color.White
                )
            ) {
                Text(
                    when (link.status) {
                        LinkStatus.IDLE -> "CONNECT TO ${UranProtocol.DEVICE_NAME}"
                        LinkStatus.READY -> "DISCONNECT"
                        else -> "CANCEL"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ---------- Mode ----------
        ModeToggle()

        // ---------- Direction pad ----------
        Panel {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("MOVE")
                Text(
                    "R $roll  P $pitch  Y $yaw",
                    color = if (manualEnabled) MaterialTheme.colorScheme.primary else Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HoldButton("▲", manualEnabled, Modifier.size(72.dp), { pitch = STEP }, { pitch = 0 })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoldButton("◀", manualEnabled, Modifier.size(72.dp), { roll = -STEP }, { roll = 0 })
                    Spacer(Modifier.size(72.dp))
                    HoldButton("▶", manualEnabled, Modifier.size(72.dp), { roll = STEP }, { roll = 0 })
                }
                HoldButton("▼", manualEnabled, Modifier.size(72.dp), { pitch = -STEP }, { pitch = 0 })
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HoldButton("YAW L", manualEnabled, Modifier.weight(1f).height(52.dp), { yaw = -STEP }, { yaw = 0 })
                HoldButton("YAW R", manualEnabled, Modifier.weight(1f).height(52.dp), { yaw = STEP }, { yaw = 0 })
            }
            Spacer(Modifier.height(8.dp))
            Text("Throttle: reserved in v0.1 (always 0)", color = Muted, fontSize = 12.sp)
        }

        // ---------- Safety ----------
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    roll = 0; pitch = 0; yaw = 0
                    addLog(if (sender.sendDisarm()) "TX DISARM" else "DISARM NOT SENT (not connected)")
                },
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Warn, contentColor = Color(0xFF1A1200))
            ) { Text("DISARM", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

            Button(
                onClick = {
                    if (stopped) {
                        stopped = false
                        addLog("APP RESET (app side only)")
                    } else {
                        roll = 0; pitch = 0; yaw = 0
                        stopped = true
                        addLog(if (sender.sendEstop()) "TX ESTOP" else "ESTOP NOT SENT (not connected)")
                    }
                },
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (stopped) Ok else Danger,
                    contentColor = Color.White
                )
            ) {
                Text(if (stopped) "RESET" else "E-STOP", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (stopped) {
            Text("E-STOP sent. Controls locked. RESET unlocks the app only.", color = Danger, fontSize = 12.sp)
        }

        // ---------- Log ----------
        Panel {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("LOG")
                Text("Clear", color = Muted, fontSize = 12.sp, modifier = Modifier.clickable { log.clear() })
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF070C16))
                    .padding(10.dp)
            ) {
                if (log.isEmpty()) {
                    Text("No messages yet", color = Color(0xFF475569), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                log.take(12).forEach {
                    Text(it, color = Ok, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(PanelColor)
            .border(1.dp, LineColor, RoundedCornerShape(20.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// AUTO is not implemented in v0.1, so it is shown but disabled
@Composable
private fun ModeToggle() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelColor)
            .border(1.dp, LineColor, RoundedCornerShape(14.dp))
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) { Text("MANUAL", fontWeight = FontWeight.Bold, color = Color(0xFF04121A)) }
        Box(
            Modifier.weight(1f).padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) { Text("AUTO (later)", fontWeight = FontWeight.Bold, color = Color(0xFF475569)) }
    }
}

@Composable
private fun HoldButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    val background = when {
        !enabled -> Color(0xFF1A2236)
        pressed -> accent
        else -> Color(0xFF1F2A44)
    }
    val foreground = when {
        !enabled -> Color(0xFF475569)
        pressed -> Color(0xFF04121A)
        else -> accent
    }
    Box(
        modifier
            .clip(shape)
            .background(background)
            .border(1.dp, if (enabled) accent.copy(alpha = 0.4f) else Color.Transparent, shape)
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    if (enabled) {
                        pressed = true
                        onPress()
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            onRelease()
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = foreground, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}