package com.uran.tashtari.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun levelColor(b: BatteryMonitor, now: Long): Color = when {
    !b.isLive(now) -> Muted
    b.level == BatteryLevel.CRITICAL -> Danger
    b.level == BatteryLevel.LOW -> Warn
    else -> Ok
}

// Pinned strip, shown only when something needs attention
@Composable
fun BatteryBanner(state: UranAppState) {
    val b = state.battery
    val now = state.now
    val volts = b.smoothVolts?.let { "%.2f V".format(it) } ?: ""
    val banner: Pair<String, Color>? = when {
        b.lastUpdateMs == 0L -> null
        b.isStale(now) -> "BATTERY TELEMETRY STALE (${(now - b.lastUpdateMs) / 1000} s ago)" to Warn
        !b.sensorValid -> "BATTERY SENSOR INVALID - ignore battery readings" to Warn
        b.level == BatteryLevel.CRITICAL -> "BATTERY CRITICAL $volts - stop and disconnect the pack" to Danger
        b.level == BatteryLevel.LOW -> "BATTERY LOW $volts" to Warn
        else -> null
    }
    if (banner != null) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(banner.second.copy(alpha = 0.18f))
                .padding(10.dp)
        ) {
            Text(banner.first, color = banner.second, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun BatteryRow(state: UranAppState) {
    val b = state.battery
    val now = state.now
    val pct = b.percent(now)
    val color = levelColor(b, now)
    val approx = pct.source.startsWith("VOLTAGE")
    val percentText = when {
        pct.percent == null -> "--%"
        approx -> "~${pct.percent}%"
        else -> "${pct.percent}%"
    }
    val live = b.isLive(now)

    Panel {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("BATTERY")
            Text(batteryStatusText(b, now), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(percentText, color = color, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(18.dp))
            Column {
                Text(
                    if (live) "%.2f V".format(b.smoothVolts ?: 0f) else "-- V",
                    color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace
                )
                Text(
                    if (live && b.amps != null) "%.1f A".format(b.amps) else "-- A",
                    color = Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF0B1220))
        ) {
            val p = pct.percent
            if (p != null) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0, 100) / 100f).fillMaxHeight().background(color))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                pct.percent == null -> "No valid battery data. Nothing is estimated."
                approx -> "Approximate: ${pct.source.lowercase()}"
                else -> "Percentage reported by Flight"
            },
            color = DisabledGrey, fontSize = 11.sp
        )
    }
}

@Composable
fun BatteryDetailPanel(state: UranAppState) {
    val b = state.battery
    val now = state.now
    val pct = b.percent(now)
    Panel {
        SectionLabel("BATTERY DETAIL")
        Spacer(Modifier.height(6.dp))
        BatRow("Status", batteryStatusText(b, now), levelColor(b, now))
        BatRow("Voltage (smoothed)", b.smoothVolts?.let { "%.2f V".format(it) } ?: "--")
        BatRow("Voltage (raw)", b.rawVolts?.let { "%.2f V".format(it) } ?: "--")
        BatRow("Per cell (smoothed)", b.smoothVolts?.let { "%.2f V".format(it / BatteryConfig.CELLS) } ?: "--")
        BatRow("Current", b.amps?.let { "%.1f A".format(it) } ?: "-- (no current data)")
        BatRow("Charge", if (pct.percent == null) "--%" else "${pct.percent}%  [${pct.source}]")
        BatRow("Lowest voltage seen", b.minVolts?.let { "%.2f V".format(it) } ?: "--")
        BatRow("Last update", if (b.lastUpdateMs == 0L) "never" else "${now - b.lastUpdateMs} ms ago")
        BatRow("BAT packets ok / rejected", "${b.packets} / ${b.rejected}")
        if (b.rejected > 0) BatRow("Last rejected", b.lastRejected, Warn)
        Spacer(Modifier.height(6.dp))
        Text(
            "Config: ${BatteryConfig.CELLS}S, ${BatteryConfig.CAPACITY_MAH} mAh, low " +
                    "${BatteryConfig.LOW_V_PER_CELL} V/cell, critical ${BatteryConfig.CRITICAL_V_PER_CELL} V/cell. " +
                    "Placeholders: confirm against the pack label. Voltage-only percentage is approximate " +
                    "and reads low under load. Low-voltage protection must be enforced by Flight.",
            color = DisabledGrey, fontSize = 11.sp
        )
    }
}

@Composable
private fun BatRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}