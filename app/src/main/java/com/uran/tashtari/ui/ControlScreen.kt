package com.uran.tashtari.ui

import com.uran.tashtari.camera.CameraPreview
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uran.tashtari.model.DroneCommand
import com.uran.tashtari.network.FakeCommandSender

private val PanelColor = Color(0xFF131C2E)
private val LineColor = Color(0xFF22304A)
private val Muted = Color(0xFF94A3B8)
private val Danger = Color(0xFFEF4444)
private val Ok = Color(0xFF22C55E)
private val Warn = Color(0xFFF59E0B)

@Composable
fun ControlScreen(modifier: Modifier = Modifier) {
    val sender = remember { FakeCommandSender() }
    var seq by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf("MANUAL") }
    var throttle by remember { mutableFloatStateOf(0f) }
    var stopped by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }

    fun addLog(line: String) {
        log.add(0, line)
        if (log.size > 50) log.removeAt(log.lastIndex)
    }

    fun send(roll: Int = 0, pitch: Int = 0, yaw: Int = 0) {
        seq++
        val cmd = DroneCommand(seq, mode, roll, pitch, yaw, throttle.toInt())
        addLog(sender.send(cmd))
    }

    val manualEnabled = !stopped && mode == "MANUAL"

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
                Text("Flight control (simulation)", fontSize = 12.sp, color = Muted)
            }
            StatusPill(
                text = if (stopped) "E-STOP" else "LINK: MOCK",
                color = if (stopped) Danger else Warn
            )
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
            // When Stage 2 is done, replace this Text with:
            // CameraPreview(url = "http://192.168.4.1/stream", modifier = Modifier.fillMaxSize())
            CameraPreview(url = "http://192.168.4.1/stream", modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                StatusPill("CAMERA OFFLINE", Muted)
            }
        }

        // ---------- Mode ----------
        ModeToggle(mode = mode, enabled = !stopped) {
            mode = it
            addLog("MODE -> $it")
        }
        if (mode == "AUTO" && !stopped) {
            Text(
                "AUTO: target following (simulated). Manual controls are locked.",
                color = Warn,
                fontSize = 12.sp
            )
        }

        // ---------- Direction pad ----------
        Panel {
            SectionLabel("MOVE")
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HoldButton("▲", manualEnabled, Modifier.size(72.dp), { send(pitch = 30) }, { send() })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoldButton("◀", manualEnabled, Modifier.size(72.dp), { send(roll = -30) }, { send() })
                    Spacer(Modifier.size(72.dp))
                    HoldButton("▶", manualEnabled, Modifier.size(72.dp), { send(roll = 30) }, { send() })
                }
                HoldButton("▼", manualEnabled, Modifier.size(72.dp), { send(pitch = -30) }, { send() })
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HoldButton("YAW L", manualEnabled, Modifier.weight(1f).height(52.dp), { send(yaw = -30) }, { send() })
                HoldButton("YAW R", manualEnabled, Modifier.weight(1f).height(52.dp), { send(yaw = 30) }, { send() })
            }
        }

        // ---------- Throttle ----------
        Panel {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("THROTTLE")
                Text(
                    "${throttle.toInt()}%",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (manualEnabled) MaterialTheme.colorScheme.primary else Muted
                )
            }
            Slider(
                value = throttle,
                onValueChange = { throttle = it },
                onValueChangeFinished = { send() },
                valueRange = 0f..100f,
                enabled = manualEnabled,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = LineColor,
                    disabledThumbColor = Color(0xFF475569),
                    disabledActiveTrackColor = Color(0xFF334155),
                    disabledInactiveTrackColor = LineColor
                )
            )
        }

        // ---------- Emergency stop ----------
        Button(
            onClick = {
                if (stopped) {
                    stopped = false
                    addLog("RESET")
                } else {
                    stopped = true
                    throttle = 0f
                    send()
                    addLog("EMERGENCY STOP")
                }
            },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (stopped) Ok else Danger,
                contentColor = Color.White
            )
        ) {
            Text(
                if (stopped) "RESET" else "EMERGENCY STOP",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // ---------- Log ----------
        Panel {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("LOG")
                Text(
                    "Clear",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { log.clear() }
                )
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
                    Text("No commands yet", color = Color(0xFF475569), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                log.take(8).forEach {
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

@Composable
private fun ModeToggle(mode: String, enabled: Boolean, onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelColor)
            .border(1.dp, LineColor, RoundedCornerShape(14.dp))
            .padding(4.dp)
    ) {
        listOf("MANUAL", "AUTO").forEach { m ->
            val selected = mode == m
            val accent = if (m == "AUTO") Warn else MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) accent else Color.Transparent)
                    .clickable(enabled = enabled) { onChange(m) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    m,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) Color(0xFF04121A) else Muted
                )
            }
        }
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