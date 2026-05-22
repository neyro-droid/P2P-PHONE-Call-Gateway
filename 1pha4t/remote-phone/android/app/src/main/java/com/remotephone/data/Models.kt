package com.remotephone.data

enum class CallStatus { IDLE, INCOMING, DIALING, ACTIVE }

enum class ConnectionStatus {
    STOPPED,
    CONNECTING,
    CONNECTED,
    FAILED
}

data class SimInfo(
    val slotIndex: Int,
    val subscriptionId: Int,
    val displayName: String,
    val number: String?
)

data class GatewayState(
    val sessionId: String = "",
    val sims: List<SimInfo> = emptyList(),
    val callStatus: CallStatus = CallStatus.IDLE,
    val callNumber: String = "",
    val clientConnected: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.STOPPED,
    val statusText: String = "Stopped",
    val logs: List<String> = emptyList()
)
