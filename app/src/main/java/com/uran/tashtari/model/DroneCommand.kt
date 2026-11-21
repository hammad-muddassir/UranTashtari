package com.uran.tashtari.model

data class DroneCommand(
    val seq: Int,
    val roll: Int = 0,
    val pitch: Int = 0,
    val yaw: Int = 0,
    val throttle: Int = 0
)
