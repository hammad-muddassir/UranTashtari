package com.uran.tashtari.model

// Matches the v0.1 contract in the team guide. NOT motor values.
data class DroneCommand(
    val seq: Int,
    val mode: String,        // "MANUAL" or "AUTO"
    val roll: Int = 0,       // -100..100
    val pitch: Int = 0,      // -100..100
    val yaw: Int = 0,        // -100..100
    val throttle: Int = 0    // 0..100
)