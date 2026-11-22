package com.uran.tashtari.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uran.tashtari.network.LinkStatus
import com.uran.tashtari.network.UranProtocol
import kotlin.math.max
import kotlin.math.roundToInt
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.platform.LocalContext


private const val AXIS_MAX = 20   // BLE v0.1 limit: -20..+20

@Composable
fun ControlScreen(
    state: UranAppState,
    onConnect: () -> Unit,
    onPrepare: () -> Unit,
    modifier: Modifier = Modifier
)
 {
     if (state.flightTestSelected) {
         FlightTestControls(state, modifier)
         return
     }

     val context = LocalContext.current

     var terminalExpanded by remember {
         mutableStateOf(false)
     }

     val flightMode =
         if (
             state.flight.legacyFresh(
                 state.now
             )
         ) {
             state.flight.flightMode
                 ?: "UNKNOWN"
         } else {
             "UNKNOWN"
         }

     val link by state.sender.state.collectAsState()
    val manualEnabled = link.status == LinkStatus.READY && !state.stopped
    val items = healthItems(state, link)

    Column(modifier.fillMaxSize()) {

        // ===== Pinned: always visible, never scrolls away =====
       
Column(
    Modifier
        .fillMaxWidth()
        .padding(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 8.dp
        ),
    verticalArrangement = Arrangement.spacedBy(10.dp)
) {
    TextButton(
        enabled = manualEnabled,
        onClick = {
            state.disarm()
            state.flightTestSelected = true
        }
    ) { Text("MANUAL FLIGHT TEST") }

    ScreenHeader(
        "Flight mode: $flightMode | throttle locked"
    ) {
        if (state.stopped) {
            StatusPill("UI LOCKED", Danger)
        } else {
            StatusPill(
                items[0].value,
                items[0].health.tint()
            )
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = { state.disarm() },
            modifier = Modifier
                .weight(1f)
                .height(60.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Warn,
                contentColor = Color(0xFF1A1200)
            )
        ) {
            Text(
                "DISARM",
                fontWeight = FontWeight.Bold
            )
        }

        Button(
            onClick = {
                if (state.stopped) {
                    state.resetUi()
                } else {
                    state.estop()
                }
            },
            modifier = Modifier
                .weight(1f)
                .height(60.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor =
                    if (state.stopped) {
                        Color(0xFF475569)
                    } else {
                        Danger
                    }
            )
        ) {
            Text(
                if (state.stopped) "RESET UI"
                else "E-STOP",
                fontWeight = FontWeight.Bold
            )
        }
    }

    if (state.stopped) {
        Text(
            "E-STOP locked. Physical Flight reset " +
                "and fresh telemetry required.",
            color = Danger,
            fontSize = 12.sp
        )
    }

    BatteryBanner(state)
}

        // ===== Scrollable area =====
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // ---------- Connection (compact) ----------
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("COMM LINK")
                        Text(link.message, color = Color.White, fontSize = 13.sp)
                        Text(
                            "Last reply: ${state.lastReply}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            if (link.status == LinkStatus.IDLE) onConnect() else state.sender.disconnect()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (link.status == LinkStatus.IDLE) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondary,
                            contentColor = if (link.status == LinkStatus.IDLE) Color(0xFF04121A) else Color.White
                        )
                    ) {
                        Text(
                            when (link.status) {
                                LinkStatus.IDLE -> "CONNECT"
                                LinkStatus.READY -> "DISCONNECT"
                                else -> "CANCEL"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    "Device: ${UranProtocol.DEVICE_NAME}",
                    color = DisabledGrey, fontSize = 10.sp
                )
            }

            // ---------- Joysticks ----------
            Panel {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("FLIGHT STICKS")
                    Text(
                        if (manualEnabled) "LIVE" else "LOCKED",
                        color = if (manualEnabled) Ok else Muted,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {

                    // Left: throttle (up) + yaw (left/right)
                    StickBlock(
                        title = "THROTTLE / YAW",
                        readout = "THR %3d%%\nYAW %+3d".format(state.throttleSelected, state.yaw),
                        enabled = manualEnabled,
                        modifier = Modifier.weight(1f),
                        onChange = { x, y ->
                            state.yaw = (x * AXIS_MAX).roundToInt()
                            // Up only: pushing down stays at 0, throttle is never negative.
                            // UI only. This value is never put into a BLE packet.
                            state.throttleSelected = (max(0f, y) * 100f).roundToInt()
                        },
                        onRelease = {
                            state.yaw = 0
                            state.throttleSelected = 0
                        }
                    )

                    // Right: pitch (up/down) + roll (left/right)
                    StickBlock(
                        title = "PITCH / ROLL",
                        readout = "PITCH %+3d\nROLL  %+3d".format(state.pitch, state.roll),
                        enabled = manualEnabled,
                        modifier = Modifier.weight(1f),
                        onChange = { x, y ->
                            state.roll = (x * AXIS_MAX).roundToInt()
                            state.pitch = (y * AXIS_MAX).roundToInt()
                        },
                        onRelease = {
                            state.roll = 0
                            state.pitch = 0
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Roll, pitch and yaw return to 0 on release. Throttle is shown on screen only and is NOT transmitted. " +
                            "Directions (right = roll +, up = pitch +, right = yaw +) are placeholders until Usman confirms the sign convention.",
                    color = DisabledGrey, fontSize = 11.sp
                )
            }

            
Panel {
    SectionLabel("BENCH PREPARATION")

    Spacer(Modifier.height(8.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = state.cellsVerified,
            onCheckedChange = {
                state.cellsVerified = it
            }
        )

        Text(
            "3S LiPo cells individually checked safe",
            color = Muted,
            fontSize = 11.sp
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = state.benchAreaVerified,
            onCheckedChange = {
                state.benchAreaVerified = it
            }
        )

        Text(
            "Propellers removed and area clear",
            color = Muted,
            fontSize = 11.sp
        )
    }

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = onPrepare,
        enabled =
            link.status == LinkStatus.READY &&
            !state.stopped &&
            state.cellsVerified &&
            state.benchAreaVerified &&
            state.preparePhase !=
                UranAppState.PreparePhase.PREPARING,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            "PREPARE DRONE",
            fontWeight = FontWeight.Bold
        )
    }

    val prepText =
        if (
            state.preparePhase ==
                UranAppState.PreparePhase.READY &&
            !state.canBenchReady()
        ) {
            "STALE: Repeat preparation"
        } else {
            "${state.preparePhase}: ${state.prepareMessage}"
        }

    Text(
        prepText,
        color = when (state.preparePhase) {
            UranAppState.PreparePhase.READY ->
                if (state.canBenchReady()) Ok else Warn

            UranAppState.PreparePhase.FAILED ->
                Danger

            else -> Muted
        },
        fontSize = 11.sp
    )
}


            // ---------- Health (real data or UNKNOWN) ----------
            items.chunked(3).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { HealthPill(it.label, it.value, it.health, Modifier.weight(1f)) }
                }
            }

            

            // ---------- Mode: AUTO is not implemented ----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PanelColor)
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
                ) { Text("AUTO (later)", fontWeight = FontWeight.Bold, color = DisabledGrey) }
            }
            BatteryRow(state)
            Panel {
                SectionLabel(
                    "THROTTLE + LIVE TX/RX"
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        Modifier.weight(1f)
                    ) {
                        Text(
                            "THROTTLE",
                            color = Muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            "Requested " +
                                    "${state.throttleSelected}%",
                            color = Color.White,
                            fontFamily =
                                FontFamily.Monospace,
                            fontSize = 11.sp
                        )

                        Text(
                            "Written " +
                                    "${state.throttleTransmitted}%",
                            color = Warn,
                            fontFamily =
                                FontFamily.Monospace,
                            fontSize = 11.sp
                        )

                        Text(
                            "LOCKED - BENCH ONLY",
                            color = Danger,
                            fontSize = 10.sp
                        )
                    }

                    Column(
                        Modifier.weight(1f)
                    ) {
                        Text(
                            "LIVE TX / RX",
                            color = Muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            "TX: " +
                                    (state.lastTx?.format() ?: "--"),
                            color = Ok,
                            fontSize = 10.sp,
                            maxLines = 2
                        )

                        Text(
                            "RX: " +
                                    (state.lastRx?.format() ?: "--"),
                            color = Color.White,
                            fontSize = 10.sp,
                            maxLines = 2
                        )

                        Text(
                            "ERR: " +
                                    (state.lastError?.text ?: "--"),
                            color = Danger,
                            fontSize = 10.sp,
                            maxLines = 2
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        terminalExpanded =
                            !terminalExpanded
                    }
                ) {
                    Text(
                        if (terminalExpanded) {
                            "Hide terminal"
                        } else {
                            "Show terminal"
                        }
                    )
                }

                if (terminalExpanded) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = {
                                val clipboard =
                                    context.getSystemService(
                                        ClipboardManager::class.java
                                    )

                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "URAN TASHTRI TX/RX",
                                        state.trafficText()
                                    )
                                )

                                Toast.makeText(
                                    context,
                                    "Terminal copied",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Text("Copy")
                        }

                        TextButton(
                            onClick = {
                                state.clearTraffic()
                            }
                        ) {
                            Text("Clear")
                        }
                    }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(
                                rememberScrollState()
                            )
                            .background(
                                Color(0xFF070C16)
                            )
                            .padding(8.dp)
                    ) {
                        if (
                            state.trafficLog.isEmpty()
                        ) {
                            Text(
                                "No TX/RX messages",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }

                        state.trafficLog
                            .take(150)
                            .forEach { entry ->
                                Text(
                                    entry.format(),
                                    color =
                                        if (entry.tag == "TX") {
                                            Ok
                                        } else {
                                            Muted
                                        },
                                    fontFamily =
                                        FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                    }
                }
            }

            // ---------- Throttle: four separate meanings, never mixed ----------
            Panel {
                SectionLabel("THROTTLE STATE")
                Spacer(Modifier.height(6.dp))
                ThrottleRow("Requested (stick, UI only)", "${state.throttleSelected} %")
                ThrottleRow(
                    "Written to COMM",
                    "${state.throttleTransmitted}% " +
                            "(physical throttle locked)",
                    Warn
                )
                ThrottleRow("Flight-confirmed (simulation)", "UNKNOWN", Muted)
                ThrottleRow("Physical motor throttle", "DISABLED", Danger)
            }

            // ---------- Simulation controls: disabled, nothing is sent ----------
            Panel {
                SectionLabel("SIMULATION")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PendingAction("LEVEL CALIBRATE", "Needs COMM relay and Flight ACK")
                    PendingAction("START SIMULATION", "Enabled only when Flight confirms MPU, calibration, fresh RC and no ESTOP")
                    PendingAction("STOP SIMULATION", "Waiting for a verified Flight stop command")
                }
            }

            Spacer(Modifier.height(8.dp))
       
     Panel {
         SectionLabel(
             "MOTOR TEST - FUTURE FIRMWARE"
         )

         Spacer(Modifier.height(8.dp))

         Text(
             "Disabled until COMM and Flight " +
                     "implement verified BLE authorization " +
                     "and motor-stop acknowledgements.",
             color = DisabledGrey,
             fontSize = 11.sp
         )

         Spacer(Modifier.height(8.dp))

                  Row(
             Modifier.fillMaxWidth(),
             horizontalArrangement =
                 Arrangement.spacedBy(4.dp)
         ) {
             listOf(
                 "FL", "FR", "RR", "RL", "ALL"
             ).forEach { motor ->
                 OutlinedButton(
                     onClick = {},
                     enabled = false,
                     modifier =
                         Modifier.weight(1f),
                     contentPadding =
                         PaddingValues(0.dp)
                 ) {
                     Text(
                         motor,
                         fontSize = 10.sp
                     )
                 }
             }
         }
     }       // closes Panel (MOTOR TEST)

    }        // closes the scrollable Column
   }         // closes Column(modifier.fillMaxSize())
}            // closes fun ControlScreen


@Composable
private fun FlightTestControls(state: UranAppState, modifier: Modifier = Modifier) {
    val link by state.sender.state.collectAsState()
    val connected = link.status == LinkStatus.READY
    val armed = state.flightArmed && connected && !state.stopped

    Column(modifier.fillMaxSize().padding(16.dp)) {
        ScreenHeader("Manual flight test") {
            StatusPill(
                if (state.flightArmPending) "ARMING" else state.liveStatus,
                if (armed) Warn else Muted
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { state.disarm() },
                modifier = Modifier.weight(1f)
            ) { Text("DISARM") }
            Button(
                onClick = { state.estop() },
                colors = ButtonDefaults.buttonColors(containerColor = Danger),
                modifier = Modifier.weight(1f)
            ) { Text("E-STOP") }
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = connected && !state.stopped && !armed && !state.flightArmPending,
                    onClick = { state.sender.sendCommand("LEVEL") }
                ) { Text("CALIBRATE LEVEL") }
                Button(
                    enabled = connected && !state.stopped && !armed && !state.flightArmPending &&
                        state.liveStatus == "READY",
                    onClick = { state.armFlight() }
                ) { Text("ARM FLIGHT") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StickBlock(
                    title = "YAW",
                    readout = "%+d".format(state.yaw),
                    enabled = armed,
                    modifier = Modifier.weight(1f),
                    onChange = { x, _ -> state.yaw = (x * AXIS_MAX).roundToInt() },
                    onRelease = { state.yaw = 0 }
                )
                StickBlock(
                    title = "PITCH / ROLL",
                    readout = "%+d / %+d".format(state.pitch, state.roll),
                    enabled = armed,
                    modifier = Modifier.weight(1f),
                    onChange = { x, y ->
                        state.roll = (x * AXIS_MAX).roundToInt()
                        state.pitch = (y * AXIS_MAX).roundToInt()
                    },
                    onRelease = {
                        state.roll = 0
                        state.pitch = 0
                    }
                )
            }
            Text("Throttle ${state.throttleSelected}%  |  BLE written ${state.throttleTransmitted}%")
            Slider(
                value = state.throttleSelected.toFloat(),
                onValueChange = { state.throttleSelected = it.roundToInt() },
                valueRange = 0f..100f,
                enabled = armed
            )
            Text(
                when (state.liveStatus) {
                    "CFG" -> "Verify the aircraft configuration on the FLIGHT ESP32."
                    "READY" -> "Keep throttle at zero and release both sticks before arming."
                    "ARMED" -> "Throttle holds its selected value when you lift your finger."
                    "FAULT" -> "Flight stopped. Check the connection, key and attitude."
                    else -> "Level the frame, then cycle the physical key off and on."
                },
                color = Muted
            )
            TextButton(
                onClick = {
                    state.disarm()
                    state.flightTestSelected = false
                }
            ) { Text("EXIT FLIGHT TEST") }
        }
    }
}

@Composable
private fun StickBlock(
    title: String,
    readout: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Float, Float) -> Unit,
    onRelease: () -> Unit
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title, color = Muted, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
        Spacer(Modifier.height(6.dp))
        VirtualJoystick(
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            onChange = onChange,
            onRelease = onRelease
        )
        Spacer(Modifier.height(6.dp))
        Text(
            readout,
            color = if (enabled) MaterialTheme.colorScheme.primary else Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ThrottleRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
