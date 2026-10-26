package com.mimo.pro.api

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
    val error: String?,
    /** Set when a big result (camera video, big capture) was uploaded over
     *  HTTP instead of the WebSocket: download `${base}/api/file/${ref}`. */
    val ref: String? = null,
    val filename: String? = null,
    val mime: String? = null
)

interface LiveListener {
    fun onConnected()
    fun onFrame(imageBase64: String)
    /** Real remote cursor position as fractions of the frame (agent-provided). */
    fun onCursor(cx: Float, cy: Float) {}
    fun onError(message: String)
    fun onClosed()
}
