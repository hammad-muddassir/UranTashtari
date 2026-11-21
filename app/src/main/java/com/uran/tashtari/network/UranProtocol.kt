package com.uran.tashtari.network

import com.uran.tashtari.model.DroneCommand
import java.util.Locale
import java.util.UUID

object UranProtocol {
    const val DEVICE_NAME = "URAN_TASHTRI_COMM"
    const val AXIS_LIMIT = 20

    // DO NOT enable without validated flight firmware.
    const val ENABLE_NONZERO_THROTTLE = false

    val SERVICE: UUID = UUID.fromString(
        "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    )

    val RX: UUID = UUID.fromString(
        "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    )

    val TX: UUID = UUID.fromString(
        "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
    )

    const val DISARM = "DISARM\n"
    const val ESTOP = "ESTOP\n"

    private val commands = setOf(
        "STATUS",
        "LEVEL",
        "SIM_OFF",
        "PREFLIGHT",
        "ARM",
        "MOTOR_TEST"
    )

    fun command(text: String): String? {
        val normalized =
            text.trim().uppercase(Locale.ROOT)

        return if (normalized in commands) {
            "$normalized\n"
        } else {
            null
        }
    }

    fun control(
        c: DroneCommand,
        flightReady: Boolean = false
    ): String {
        val throttle =
            if (ENABLE_NONZERO_THROTTLE && flightReady) {
                c.throttle.coerceIn(0, 100)
            } else {
                0
            }

        return "C,${c.seq and 0xFF},M," +
                "${c.roll.coerceIn(-AXIS_LIMIT, AXIS_LIMIT)}," +
                "${c.pitch.coerceIn(-AXIS_LIMIT, AXIS_LIMIT)}," +
                "${c.yaw.coerceIn(-AXIS_LIMIT, AXIS_LIMIT)}," +
                "$throttle\n"
    }
}
