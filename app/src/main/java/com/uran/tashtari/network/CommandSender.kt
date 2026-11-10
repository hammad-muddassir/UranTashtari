package com.uran.tashtari.network

import com.uran.tashtari.model.DroneCommand

interface CommandSender {
    fun send(command: DroneCommand): String
}

// Simulated sender: nothing leaves the phone. Later you add HttpCommandSender.
class FakeCommandSender : CommandSender {
    override fun send(command: DroneCommand): String =
        "SIMULATED #${command.seq} ${command.mode} " +
                "ROLL=${command.roll} PITCH=${command.pitch} " +
                "YAW=${command.yaw} THR=${command.throttle}"
}