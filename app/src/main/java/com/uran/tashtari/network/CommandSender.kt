package com.uran.tashtari.network

import com.uran.tashtari.model.DroneCommand
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LinkStatus {
    IDLE, SCANNING, CONNECTING, READY
}

data class LinkState(
    val status: LinkStatus,
    val message: String
)

interface CommandSender {
    val state: StateFlow<LinkState>

    val replies: SharedFlow<String>
    val writes: SharedFlow<String>

    fun connect()
    fun disconnect()

    fun sendControl(command: DroneCommand): Boolean
    fun sendCommand(command: String): Boolean
    fun sendDisarm(): Boolean
    fun sendEstop(): Boolean
}

class FakeCommandSender : CommandSender {

    private val _state = MutableStateFlow(
        LinkState(
            LinkStatus.IDLE,
            "Simulated, not connected"
        )
    )

    override val state: StateFlow<LinkState> =
        _state.asStateFlow()

    private val _replies = MutableSharedFlow<String>(
        extraBufferCapacity = 32
    )

    override val replies: SharedFlow<String> =
        _replies.asSharedFlow()

    private val _writes = MutableSharedFlow<String>(
        extraBufferCapacity = 32
    )

    override val writes: SharedFlow<String> =
        _writes.asSharedFlow()

    override fun connect() {
        _state.value = LinkState(
            LinkStatus.READY,
            "Simulated link"
        )
    }

    override fun disconnect() {
        _state.value = LinkState(
            LinkStatus.IDLE,
            "Simulated, not connected"
        )
    }

    override fun sendControl(
        command: DroneCommand
    ): Boolean {
        if (_state.value.status != LinkStatus.READY) {
            return false
        }

        _writes.tryEmit(
            UranProtocol.control(command).trim()
        )

        return true
    }

    override fun sendCommand(command: String): Boolean {
        val packet = UranProtocol.command(command)
            ?: return false

        if (_state.value.status != LinkStatus.READY) {
            return false
        }

        _writes.tryEmit(packet.trim())
        return true
    }

    override fun sendDisarm(): Boolean {
        if (_state.value.status != LinkStatus.READY) {
            return false
        }

        _writes.tryEmit("DISARM")
        _replies.tryEmit("SIM,DISARM")
        return true
    }

    override fun sendEstop(): Boolean {
        if (_state.value.status != LinkStatus.READY) {
            return false
        }

        _writes.tryEmit("ESTOP")
        _replies.tryEmit("SIM,ESTOP")
        return true
    }
}
