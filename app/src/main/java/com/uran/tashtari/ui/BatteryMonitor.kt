package com.uran.tashtari.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

// CONFIRM these against the real LiPo label and Usman's safety thresholds.
object BatteryConfig {
    const val CELLS = 3                       // 3S 11.1 V pack (from the purchase list)
    const val CAPACITY_MAH = 2200             // from the purchase list
    const val LOW_V_PER_CELL = 3.50f          // PLACEHOLDER warning threshold
    const val CRITICAL_V_PER_CELL = 3.30f     // PLACEHOLDER critical threshold
    const val HYSTERESIS_V_PER_CELL = 0.05f
    const val STALE_MS = 3000L
    const val ALLOW_VOLTAGE_ESTIMATE = true   // false = show --% unless Flight sends a valid percentage
}

enum class BatteryLevel { UNKNOWN, NORMAL, LOW, CRITICAL }

data class PercentInfo(val percent: Int?, val source: String)

// Approximate resting LiPo curve (per cell). Reads LOW under load, so it is only a rough guide.
private val LUT = listOf(
    4.20f to 100, 4.15f to 95, 4.11f to 90, 4.08f to 85, 4.02f to 80, 3.98f to 75,
    3.95f to 70, 3.91f to 65, 3.87f to 60, 3.85f to 55, 3.84f to 50, 3.82f to 45,
    3.80f to 40, 3.79f to 35, 3.77f to 30, 3.75f to 25, 3.73f to 20, 3.71f to 15,
    3.69f to 10, 3.61f to 5, 3.27f to 0
)

fun voltagePercentPerCell(v: Float): Int {
    if (v >= LUT.first().first) return 100
    if (v <= LUT.last().first) return 0
    for (i in 0 until LUT.size - 1) {
        val (v1, p1) = LUT[i]
        val (v2, p2) = LUT[i + 1]
        if (v <= v1 && v >= v2) {
            val f = (v - v2) / (v1 - v2)
            return (p2 + f * (p1 - p2)).roundToInt()
        }
    }
    return 0
}

/**
 * Parses the PROPOSED packet:  BAT,<millivolts>,<milliamps>,<remaining_percent>,<valid>
 *   milliamps < 0     -> no current sensor
 *   percent outside 0..100 -> Flight has no valid percentage
 *   valid = 0         -> Flight says the reading is invalid
 * Nothing sends this packet yet. COMM and Flight firmware still have to implement it.
 */
class BatteryMonitor {
    var rawVolts by mutableStateOf<Float?>(null)
    var smoothVolts by mutableStateOf<Float?>(null)
    var minVolts by mutableStateOf<Float?>(null)
    var amps by mutableStateOf<Float?>(null)
    var flightPercent by mutableStateOf<Int?>(null)
    var sensorValid by mutableStateOf(false)
    var lastUpdateMs by mutableLongStateOf(0L)
    var level by mutableStateOf(BatteryLevel.UNKNOWN)
    var packets by mutableIntStateOf(0)
    var rejected by mutableIntStateOf(0)
    var lastRejected by mutableStateOf("-")

    private val fall = 0.60f   // follow voltage drops quickly
    private val rise = 0.15f   // smooth rises slowly

    private fun reject(why: String): Boolean {
        rejected++
        lastRejected = why
        return false
    }

    /** null = not a BAT line, true = accepted, false = rejected */
    fun handleLine(line: String, nowMs: Long): Boolean? {
        if (!line.startsWith("BAT,")) return null
        val p = line.trim().split(",")
        if (p.size != 5) return reject("expected 5 fields, got ${p.size}")
        val mv = p[1].trim().toIntOrNull() ?: return reject("bad millivolts '${p[1]}'")
        val ma = p[2].trim().toIntOrNull() ?: return reject("bad milliamps '${p[2]}'")
        val pct = p[3].trim().toIntOrNull() ?: return reject("bad percent '${p[3]}'")
        val valid = p[4].trim().toIntOrNull() ?: return reject("bad valid flag '${p[4]}'")
        if (valid != 0 && valid != 1) return reject("valid flag must be 0 or 1")

        if (valid == 0) {                       // Flight says the sensor reading is not trustworthy
            sensorValid = false
            smoothVolts = null
            amps = null
            flightPercent = null
            level = BatteryLevel.UNKNOWN
            lastUpdateMs = nowMs
            packets++
            return true
        }

        val v = mv / 1000f
        if (v < 1.0f || v > BatteryConfig.CELLS * 4.5f) {
            return reject("%.2f V is implausible for a %dS pack (check BatteryConfig)".format(v, BatteryConfig.CELLS))
        }

        sensorValid = true
        rawVolts = v
        minVolts = minVolts?.let { minOf(it, v) } ?: v
        val prev = smoothVolts
        val s = when {
            prev == null -> v
            v < prev -> prev + fall * (v - prev)
            else -> prev + rise * (v - prev)
        }
        smoothVolts = s
        amps = if (ma >= 0) ma / 1000f else null
        flightPercent = if (pct in 0..100) pct else null
        lastUpdateMs = nowMs
        packets++
        evaluateLevel(s)
        return true
    }

    private fun evaluateLevel(volts: Float) {
        val perCell = volts / BatteryConfig.CELLS
        val low = BatteryConfig.LOW_V_PER_CELL
        val crit = BatteryConfig.CRITICAL_V_PER_CELL
        val h = BatteryConfig.HYSTERESIS_V_PER_CELL
        level = when (level) {
            BatteryLevel.CRITICAL -> when {
                perCell > crit + h && perCell >= low -> BatteryLevel.NORMAL
                perCell > crit + h -> BatteryLevel.LOW
                else -> BatteryLevel.CRITICAL
            }
            BatteryLevel.LOW -> when {
                perCell < crit -> BatteryLevel.CRITICAL
                perCell > low + h -> BatteryLevel.NORMAL
                else -> BatteryLevel.LOW
            }
            else -> when {
                perCell < crit -> BatteryLevel.CRITICAL
                perCell < low -> BatteryLevel.LOW
                else -> BatteryLevel.NORMAL
            }
        }
    }

    fun isStale(now: Long) = lastUpdateMs != 0L && now - lastUpdateMs > BatteryConfig.STALE_MS

    fun isLive(now: Long) = lastUpdateMs != 0L && sensorValid && !isStale(now)

    fun percent(now: Long): PercentInfo {
        if (!isLive(now)) return PercentInfo(null, "-")
        flightPercent?.let { return PercentInfo(it, "FLIGHT") }
        if (BatteryConfig.ALLOW_VOLTAGE_ESTIMATE) {
            smoothVolts?.let { v ->
                val loaded = (amps ?: 0f) > 1f
                return PercentInfo(
                    voltagePercentPerCell(v / BatteryConfig.CELLS),
                    if (loaded) "VOLTAGE ONLY (approx, reads low under load)" else "VOLTAGE ONLY (approx)"
                )
            }
        }
        return PercentInfo(null, "-")
    }
}

fun batteryStatusText(b: BatteryMonitor, now: Long): String = when {
    b.lastUpdateMs == 0L -> "NO DATA"
    b.isStale(now) -> "STALE"
    !b.sensorValid -> "SENSOR INVALID"
    b.level == BatteryLevel.CRITICAL -> "CRITICAL"
    b.level == BatteryLevel.LOW -> "LOW"
    else -> "OK"
}

fun batteryHealthItem(state: UranAppState): HealthItem {
    val b = state.battery
    val now = state.now
    return when {
        b.lastUpdateMs == 0L ->
            HealthItem("BATTERY", "NO DATA", Health.UNKNOWN, "No battery telemetry from Flight yet")
        b.isStale(now) ->
            HealthItem("BATTERY", "STALE", Health.WARN, "No BAT packet for ${now - b.lastUpdateMs} ms")
        !b.sensorValid ->
            HealthItem("BATTERY", "INVALID", Health.BAD, "Flight reports the battery reading as invalid")
        b.level == BatteryLevel.CRITICAL ->
            HealthItem("BATTERY", "CRITICAL", Health.BAD, "Pack voltage is critically low")
        b.level == BatteryLevel.LOW ->
            HealthItem("BATTERY", "LOW", Health.WARN, "Pack voltage is low")
        else ->
            HealthItem("BATTERY", "%.2f V".format(b.smoothVolts ?: 0f), Health.OK, "Battery telemetry is live")
    }
}