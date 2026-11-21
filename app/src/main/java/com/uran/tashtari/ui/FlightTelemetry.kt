package com.uran.tashtari.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object TelemetryConfig {
    const val STATE_STALE_MS = 3000L
    const val FAST_STALE_MS = 1500L
    const val COMM_STALE_MS = 3000L
}

private val SIM_STATES =
    setOf("INACTIVE", "READY", "ON", "ESTOP")

class FlightTelemetry {

    var simState by mutableStateOf<String?>(null)
    var mpuOk by mutableStateOf<Boolean?>(null)
    var calibrated by mutableStateOf<Boolean?>(null)
    var estopLatched by mutableStateOf<Boolean?>(null)
    var disarmed by mutableStateOf<Boolean?>(null)
    var rcFresh by mutableStateOf<Boolean?>(null)

    var flightMode by mutableStateOf<String?>(null)
    var keyActive by mutableStateOf<Boolean?>(null)
    var escEnabled by mutableStateOf<Boolean?>(null)
    var benchRunning by mutableStateOf<Boolean?>(null)
    var flightLink by mutableStateOf<Boolean?>(null)

    var lastStateMs by mutableLongStateOf(0L)
    var lastLegacyMs by mutableLongStateOf(0L)

    var rollDeg by mutableStateOf<Float?>(null)
    var pitchDeg by mutableStateOf<Float?>(null)
    var gyroZ by mutableStateOf<Float?>(null)
    var lastAttMs by mutableLongStateOf(0L)

    var motors by mutableStateOf<List<Int>?>(null)
    var lastMotMs by mutableLongStateOf(0L)

    var uartAlive by mutableStateOf<Boolean?>(null)
    var pilotLink by mutableStateOf<Boolean?>(null)
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

    private fun fresh(
        last: Long,
        now: Long,
        limit: Long
    ): Boolean =
        last > 0L &&
                now >= last &&
                now - last <= limit

    fun stateFresh(now: Long) =
        fresh(
            lastStateMs,
            now,
            TelemetryConfig.STATE_STALE_MS
        )

    fun legacyFresh(now: Long) =
        fresh(
            lastLegacyMs,
            now,
            TelemetryConfig.STATE_STALE_MS
        )

    fun attFresh(now: Long) =
        fresh(
            lastAttMs,
            now,
            TelemetryConfig.FAST_STALE_MS
        )

    fun motFresh(now: Long) =
        fresh(
            lastMotMs,
            now,
            TelemetryConfig.FAST_STALE_MS
        )

    fun commFresh(now: Long) =
        fresh(
            lastCommMs,
            now,
            TelemetryConfig.COMM_STALE_MS
        )

    fun anyFlightFresh(now: Long) =
        stateFresh(now) ||
                attFresh(now) ||
                motFresh(now)

    fun anyFlightEver() =
        lastStateMs != 0L ||
                lastAttMs != 0L ||
                lastMotMs != 0L

    fun lastFlightMs() =
        maxOf(lastStateMs, lastAttMs, lastMotMs)

    private fun reject(reason: String): Boolean {
        rejected++
        lastRejected = reason
        return false
    }

    private fun flag(s: String): Boolean? =
        when (s) {
            "1" -> true
            "0" -> false
            else -> null
        }

    fun handleLine(
        line: String,
        nowMs: Long
    ): Boolean? {
        val p = line.trim()
            .split(",")
            .map { it.trim() }

        return when (p.firstOrNull()) {
            "ST" -> parseState(p, nowMs)
            "AT" -> parseAttitude(p, nowMs)
            "MO" -> parseMotors(p, nowMs)
            "CS" -> parseComm(p, nowMs)

            "STATE" -> {
                if (
                    p.size >= 3 &&
                    p[1].startsWith("MPU=")
                ) {
                    parseLegacyState(p, nowMs)
                } else {
                    null
                }
            }

            else -> null
        }
    }

    private fun parseLegacyState(
        p: List<String>,
        now: Long
    ): Boolean {
        val pairs = mutableMapOf<String, String>()

        for (item in p.drop(1)) {
            val index = item.indexOf('=')

            if (index <= 0) {
                return reject(
                    "STATE malformed field"
                )
            }

            val key = item.substring(0, index)
            val value = item.substring(index + 1)

            if (key in pairs) {
                return reject(
                    "STATE duplicate $key"
                )
            }

            pairs[key] = value
        }

        val mpu = when (pairs["MPU"]) {
            "OK" -> true
            "FAULT", "FAIL", "ERROR" -> false
            else -> return reject(
                "STATE invalid MPU"
            )
        }

        fun bit(name: String): Boolean? =
            pairs[name]?.let { flag(it) }

        val level = bit("LEVEL")
            ?: return reject("STATE invalid LEVEL")

        val link = bit("LINK")
            ?: return reject("STATE invalid LINK")

        val estop = bit("ESTOP")
            ?: return reject("STATE invalid ESTOP")

        val sim = bit("SIM")
            ?: return reject("STATE invalid SIM")

        val bench = bit("BENCH")
            ?: return reject("STATE invalid BENCH")

        val key = bit("KEY")
            ?: return reject("STATE invalid KEY")

        val esc = bit("ESC_ENABLED")
            ?: return reject(
                "STATE invalid ESC_ENABLED"
            )

        val mode = pairs["MODE"]
            ?.takeIf {
                it.isNotBlank() &&
                        it.length <= 32 &&
                        it.all { char ->
                            char.isUpperCase() ||
                                    char.isDigit() ||
                                    char == '_'
                        }
            }
            ?: return reject(
                "STATE invalid MODE"
            )

        if (lastStateMs == 0L) {
            events.add(
                "Flight telemetry started"
            )
        }

        if (
            flightMode != null &&
            flightMode != mode
        ) {
            events.add(
                "MODE $flightMode -> $mode"
            )
        }

        if (
            estopLatched != null &&
            estopLatched != estop
        ) {
            events.add(
                if (estop) {
                    "FLIGHT ESTOP LATCHED"
                } else {
                    "FLIGHT ESTOP CLEARED"
                }
            )
        }

        if (
            mpuOk != null &&
            mpuOk != mpu
        ) {
            events.add(
                if (mpu) {
                    "MPU OK"
                } else {
                    "MPU FAULT"
                }
            )
        }

        flightMode = mode
        mpuOk = mpu
        calibrated = level
        flightLink = link
        estopLatched = estop

        simState = when {
            estop -> "ESTOP"
            sim -> "ON"
            else -> "INACTIVE"
        }

        benchRunning = bench
        keyActive = key
        escEnabled = esc

        // LINK does not prove fresh joystick RC.
        rcFresh = null

        // BENCH=0 does not prove physical
        // flight-control arming state.
        disarmed = if (bench) false else null

        lastStateMs = now
        lastLegacyMs = now
        packets++

        return true
    }

    private fun parseState(
        p: List<String>,
        now: Long
    ): Boolean {
        if (p.size != 7) {
            return reject(
                "ST needs 7 fields"
            )
        }

        val sim = p[1]
        if (sim !in SIM_STATES) {
            return reject(
                "ST invalid simulation"
            )
        }

        val mpu = flag(p[2])
            ?: return reject("ST invalid MPU")

        val cal = flag(p[3])
            ?: return reject("ST invalid CAL")

        val es = flag(p[4])
            ?: return reject("ST invalid ESTOP")

        val dis = flag(p[5])
            ?: return reject("ST invalid DISARM")

        val rc = flag(p[6])
            ?: return reject("ST invalid RC")

        if (lastStateMs == 0L) {
            events.add(
                "Flight telemetry started"
            )
        }

        if (
            estopLatched != null &&
            estopLatched != es
        ) {
            events.add(
                if (es) {
                    "FLIGHT ESTOP LATCHED"
                } else {
                    "FLIGHT ESTOP CLEARED"
                }
            )
        }

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

    private fun parseAttitude(
        p: List<String>,
        now: Long
    ): Boolean {
        if (p.size != 4) {
            return reject(
                "AT needs 4 fields"
            )
        }

        val r = p[1].toIntOrNull()
            ?: return reject(
                "AT invalid roll"
            )

        val pitch = p[2].toIntOrNull()
            ?: return reject(
                "AT invalid pitch"
            )

        val z = p[3].toIntOrNull()
            ?: return reject(
                "AT invalid gyroZ"
            )

        if (
            r !in -1800..1800 ||
            pitch !in -1800..1800 ||
            z !in -30000..30000
        ) {
            return reject(
                "AT value out of range"
            )
        }

        rollDeg = r / 10f
        pitchDeg = pitch / 10f
        gyroZ = z / 10f

        lastAttMs = now
        packets++

        return true
    }

    private fun parseMotors(
        p: List<String>,
        now: Long
    ): Boolean {
        if (p.size != 5) {
            return reject(
                "MO needs 5 fields"
            )
        }

        val values = p.drop(1).map {
            it.toIntOrNull()
                ?: return reject(
                    "MO invalid value"
                )
        }

        if (values.any { it !in 0..100 }) {
            return reject(
                "MO outside 0..100"
            )
        }

        motors = values
        lastMotMs = now
        packets++

        return true
    }

    private fun parseComm(
        p: List<String>,
        now: Long
    ): Boolean {
        if (p.size != 3) {
            return reject(
                "CS needs 3 fields"
            )
        }

        val uart = flag(p[1])
            ?: return reject(
                "CS invalid UART"
            )

        val pilot = flag(p[2])
            ?: return reject(
                "CS invalid pilot"
            )

        uartAlive = uart
        pilotLink = pilot

        lastCommMs = now
        packets++

        return true
    }
}
