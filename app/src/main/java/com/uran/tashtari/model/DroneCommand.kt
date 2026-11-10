package com.uran.tashtari.model

// BLE Simulation v0.1: mode is always M and throttle is always 0, so neither is stored.
// roll/pitch/yaw: -20..+20, seq: 0..255 (wraps)
data class DroneCommand(
    val seq: Int,
    val roll: Int = 0,
    val pitch: Int = 0,
    val yaw: Int = 0
)