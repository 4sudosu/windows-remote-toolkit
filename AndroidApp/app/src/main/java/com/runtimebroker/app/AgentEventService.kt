package com.runtimebroker.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Foreground service that streams /api/events (SSE) from the server and
 * posts a native notification whenever an agent connects
 * (ported from WinSysMonitor V2's AgentEventService).
 *
 * The MainActivity polling loop stays as a fallback list updater; this
 * service is the instant-push trigger for connect alerts.
 */
class AgentEventService : Service() {

    companion object {
        private const val CHANNEL_ID = "agent_alerts"
        private const val WATCH_ID = 1001

        @Volatile
        private var instance: AgentEventService? = null

        fun start(context: Context, baseUrl: String) {
            val i = Intent(context, AgentEventService::class.java)
                .putExtra("baseUrl", baseUrl)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        /** Called when notification settings change: refreshes the running service. */
        fun applyPrefs(context: Context) {
            val s = instance
            if (!Prefs.notifEnabled(context)) {
                s?.stopSelf()
            } else {
                s?.updateWatchingNotification()
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        createChannel()
        instance = this

        val baseUrl = intent?.getStringExtra("baseUrl")
        if (baseUrl.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= 26) {
            startForeground(WATCH_ID, watchingNotification())
        }

        listen(baseUrl.trimEnd('/') + "/api/events")
        return START_STICKY
    }

    private fun watchingNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(Prefs.notifIconRes(this))
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_connecting))
            .setOngoing(true)
            .build()

    private fun updateWatchingNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                startForeground(WATCH_ID, watchingNotification())
            } catch (_: Exception) {
            }
        }
    }

    private fun listen(eventsUrl: String) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        scope.launch {
            while (isActive) {
                var response: okhttp3.Response? = null
                try {
                    val request = Request.Builder()
                        .url(eventsUrl)
                        .header("X-Admin-Password", Prefs.password(this@AgentEventService))
                        .build()
                    response = client.newCall(request).execute()
                    val body = response.body
                    if (body == null) continue
                    val source = body.source()
                    while (isActive) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("event: agent-online")) {
                            val dataLine = source.readUtf8Line() ?: break
                            val payload = dataLine.removePrefix("data: ")
                            notifyAgentConnected(parseJson(payload))
                        }
                    }
                } catch (_: IOException) {
                    // server unreachable - retry below
                } catch (_: Exception) {
                    // malformed stream - retry below
                } finally {
                    try { response?.close() } catch (_: Exception) { }
                }
                if (isActive) delay(3000)
            }
        }
    }

    private fun parseJson(s: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(s)
            .forEach { m -> map[m.groupValues[1]] = m.groupValues[2] }
        return map
    }

    private fun notifyAgentConnected(info: Map<String, String>) {
        val host = info["hostname"] ?: info["machineName"] ?: "Unknown device"
        val machine = info["machineName"] ?: host
        Notifications.postNewAgent(this, host, machine)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Agent Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Alerts when a new device connects"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
