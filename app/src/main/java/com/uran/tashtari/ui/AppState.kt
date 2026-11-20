package com.uran.tashtari.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uran.tashtari.network.CommandSender
import com.uran.tashtari.network.LinkState
import com.uran.tashtari.network.LinkStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(val timeMs: Long, val tag: String, val text: String)

data class HealthItem(val label: String, val value: String, val health: Health, val detail: String)

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

fun LogEntry.format(): String = "${timeFormat.format(Date(timeMs))} [$tag] $text"

class UranAppState(val sender: CommandSender) {

    // ----- controls -----
    var roll by mutableIntStateOf(0)
    var pitch by mutableIntStateOf(0)
    var yaw by mutableIntStateOf(0)
    var throttleSelected by mutableIntStateOf(0)   // not sent anywhere until Stage 4
    var stopped by mutableStateOf(false)           // UI lock after ESTOP
    var estopSent by mutableStateOf(false)         // stays true until Flight itself reports the latch cleared (Stage 5)

    // ----- link statistics (real values from COMM replies) -----
    var lastReply by mutableStateOf("-")
    var now by mutableLongStateOf(SystemClock.elapsedRealtime())
    var rcSent by mutableIntStateOf(0)
    var rcAcks by mutableIntStateOf(0)
    var lastRcAckAt by mutableLongStateOf(0L)
    var rttAvgMs by mutableFloatStateOf(0f)
    var rttMaxMs by mutableLongStateOf(0L)
    private val rcSentAt = LongArray(256)

    // ----- event log -----
    val log = mutableStateListOf<LogEntry>()
    val battery = BatteryMonitor()

    fun addLog(tag: String, text: String) {
        log.add(0, LogEntry(System.currentTimeMillis(), tag, text))
        if (log.size > 100) log.removeAt(log.lastIndex)
    }

    fun logText(): String = log.asReversed().joinToString("\n") { it.format() }

    fun clearLog() = log.clear()

    fun neutralAll() {
        roll = 0; pitch = 0; yaw = 0
        throttleSelected = 0
    }

    fun noteRcSent(seq: Int) {
        rcSentAt[seq and 0xFF] = SystemClock.elapsedRealtime()
        rcSent++
    }

    fun resetLinkStats() {
        rcSent = 0; rcAcks = 0; lastRcAckAt = 0L
        rttAvgMs = 0f; rttMaxMs = 0L
        rcSentAt.fill(0L)
    }

    // Every joystick heartbeat is ACKed by COMM, so ACK,COMM_RC,n is counted, not logged
    fun handleReply(line: String) {
        // Battery telemetry: parsed here, never logged per packet (avoids flooding the log)
        val levelBefore = battery.level
        val batResult = battery.handleLine(line, SystemClock.elapsedRealtime())
        if (batResult != null) {
            if (!batResult && battery.rejected % 20 == 1) addLog("ERR", "BAT rejected: ${battery.lastRejected}")
            if (battery.level != levelBefore) addLog("BAT", "level $levelBefore -> ${battery.level}")
            return
        }
        val p = line.split(",")
        if (p.size >= 3 && p[0] == "ACK" && p[1] == "COMM_RC") {
            val t = SystemClock.elapsedRealtime()
            rcAcks++
            lastRcAckAt = t
            p[2].trim().toIntOrNull()?.let { s ->
                val sent = rcSentAt[s and 0xFF]
                val rtt = t - sent
                if (sent > 0L && rtt in 0..5000) {
                    rttAvgMs = if (rttAvgMs == 0f) rtt.toFloat() else rttAvgMs * 0.9f + rtt * 0.1f
                    if (rtt > rttMaxMs) rttMaxMs = rtt
                }
            }
            return
        }
        lastReply = line
        addLog(if (line.startsWith("ERR")) "ERR" else "COMM", line)
    }

    fun disarm() {
        neutralAll()
        addLog("TX", if (sender.sendDisarm()) "DISARM" else "DISARM NOT SENT (not connected)")
    }

    fun estop() {
        neutralAll()
        stopped = true
        val sent = sender.sendEstop()
        if (sent) estopSent = true
        addLog("TX", if (sent) "ESTOP" else "ESTOP NOT SENT (not connected). UI locked.")
    }

    // Unlocks THIS APP only. It never implies the Flight latch was cleared.
    fun resetUi() {
        stopped = false
        addLog("UI", "RESET UI. Flight ESTOP state is NOT confirmed.")
    }
}

// Real values only. Anything without a data source stays UNKNOWN.
fun healthItems(state: UranAppState, link: LinkState): List<HealthItem> {
    val ble = when (link.status) {
        LinkStatus.IDLE ->
            HealthItem("ANDROID-COMM BLE", "DISCONNECTED", Health.UNKNOWN, link.message)
        LinkStatus.SCANNING, LinkStatus.CONNECTING ->
            HealthItem("ANDROID-COMM BLE", "CONNECTING", Health.WARN, link.message)
        LinkStatus.READY ->
            if (!state.stopped && state.rcAcks > 0 && state.now - state.lastRcAckAt > 1500)
                HealthItem("ANDROID-COMM BLE", "STALE", Health.WARN,
                    "No COMM ACK for ${state.now - state.lastRcAckAt} ms")
            else
                HealthItem("ANDROID-COMM BLE", "CONNECTED", Health.OK, link.message)
    }
    val pending = "No Flight telemetry channel yet (Stages 3 and 5)"
    return listOf(
        ble,
        HealthItem("COMM-FLIGHT UART", "UNKNOWN", Health.UNKNOWN, pending),
        HealthItem("FLIGHT MPU", "UNKNOWN", Health.UNKNOWN, pending),
        HealthItem("SIMULATION", "UNKNOWN", Health.UNKNOWN, pending),
        if (state.estopSent)
            HealthItem("FLIGHT ESTOP", "LATCHED?", Health.BAD,
                "ESTOP was sent. Only a physical Flight reset clears it. The app cannot confirm yet.")
        else
            HealthItem("FLIGHT ESTOP", "UNKNOWN", Health.UNKNOWN, pending),
        batteryHealthItem(state)
    )
}