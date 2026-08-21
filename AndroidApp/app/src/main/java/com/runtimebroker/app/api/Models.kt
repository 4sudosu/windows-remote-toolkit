package com.runtimebroker.app.api

data class AgentInfo(
    val machineName: String,
    val hostname: String,
    val model: String,
    val serial: String,
    val username: String,
    val os: String,
    val version: String,
    val ip: String,
    val online: Boolean,
    val lastSeen: String
)

data class CaptureResult(
    val success: Boolean,
    val image: String?,
    val at: String?,
    val error: String?
)

data class CommandResult(
    val success: Boolean,
    val output: String?,
    val data: Any?,
    val exitCode: Int,
    val error: String?
)

interface LiveListener {
    fun onConnected()
    fun onFrame(imageBase64: String)
    fun onError(message: String)
    fun onClosed()
}