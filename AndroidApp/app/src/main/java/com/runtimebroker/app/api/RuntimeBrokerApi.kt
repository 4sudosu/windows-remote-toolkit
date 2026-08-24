package com.runtimebroker.app.api

import com.runtimebroker.app.ConnectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object RuntimeBrokerApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun wsBaseUrl(baseUrl: String): String =
        baseUrl.trimEnd('/')
            .replaceFirst(Regex("^https"), "wss")
            .replaceFirst(Regex("^http"), "ws")

    suspend fun command(
        baseUrl: String,
        machineName: String,
        password: String,
        cmd: String,
        params: JSONObject = JSONObject()
    ): CommandResult = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append(baseUrl.trimEnd('/'))
                append("/api/monitor/")
                append(URLEncoder.encode(machineName, "UTF-8"))
                append("/command")
            }
            val payload = JSONObject()
                .put("password", password)
                .put("cmd", cmd)
                .put("params", params)
            // Hard ceiling a bit above the server-side timeout so the UI can
            // never hang on a lost request.
            val serverTimeoutSec = params.optInt("timeoutSec", 0).coerceAtLeast(35)
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonType))
                .build()
            val callClient = client.newBuilder()
                .callTimeout(serverTimeoutSec + 20L, TimeUnit.SECONDS)
                .build()
            callClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                    ?: return@withContext CommandResult(false, null, null, 0, "Empty response")
                val obj = JSONObject(body)
                CommandResult(
                    success = obj.optBoolean("success"),
                    output = obj.optString("output").ifBlank { null },
                    data = obj.opt("data"),
                    exitCode = obj.optInt("exitCode"),
                    error = obj.optString("error").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            CommandResult(false, null, null, 0, e.message ?: "Network error")
        }
    }

    suspend fun shell(baseUrl: String, machineName: String, password: String, command: String, admin: Boolean = false, timeoutSec: Int = 30): CommandResult =
        command(baseUrl, machineName, password, "shell_exec", JSONObject()
            .put("command", command)
            .put("timeoutSec", timeoutSec)
            .put("admin", admin))

    suspend fun shell(baseUrl: String, machineName: String, password: String, command: String, timeoutSec: Int = 30): CommandResult =
        shell(baseUrl, machineName, password, command, false, timeoutSec)

    suspend fun listProcesses(baseUrl: String, machineName: String, password: String): CommandResult =
        command(baseUrl, machineName, password, "list_processes")

    suspend fun listServices(baseUrl: String, machineName: String, password: String): CommandResult =
        command(baseUrl, machineName, password, "list_services")

    suspend fun listFiles(baseUrl: String, machineName: String, password: String, path: String): CommandResult =
        command(baseUrl, machineName, password, "list_files", JSONObject().put("path", path))

    fun connectLive(
        baseUrl: String,
        machineName: String,
        password: String,
        intervalMs: Int = 800,
        listener: LiveListener
    ): WebSocket {
        val url = buildString {
            append(wsBaseUrl(baseUrl))
            append("/ws/live/")
            append(URLEncoder.encode(machineName, "UTF-8"))
            append("?token=").append(URLEncoder.encode(password, "UTF-8"))
            append("&interval=").append(intervalMs)
        }
        val request = Request.Builder().url(url).build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "frame" -> listener.onFrame(obj.optString("image"))
                        "error" -> listener.onError(obj.optString("error").ifBlank { "Stream error" })
                    }
                } catch (e: Exception) {
                    listener.onError(e.message ?: "Bad frame")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "Connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed()
            }
        })
    }

    suspend fun agents(baseUrl: String, query: String): List<AgentInfo>? = withContext(Dispatchers.IO) {
        // Retry logic for better connection stability
        repeat(3) { attempt ->
            try {
                val url = buildString {
                    append(baseUrl.trimEnd('/'))
                    append("/api/agents")
                    if (query.isNotBlank()) {
                        append("?q=").append(URLEncoder.encode(query, "UTF-8"))
                    }
                }
                val request = Request.Builder().url(url).build()
                val callClient = client.newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(15, TimeUnit.SECONDS)
                    .build()
                callClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (attempt == 2) return@withContext null
delay(1000L * (attempt + 1))
                        return@withContext null
                    }
                    val body = resp.body?.string() ?: return@withContext null
                    val arr = JSONArray(body)
                    return@withContext (0 until arr.length()).map { i -> parseAgent(arr.getJSONObject(i)) }
                }
            } catch (e: Exception) {
                if (attempt == 2) return@withContext null
                delay(1000L * (attempt + 1))
            }
        }
        null
    }

    suspend fun capture(baseUrl: String, machineName: String, password: String): CaptureResult =
        withContext(Dispatchers.IO) {
            try {
                val url = buildString {
                    append(baseUrl.trimEnd('/'))
                    append("/api/monitor/")
                    append(URLEncoder.encode(machineName, "UTF-8"))
                    append("/screenshot")
                }
                val payload = JSONObject().put("password", password).toString()
                val request = Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string()
                        ?: return@withContext CaptureResult(false, null, null, "Empty response")
                    val obj = JSONObject(body)
                    CaptureResult(
                        success = obj.optBoolean("success"),
                        image = obj.optString("image").ifBlank { null },
                        at = obj.optString("at").ifBlank { null },
                        error = obj.optString("error").ifBlank { null }
                    )
                }
            } catch (e: Exception) {
                CaptureResult(false, null, null, e.message ?: "Network error")
            }
        }

    private fun parseAgent(o: JSONObject) = AgentInfo(
        machineName = o.optString("machineName"),
        hostname = o.optString("hostname"),
        model = o.optString("model"),
        serial = o.optString("serial"),
        username = o.optString("username"),
        os = o.optString("os"),
        version = o.optString("version"),
        ip = o.optString("ip"),
        online = o.optBoolean("online"),
        lastSeen = o.optString("lastSeen")
    )

    // Device connection check (used by ConnectActivity for 3-attempt lock)
    suspend fun checkConnection(baseUrl: String, password: String, deviceId: String): ConnectActivity.ConnectionResult =
        withContext(Dispatchers.IO) {
            try {
                val url = buildString {
                    append(baseUrl.trimEnd('/'))
                    append("/api/config")
                }
                val payload = JSONObject()
                    .put("password", password)
                    .put("deviceId", deviceId)
                val request = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: return@withContext ConnectActivity.ConnectionResult.Error("Empty response")
                    val obj = JSONObject(body)
                    when (resp.code) {
                        200 -> ConnectActivity.ConnectionResult.Success(obj.optString("url", baseUrl))
                        401, 403 -> ConnectActivity.ConnectionResult.AuthFailed(obj.optString("error", "Invalid credentials"))
                        423 -> ConnectActivity.ConnectionResult.AuthFailed("Device blocked")
                        else -> ConnectActivity.ConnectionResult.Error(obj.optString("error", "HTTP ${resp.code}"))
                    }
                }
            } catch (e: Exception) {
                ConnectActivity.ConnectionResult.Error(e.message ?: "Network error")
            }
        }

    // Device status check (used by "Check Status" button)
    suspend fun checkDeviceStatus(baseUrl: String, deviceId: String): ConnectActivity.ConnectionResult =
        withContext(Dispatchers.IO) {
            try {
                val url = buildString {
                    append(baseUrl.trimEnd('/'))
                    append("/api/config/status")
                }
                val payload = JSONObject().put("deviceId", deviceId)
                val request = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: return@withContext ConnectActivity.ConnectionResult.Error("Empty response")
                    val obj = JSONObject(body)
                    when (resp.code) {
                        200 -> ConnectActivity.ConnectionResult.Success(obj.optString("message", "OK"))
                        403 -> ConnectActivity.ConnectionResult.AuthFailed(obj.optString("error", "Still locked"))
                        else -> ConnectActivity.ConnectionResult.Error(obj.optString("error", "HTTP ${resp.code}"))
                    }
                }
            } catch (e: Exception) {
                ConnectActivity.ConnectionResult.Error(e.message ?: "Network error")
            }
        }
}