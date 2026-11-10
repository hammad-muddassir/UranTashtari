package com.uran.tashtari.network

import com.uran.tashtari.model.DroneCommand
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LinkStatus { IDLE, SCANNING, CONNECTING, READY }

data class LinkState(val status: LinkStatus, val message: String)

interface CommandSender {
    val state: StateFlow<LinkState>
    val replies: SharedFlow<String>      // lines notified by COMM
    fun connect()
    fun disconnect()
    fun sendControl(command: DroneCommand): Boolean
    fun sendDisarm(): Boolean
    fun sendEstop(): Boolean
}

// For UI work without hardware. Nothing leaves the phone.
class FakeCommandSender : CommandSender {
    private val _state = MutableStateFlow(LinkState(LinkStatus.IDLE, "Simulated, not connected"))
    override val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _replies = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val replies: SharedFlow<String> = _replies.asSharedFlow()

    override fun connect() { _state.value = LinkState(LinkStatus.READY, "Simulated link") }
    override fun disconnect() { _state.value = LinkState(LinkStatus.IDLE, "Simulated, not connected") }
    override fun sendControl(command: DroneCommand): Boolean = _state.value.status == LinkStatus.READY
    override fun sendDisarm(): Boolean { _replies.tryEmit("SIM DISARM"); return true }
    override fun sendEstop(): Boolean { _replies.tryEmit("SIM ESTOP"); return true }
}