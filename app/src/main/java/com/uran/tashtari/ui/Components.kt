package com.uran.tashtari.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val PanelColor = Color(0xFF131C2E)
val LineColor = Color(0xFF22304A)
val Muted = Color(0xFF94A3B8)
val Danger = Color(0xFFEF4444)
val Ok = Color(0xFF22C55E)
val Warn = Color(0xFFF59E0B)
val DisabledGrey = Color(0xFF475569)

enum class Health { OK, WARN, BAD, UNKNOWN }

fun Health.tint(): Color = when (this) {
    Health.OK -> Ok
    Health.WARN -> Warn
    Health.BAD -> Danger
    Health.UNKNOWN -> Muted
}

@Composable
fun ScreenHeader(subtitle: String, trailing: @Composable () -> Unit = {}) {
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
            Text(subtitle, fontSize = 12.sp, color = Muted)
        }
        trailing()
    }
}

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
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
fun SectionLabel(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
fun StatusPill(text: String, color: Color) {
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
fun HealthPill(label: String, value: String, health: Health, modifier: Modifier = Modifier) {
    val c = health.tint()
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            value, color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

// A control that exists in the layout but is not usable until firmware supports it
@Composable
fun PendingAction(label: String, note: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1A2236))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(label, color = DisabledGrey, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(note, color = DisabledGrey, fontSize = 11.sp)
    }
}

@Composable
fun HoldButton(
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
        !enabled -> DisabledGrey
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

@Composable
fun BottomTabs(selected: Int, navigationEnabled: Boolean, onSelect: (Int) -> Unit) {
    val labels = listOf("FLIGHT", "CAMERA", "DIAGNOSTICS")
    Column(Modifier.fillMaxWidth().background(PanelColor)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineColor))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                val sel = i == selected
                Column(
                    Modifier.weight(1f)
                        .clickable(enabled = navigationEnabled && !sel) { onSelect(i) }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .width(28.dp).height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = when {
                            sel -> MaterialTheme.colorScheme.primary
                            navigationEnabled -> Muted
                            else -> DisabledGrey
                        }
                    )
                }
            }
        }
    }
}
