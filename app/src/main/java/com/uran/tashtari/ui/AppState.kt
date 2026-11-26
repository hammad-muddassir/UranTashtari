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
const val SERIAL_LINE_LIMIT = 4096

fun LogEntry.format(): String = "${timeFormat.format(Date(timeMs))} [$tag] $text"

class UranAppState(
    val sender: CommandSender
) {
    var roll by mutableIntStateOf(0)
    var pitch by mutableIntStateOf(0)
    var yaw by mutableIntStateOf(0)

    var throttleSelected by mutableIntStateOf(0)
    var throttleTransmitted by mutableIntStateOf(0)

    var flightTestSelected by mutableStateOf(false)
        private set
    var flightArmed by mutableStateOf(false)
        private set
    var flightArmPending by mutableStateOf(false)
        private set
    var liveStatus by mutableStateOf("WAITING")
        private set
    private var lastLiveStatusAt = 0L
    private var armRequestedAt = 0L

    var stopped by mutableStateOf(false)
    var estopSent by mutableStateOf(false)

    private var estopRequestedAtMs = 0L
    private var estopObservedLatched = false

    var lastReply by mutableStateOf("-")

    var now by mutableLongStateOf(
        SystemClock.elapsedRealtime()
    )

    var rcSent by mutableIntStateOf(0)
    var rcAcks by mutableIntStateOf(0)

    var lastRcAckAt by mutableLongStateOf(0L)
    var rttAvgMs by mutableFloatStateOf(0f)
    var rttMaxMs by mutableLongStateOf(0L)

    private val rcSentAt = LongArray(256)

    val battery = BatteryMonitor()
    val flight = FlightTelemetry()

    val log = mutableStateListOf<LogEntry>()
    private val trafficLog = mutableStateListOf<LogEntry>()

    var lastTx by mutableStateOf<LogEntry?>(null)
    var lastRx by mutableStateOf<LogEntry?>(null)
    var lastError by mutableStateOf<LogEntry?>(null)

    fun addLog(tag: String, text: String) {
        if (tag == "BLE") recordWire(tag, text)
        log.add(
            0,
            LogEntry(
                System.currentTimeMillis(),
                tag,
                text
            )
        )

        if (log.size > 100) {
            log.removeAt(log.lastIndex)
        }
    }

    fun logText(): String =
        log.asReversed().joinToString("\n") {
            it.format()
        }

    fun clearLog() {
        log.clear()
    }

    fun trafficText(): String =
        trafficLog.toList().joinToString("\n") {
            it.format()
        }

    fun serialLines(): List<LogEntry> = trafficLog.toList()

    fun clearTraffic() {
        trafficLog.clear()
    }

    private fun recordWire(
        tag: String,
        text: String
    ) {
        val entry = LogEntry(
            System.currentTimeMillis(),
            tag,
            text.trim()
        )

        if (tag == "TX") lastTx = entry
        if (tag == "RX") lastRx = entry

        if (text.startsWith("ERR")) {
            lastError = entry
        }

        trafficLog.add(entry)
        if (trafficLog.size > SERIAL_LINE_LIMIT) {
            trafficLog.removeRange(0, 128)
        }
    }

    fun neutralAll() {
        roll = 0
        pitch = 0
        yaw = 0
        throttleSelected = 0
    }

    fun noteBleWrite(packet: String) {
        recordWire("TX", packet)

        if (packet.startsWith("C,")) {
            val parts = packet.split(",")

            if (parts.size == 7) {
                val seq = parts[1].toIntOrNull()
                val throttle =
                    parts[6].toIntOrNull()

                if (seq != null) {
                    noteRcSent(seq)
                }

                if (throttle != null) {
                    throttleTransmitted =
                        throttle.coerceIn(0, 100)
                }
            }
        }
    }

    private fun noteRcSent(seq: Int) {
        rcSentAt[seq and 0xFF] =
            SystemClock.elapsedRealtime()

        rcSent++
    }

    fun resetLinkStats() {
        rcSent = 0
        rcAcks = 0
        lastRcAckAt = 0L

        rttAvgMs = 0f
        rttMaxMs = 0L

        rcSentAt.fill(0L)
    }

    fun enableControls() {
        if (sender.state.value.status != LinkStatus.READY || stopped || flightTestSelected) return
        resetLiveFlight("WAITING")
        flightTestSelected = true
    }

    fun armFlight() {
        val time = SystemClock.elapsedRealtime()
        if (!flightTestSelected || stopped || flightArmed || flightArmPending ||
            liveStatus != "READY" || time - lastLiveStatusAt >= 750L ||
            throttleSelected != 0 || roll != 0 || pitch != 0 || yaw != 0) return

        flightArmPending = true
        armRequestedAt = time
        if (!sender.sendCommand("FLIGHT_ARM")) {
            resetLiveFlight("ARM COMMAND FAILED")
        }
    }

    fun resetLiveFlight(status: String) {
        flightArmPending = false
        flightArmed = false
        liveStatus = status
        throttleTransmitted = 0
        neutralAll()
    }

    fun checkFlightStatus(time: Long) {
        if ((flightArmed && time - lastLiveStatusAt >= 750L) ||
            (flightArmPending && time - armRequestedAt >= 1000L)) {
            disarm()
            resetLiveFlight("FLIGHT LINK STALE")
        }
    }

    fun handleReply(line: String) {
        recordWire("RX", line)
        if (line.startsWith("PWM,") || line.startsWith("YAW_CHECK,")) return
        val time = SystemClock.elapsedRealtime()
        if (line.startsWith("FS,")) {
            val status = line.substringAfter(',')
            if (status != "ARMED" && status != "READY" &&
                status != "SAFE" && status != "CFG" && status != "FAULT") return

            lastLiveStatusAt = time
            when (status) {
                "ARMED" -> {
                    if (flightTestSelected && !stopped && (flightArmPending || flightArmed)) {
                        flightArmed = true
                        flightArmPending = false
                        liveStatus = status
                    } else {
                        sender.sendDisarm()
                    }
                }
                "FAULT", "CFG" -> {
                    if (flightArmed || flightArmPending) disarm()
                    resetLiveFlight(status)
                }
                else -> if (flightArmed) {
                    flightTestSelected = false
                    resetLiveFlight(status)
                } else liveStatus = status
            }
            return
        }

        if (line == "ERR,FLIGHT_ARM" || line == "ACK,DISARMED" ||
            line == "ACK,ESTOP" || line == "ERR,MPU_FAULT") {
            if (flightArmed || flightArmPending || line == "ACK,ESTOP" || line == "ERR,MPU_FAULT") {
                flightTestSelected = false
            }
            resetLiveFlight(if (line == "ERR,FLIGHT_ARM") "ARM REJECTED" else "SAFE")
        }

        val previousLevel = battery.level

        val batResult =
            battery.handleLine(line, time)

        if (batResult != null) {
            if (
                !batResult &&
                battery.rejected % 20 == 1
            ) {
                addLog(
                    "ERR",
                    "BAT rejected: " +
                            battery.lastRejected
                )
            }

            if (
                battery.level != previousLevel
            ) {
                addLog(
                    "BAT",
                    "level $previousLevel -> " +
                            battery.level
                )
            }

            return
        }

        val telemResult =
            flight.handleLine(line, time)

        if (telemResult != null) {
            if (
                !telemResult &&
                flight.rejected % 20 == 1
            ) {
                addLog(
                    "ERR",
                    "Telemetry rejected: " +
                            flight.lastRejected
                )
            }

            if (
                telemResult &&
                flight.estopLatched == true
            ) {
                stopped = true
                estopSent = true
                estopObservedLatched = true
            }

            flight.takeEvents().forEach {
                addLog("FLT", it)
            }

            return
        }

        val p = line.split(",")

        if (
            p.size >= 3 &&
            p[0] == "ACK" &&
            p[1] == "COMM_RC"
        ) {
            val t =
                SystemClock.elapsedRealtime()

            rcAcks++
            lastRcAckAt = t

            p[2].trim()
                .toIntOrNull()
                ?.let { seq ->

                    val index = seq and 0xFF
                    val sent = rcSentAt[index]
                    val rtt = t - sent

                    if (
                        sent > 0L &&
                        rtt in 0..5000
                    ) {
                        rttAvgMs =
                            if (rttAvgMs == 0f) {
                                rtt.toFloat()
                            } else {
                                rttAvgMs * 0.9f +
                                        rtt * 0.1f
                            }

                        if (rtt > rttMaxMs) {
                            rttMaxMs = rtt
                        }

                        rcSentAt[index] = 0L
                    }
                }

            return
        }

        lastReply = line

        addLog(
            if (line.startsWith("ERR")) {
                "ERR"
            } else {
                "COMM"
            },
            line
        )
    }

    fun disarm() {
        flightTestSelected = false
        resetLiveFlight("DISARM REQUESTED")

        val sent = sender.sendDisarm()

        addLog(
            "QUEUE",
            if (sent) {
                "DISARM queued"
            } else {
                "DISARM not sent: disconnected"
            }
        )
    }

    fun estop() {
        flightTestSelected = false
        resetLiveFlight("E-STOP REQUESTED")

        stopped = true
        estopSent = true

        estopObservedLatched = false
        estopRequestedAtMs =
            SystemClock.elapsedRealtime()

        val sent = sender.sendEstop()

        addLog(
            "QUEUE",
            if (sent) {
                "ESTOP queued; UI locked"
            } else {
                "ESTOP not sent; UI locked"
            }
        )
    }

    fun resetUi(): Boolean {
        val time =
            SystemClock.elapsedRealtime()

        val confirmed =
            sender.state.value.status ==
                    LinkStatus.READY &&
                    flight.stateFresh(time) &&
                    flight.estopLatched == false &&
                    flight.lastStateMs >
                    estopRequestedAtMs &&
                    estopObservedLatched

        if (!confirmed) {
            addLog(
                "UI",
                "RESET REFUSED: fresh Flight " +
                        "ESTOP=0 after observed latch required"
            )

            return false
        }

        neutralAll()

        stopped = false
        estopSent = false
        estopObservedLatched = false

        addLog(
            "UI",
            "UI unlocked after Flight confirmation"
        )

        return true
    }

}


// Real values only. Anything without a data source stays UNKNOWN.
fun healthItems(state: UranAppState, link: LinkState): List<HealthItem> {
    val now = state.now
    val f = state.flight

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

    val uart = when {
        link.status != LinkStatus.READY ->
            HealthItem("COMM-FLIGHT UART", "UNKNOWN", Health.UNKNOWN, "Phone is not connected to COMM")
        f.commFresh(now) && f.uartAlive == false ->
            HealthItem("COMM-FLIGHT UART", "DOWN", Health.BAD, "COMM reports no heartbeat from Flight")
        f.anyFlightFresh(now) ->
            HealthItem("COMM-FLIGHT UART", "CONNECTED", Health.OK, "Flight telemetry is arriving through COMM")
        f.anyFlightEver() ->
            HealthItem("COMM-FLIGHT UART", "STALE", Health.WARN,
                "No Flight telemetry for ${now - f.lastFlightMs()} ms")
        f.commFresh(now) && f.uartAlive == true ->
            HealthItem("COMM-FLIGHT UART", "HEARTBEAT", Health.WARN,
                "COMM sees Flight, but no Flight telemetry has arrived yet")
        else ->
            HealthItem("COMM-FLIGHT UART", "UNKNOWN", Health.UNKNOWN,
                "No telemetry or COMM status lines received (firmware must send them)")
    }

    val stateStale = f.lastStateMs != 0L && !f.stateFresh(now)
    val noState = HealthItem("", "", Health.UNKNOWN, "No Flight state received yet")

    val mpu = when {
        f.lastStateMs == 0L -> noState.copy(label = "FLIGHT MPU", value = "UNKNOWN")
        stateStale -> HealthItem("FLIGHT MPU", "STALE", Health.WARN, "Flight state is ${now - f.lastStateMs} ms old")
        f.mpuOk == true -> HealthItem("FLIGHT MPU", "OK", Health.OK, "Flight reports the MPU is healthy")
        else -> HealthItem("FLIGHT MPU", "FAULT", Health.BAD, "Flight reports an MPU fault")
    }

    val sim = when {
        f.lastStateMs == 0L -> noState.copy(label = "SIMULATION", value = "UNKNOWN")
        stateStale -> HealthItem("SIMULATION", "STALE", Health.WARN, "Flight state is ${now - f.lastStateMs} ms old")
        else -> when (f.simState) {
            "ON" -> HealthItem("SIMULATION", "ON", Health.WARN, "Virtual outputs may be nonzero. Simulation only.")
            "READY" -> HealthItem("SIMULATION", "READY", Health.OK, "Calibrated. Not running.")
            "ESTOP" -> HealthItem("SIMULATION", "ESTOP", Health.BAD, "Flight is in ESTOP")
            else -> HealthItem("SIMULATION", "INACTIVE", Health.UNKNOWN, "Not calibrated or not running")
        }
    }

    val estop = when {
        f.stateFresh(now) && f.estopLatched == true ->
            HealthItem("FLIGHT ESTOP", "LATCHED", Health.BAD,
                "Flight reports ESTOP latched. Only a physical Flight reset clears it.")
        f.stateFresh(now) && f.estopLatched == false ->
            HealthItem("FLIGHT ESTOP", "CLEAR", Health.OK, "Flight reports no ESTOP latch")
        stateStale ->
            HealthItem("FLIGHT ESTOP", "STALE", Health.WARN, "Cannot confirm. Flight state is ${now - f.lastStateMs} ms old")
        state.estopSent ->
            HealthItem("FLIGHT ESTOP", "LATCHED?", Health.BAD,
                "ESTOP was sent. Only a physical Flight reset clears it. The app cannot confirm yet.")
        else ->
            HealthItem("FLIGHT ESTOP", "UNKNOWN", Health.UNKNOWN, "No Flight state received yet")
    }

    return listOf(ble, uart, mpu, sim, estop, batteryHealthItem(state))
}
