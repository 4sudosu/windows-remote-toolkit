package com.mimo.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.AssetManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Foreground service that hosts the embedded Node.js server on this device.
 *
 * The bundled node project lives in APK assets (nodejs-project/) and is copied
 * into the app's files dir on first run / after an update. server-config.json
 * (host/port/admin password) is written there so server.js can read it, then
 * the nodejs-mobile libnode.so shared library is started on a background thread
 * via the native bridge in src/main/cpp/native-lib.cpp (node::Start).
 */
class NodeServerService : Service() {

    companion object {
        private const val PREFS = "mimo_node_server"
        private const val HOST_KEY = "host"
        private const val PORT_KEY = "port"
        private const val PASSWORD_KEY = "password"
        const val DEFAULT_HOST = "0.0.0.0"
        const val DEFAULT_PORT = 4777
        @Volatile private var started = false
        @Volatile private var lifetimeRunning = false

        /** Bind address + admin password for the embedded server. */
        fun configure(context: Context, host: String, port: Int, password: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(HOST_KEY, host).putInt(PORT_KEY, port).putString(PASSWORD_KEY, password).apply()
        }

        fun adminPassword(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PASSWORD_KEY, null)?.takeIf { it.isNotBlank() } ?: Prefs.password(context)

        fun currentPort(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(PORT_KEY, DEFAULT_PORT)

        fun start(context: Context) {
            lifetimeRunning = true
            val intent = Intent(context, NodeServerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NodeServerService::class.java))
        }

        fun isRunning(context: Context): Boolean = lifetimeRunning

        /** Local IPv4 addresses a PC can reach this device on (LAN WiFi first). */
        fun localIps(context: Context): List<String> {
            val out = LinkedHashSet<String>()
            try {
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                val ip = wm?.connectionInfo?.ipAddress ?: 0
                if (ip != 0) {
                    out.add(
                        "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
                    )
                }
            } catch (_: Throwable) {
            }
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    if (!intf.isUp) continue
                    val addresses = intf.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val a = addresses.nextElement()
                        if (!a.isLoopbackAddress && a is java.net.Inet4Address) {
                            a.hostAddress?.let { out.add(it) }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            return out.toList()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lifetimeRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = sp.getString(HOST_KEY, DEFAULT_HOST)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST
        val port = sp.getInt(PORT_KEY, DEFAULT_PORT)
        startForegroundSafe(host, port)

        Thread {
            try {
                val nodeDir = File(filesDir, "nodejs-project")
                if (wasApkUpdated()) {
                    // A failed asset copy can leave this path as a file. The
                    // Kotlin directory walker throws on that shape and kills
                    // the foreground service before Node ever starts.
                    if (nodeDir.exists()) {
                        if (nodeDir.isDirectory) nodeDir.deleteRecursively()
                        else nodeDir.delete()
                    }
                    copyAssetFolder(assets, "nodejs-project", nodeDir.absolutePath)
                    saveApkTime()
                }
                if (!nodeDir.exists()) throw IllegalStateException("nodejs-project asset copy failed")
                File(nodeDir, "server-config.json").writeText(
                    """
                    {"host":"$host","port":$port,"adminPassword":"${NodeServerService.adminPassword(this@NodeServerService)}"}
                    """.trimIndent()
                )
                startNodeEngine(nodeDir)
            } catch (t: Throwable) {
                android.util.Log.e("MimoServer", "Node start failed", t)
            }
        }.start()

        return START_STICKY
    }

    private fun startForegroundSafe(host: String, port: Int) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "embedded_server"
        mgr.createNotificationChannel(
            NotificationChannel(channelId, "Embedded server", NotificationManager.IMPORTANCE_LOW)
        )
        val ips = localIps(this)
        val text = "http://${ips.firstOrNull() ?: "127.0.0.1"}:$port · $host"
        val notif: Notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Mimo server running")
            .setContentText(text)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, 0xBEEF, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (t: Throwable) {
            android.util.Log.e("MimoServer", "Foreground start denied", t)
            stopSelf()
        }
    }

    override fun onDestroy() {
        lifetimeRunning = false
        super.onDestroy()
    }

    // shortcut: node::Start cannot be stopped in-process; "Stop Local Server" only kills the
    // foreground service notice, the port stays bound until the app process dies, upgrade when
    // nodejs-mobile exposes an API for stopping the engine
    private fun startNodeEngine(nodeDir: File) {
        val scriptPath = File(nodeDir, "server.js").absolutePath
        try {
            if (!started) {
                System.loadLibrary("native-lib")
                System.loadLibrary("node")
                startNodeWithArguments(arrayOf("node", scriptPath))
                started = true
            }
        } catch (t: Throwable) {
            android.util.Log.e("MimoServer", "Node engine unavailable", t)
        }
    }

    private external fun startNodeWithArguments(arguments: Array<String>): Int

    // ---- asset copy helpers ----------------------------------------------
    private fun wasApkUpdated(): Boolean {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = prefs.getLong("apk_last_update_time", 0L)
        var last = 0L
        try {
            last = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        } catch (_: PackageManager.NameNotFoundException) {
        }
        return last != prev || !File(filesDir, "nodejs-project").exists()
    }

    private fun saveApkTime() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            prefs.edit().putLong("apk_last_update_time", packageManager.getPackageInfo(packageName, 0).lastUpdateTime).apply()
        } catch (_: PackageManager.NameNotFoundException) {
        }
    }

    private fun copyAssetFolder(am: AssetManager, from: String, to: String): Boolean {
        return try {
            val files = am.list(from) ?: return false
            if (files.isEmpty()) {
                copyAsset(am, from, to)
            } else {
                File(to).mkdirs()
                files.forEach { f -> copyAssetFolder(am, "$from/$f", "$to/$f") }
                true
            }
        } catch (t: Throwable) {
            android.util.Log.e("MimoServer", "copyAssetFolder failed: $from", t)
            false
        }
    }

    private fun copyAsset(am: AssetManager, from: String, to: String): Boolean {
        return try {
            val input: InputStream = am.open(from)
            val outFile = File(to)
            outFile.parentFile?.mkdirs()
            val output: OutputStream = FileOutputStream(outFile)
            input.copyTo(output)
            output.flush()
            output.close()
            input.close()
            true
        } catch (t: Throwable) {
            android.util.Log.e("MimoServer", "copyAsset failed: $from", t)
            false
        }
    }
}
