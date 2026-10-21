package com.example.data.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val deviceName: String, val ip: String) : ConnectionState
    data class Connected(val deviceName: String, val ip: String) : ConnectionState
    data class WaitingAuth(val deviceName: String, val message: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class CommandLog(
    val id: Long = System.currentTimeMillis(),
    val deviceName: String,
    val command: String,
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean = true
)
