package com.uran.tashtari.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object TelemetryRelayFrame {
    private const val VERSION: Byte = 1
    private const val ATTITUDE: Byte = 1
    private const val OUTPUTS: Byte = 2
    private const val STOP: Byte = 3

    fun encode(line: String): ByteArray? = when {
        line.startsWith("AT,") -> attitude(line)
        line.startsWith("PWM,") -> outputs(line)
        line.startsWith("STOP,") || line.startsWith("FLIGHT_STOP_REASON,") -> stop(line)
        else -> null
    }

    private fun attitude(line: String): ByteArray? {
        val fields = line.split(',')
        if (fields.size != 4) return null
        val roll = fields[1].toIntOrNull()?.takeIf { it in -1800..1800 } ?: return null
        val pitch = fields[2].toIntOrNull()?.takeIf { it in -1800..1800 } ?: return null
        val yaw = fields[3].toIntOrNull()?.takeIf { it in -30000..30000 } ?: return null
        return packet(ATTITUDE, 8)
            .putShort(roll.toShort()).putShort(pitch.toShort()).putShort(yaw.toShort()).array()
    }

    private fun outputs(line: String): ByteArray? {
        val fields = line.split(',')
        if (fields.size != 9) return null
        val labels = listOf("A", "T", "R", "P", "FL", "FR", "RR", "RL")
        val values = fields.drop(1).zip(labels).map { (field, key) ->
            if (!field.startsWith("$key=")) return null
            field.substringAfter('=').toIntOrNull() ?: return null
        }
        if (values[0] !in 0..1 || values[1] !in 0..100 ||
            values[2] !in -180..180 || values[3] !in -180..180 ||
            values.drop(4).any { it !in 0..3000 }) return null
        return packet(OUTPUTS, 16)
            .put(values[0].toByte()).put(values[1].toByte())
            .putShort(values[2].toShort()).putShort(values[3].toShort())
            .apply { values.drop(4).forEach { putShort(it.toShort()) } }
            .array()
    }

    private fun stop(line: String): ByteArray? {
        val reason = line.substringAfter(',').substringBefore(',')
        if (reason.isEmpty() || reason.any { it !in 'A'..'Z' && it !in '0'..'9' && it != '_' }) return null
        val bytes = reason.take(18).toByteArray(Charsets.US_ASCII)
        return packet(STOP, 2 + bytes.size).put(bytes).array()
    }

    private fun packet(type: Byte, size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).put(VERSION).put(type)
}
