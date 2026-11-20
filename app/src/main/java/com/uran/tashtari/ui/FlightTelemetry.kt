package com.uran.tashtari.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object TelemetryConfig {
    const val STATE_STALE_MS = 1500L   // ST: ~2 Hz and on every change
    const val FAST_STALE_MS = 1000L    // AT and MO: ~5 Hz
    const val COMM_STALE_MS = 1500L    // CS (from COMM): ~2 Hz
}

private val SIM_STATES = setOf("INACTIVE", "READY", "ON", "ESTOP")

/**
 * Parses the PROPOSED telemetry lines. Nothing sends them yet.
 *   ST,<sim>,<mpu>,<cal>,<estop>,<disarmed>,<rc>    Flight state  (flags are 0 or 1)
 *   AT,<roll_d10>,<pitch_d10>,<gyroz_d10>           attitude in tenths of a degree and of deg/s
 *   MO,<FL>,<FR>,<RR>,<RL>                          virtual motor outputs, 0..100
 *   CS,<uart>,<pilot>                               COMM's own status (flags are 0 or 1)
 */
class FlightTelemetry {
    var simState by mutableStateOf<String?>(null)
    var mpuOk by mutableStateOf<Boolean?>(null)
    var calibrated by mutableStateOf<Boolean?>(null)
    var estopLatched by mutableStateOf<Boolean?>(null)
    var disarmed by mutableStateOf<Boolean?>(null)
    var rcFresh by mutableStateOf<Boolean?>(null)
    var lastStateMs by mutableLongStateOf(0L)

    var rollDeg by mutableStateOf<Float?>(null)
    var pitchDeg by mutableStateOf<Float?>(null)
    var gyroZ by mutableStateOf<Float?>(null)
    var lastAttMs by mutableLongStateOf(0L)

    var motors by mutableStateOf<List<Int>?>(null)   // FL, FR, RR, RL
    var lastMotMs by mutableLongStateOf(0L)

    var uartAlive by mutableStateOf<Boolean?>(null)  // COMM: it hears Flight
    var pilotLink by mutableStateOf<Boolean?>(null)  // COMM: it receives valid C packets
    var lastCommMs by mutableLongStateOf(0L)

    var packets by mutableIntStateOf(0)
    var rejected by mutableIntStateOf(0)
    var lastRejected by mutableStateOf("-")

    private val events = mutableListOf<String>()

    fun takeEvents(): List<String> {
        val copy = events.toList()
        events.clear()
        return copy
    }

    private fun fresh(last: Long, now: Long, limit: Long) = last != 0L && now - last <= limit
    fun stateFresh(now: Long) = fresh(lastStateMs, now, TelemetryConfig.STATE_STALE_MS)
    fun attFresh(now: Long) = fresh(lastAttMs, now, TelemetryConfig.FAST_STALE_MS)
    fun motFresh(now: Long) = fresh(lastMotMs, now, TelemetryConfig.FAST_STALE_MS)
    fun commFresh(now: Long) = fresh(lastCommMs, now, TelemetryConfig.COMM_STALE_MS)
    fun anyFlightFresh(now: Long) = stateFresh(now) || attFresh(now) || motFresh(now)
    fun anyFlightEver() = lastStateMs != 0L || lastAttMs != 0L || lastMotMs != 0L
    fun lastFlightMs() = maxOf(lastStateMs, lastAttMs, lastMotMs)

    private fun reject(why: String): Boolean {
        rejected++
        lastRejected = why
        return false
    }

    private fun flag(s: String): Boolean? = when (s) { "1" -> true; "0" -> false; else -> null }

    /** null = not a telemetry line, true = accepted, false = rejected */
    fun handleLine(line: String, nowMs: Long): Boolean? {
        val p = line.trim().split(",").map { it.trim() }
        return when (p[0]) {
            "ST" -> parseState(p, nowMs)
            "AT" -> parseAttitude(p, nowMs)
            "MO" -> parseMotors(p, nowMs)
            "CS" -> parseComm(p, nowMs)
            else -> null
        }
    }

    private fun parseState(p: List<String>, now: Long): Boolean {
        if (p.size != 7) return reject("ST needs 7 fields, got ${p.size}")
        val sim = p[1]
        if (sim !in SIM_STATES) return reject("ST unknown sim state '$sim'")
        val mpu = flag(p[2]) ?: return reject("ST bad mpu flag")
        val cal = flag(p[3]) ?: return reject("ST bad calibrated flag")
        val es = flag(p[4]) ?: return reject("ST bad estop flag")
        val dis = flag(p[5]) ?: return reject("ST bad disarmed flag")
        val rc = flag(p[6]) ?: return reject("ST bad rc flag")

        if (lastStateMs == 0L) events.add("Flight telemetry started (sim $sim)")
        if (simState != null && simState != sim) events.add("SIM $simState -> $sim")
        if (mpuOk != null && mpuOk != mpu) events.add(if (mpu) "MPU OK" else "MPU FAULT")
        if (estopLatched != null && estopLatched != es) {
            events.add(if (es) "FLIGHT ESTOP LATCHED" else "FLIGHT ESTOP CLEARED")
        }
        if (rcFresh != null && rcFresh != rc) events.add(if (rc) "FLIGHT RC FRESH" else "FLIGHT RC TIMEOUT")

        simState = sim
        mpuOk = mpu
        calibrated = cal
        estopLatched = es
        disarmed = dis
        rcFresh = rc
        lastStateMs = now
        packets++
        return true
    }

    private fun parseAttitude(p: List<String>, now: Long): Boolean {
        if (p.size != 4) return reject("AT needs 4 fields, got ${p.size}")
        val r = p[1].toIntOrNull() ?: return reject("AT bad roll")
        val pi = p[2].toIntOrNull() ?: return reject("AT bad pitch")
        val g = p[3].toIntOrNull() ?: return reject("AT bad gyroZ")
        if (r !in -1800..1800 || pi !in -1800..1800 || g !in -30000..30000) {
            return reject("AT value out of range")
        }
        rollDeg = r / 10f
        pitchDeg = pi / 10f
        gyroZ = g / 10f
        lastAttMs = now
        packets++
        return true
    }

    private fun parseMotors(p: List<String>, now: Long): Boolean {
        if (p.size != 5) return reject("MO needs 5 fields, got ${p.size}")
        val v = p.drop(1).map { it.toIntOrNull() ?: return reject("MO bad value '$it'") }
        if (v.any { it !in 0..100 }) return reject("MO value outside 0..100")
        motors = v
        lastMotMs = now
        packets++
        return true
    }

    private fun parseComm(p: List<String>, now: Long): Boolean {
        if (p.size != 3) return reject("CS needs 3 fields, got ${p.size}")
        val uart = flag(p[1]) ?: return reject("CS bad uart flag")
        val pilot = flag(p[2]) ?: return reject("CS bad pilot flag")
        uartAlive = uart
        pilotLink = pilot
        lastCommMs = now
        packets++
        return true
    }
}