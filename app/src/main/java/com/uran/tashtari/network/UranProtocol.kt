package com.uran.tashtari.network

import com.uran.tashtari.model.DroneCommand
import java.util.UUID

object UranProtocol {
    const val DEVICE_NAME = "URAN_TASHTRI_COMM"
    const val AXIS_LIMIT = 20

    // Standard Nordic UART Service UUID (the team gave only the RX/TX UUIDs)
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // phone writes here
    val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // COMM notifies here

    const val DISARM = "DISARM\n"
    const val ESTOP = "ESTOP\n"

    // C,seq,M,roll,pitch,yaw,0\n
    fun control(c: DroneCommand): String =
        "C,${c.seq and 0xFF},M," +
                "${c.roll.coerceIn(-AXIS_LIMIT, AXIS_LIMIT)}," +
                "${c.pitch.coerceIn(-AXIS_LIMIT, AXIS_LIMIT)}," +
                "${c.yaw.coerceIn(-AXIS_LIMIT, AXIS_LIMIT)},0\n"
}