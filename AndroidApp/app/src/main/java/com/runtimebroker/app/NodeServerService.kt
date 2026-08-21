package com.runtimebroker.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
 *
 * NOTE: before building the APK run `npm install` inside
 * app/src/main/assets/nodejs-project/ so node_modules is bundled, and drop the
 * nodejs-mobile libnode.so binaries into app/libnode/bin/<abi>/ (see
 * app/libnode/README.txt). Without the binaries the app still builds, but the
 * embedded server stays dormant.
 */
class NodeServerService : Service() {

    companion object {
        private const val PREFS = "runtimebroker_nodejs"
        private const val APK_TIME = "apk_last_update_time"
        @Volatile private var started = false

        fun start(context: Context) {
            Notifications.ensureChannels(context)
            val intent = Intent(context, NodeServerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NodeServerService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == NodeServerService::class.java.name }
        }

        /** Local IPv4 addresses this device can be reached on (LAN WiFi first). */
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
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip = Prefs.hostIp(this)
        val port = Prefs.hostPort(this)
        val notif = Notifications.buildServerNotification(this, ip, port)
        try {
            ServiceCompat.startForeground(this, 0xBEEF, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (t: Throwable) {
            android.util.Log.e("RuntimeBrokerServer", "Foreground start denied", t)
            stopSelf()
            return START_NOT_STICKY
        }

        Thread {
            try {
                val nodeDir = File(filesDir, "nodejs-project")
                if (wasApkUpdated()) {
                    nodeDir.deleteRecursively()
                    copyAssetFolder(assets, "nodejs-project", nodeDir.absolutePath)
                    saveApkTime()
                }
                File(nodeDir, "server-config.json").writeText(
                    """
                    {"host":"0.0.0.0","port":${port.ifBlank { "4777" }},"adminPassword":"${Prefs.password(this)}"}
                    """.trimIndent()
                )
                startNodeEngine(nodeDir)
            } catch (t: Throwable) {
                android.util.Log.e("RuntimeBrokerServer", "Node start failed", t)
            }
        }.start()

        return START_STICKY
    }

    private fun startNodeEngine(nodeDir: File) {
        val scriptPath = File(nodeDir, "server.js").absolutePath
        try {
            if (!started) {
                started = true
                System.loadLibrary("native-lib")
                System.loadLibrary("node")
                startNodeWithArguments(arrayOf("node", scriptPath))
            }
        } catch (t: Throwable) {
            android.util.Log.e("RuntimeBrokerServer", "Node engine unavailable", t)
        }
    }

    private external fun startNodeWithArguments(arguments: Array<String>): Int

    override fun onDestroy() {
        Notifications.cancelServer(this)
        super.onDestroy()
    }

    // ---- asset copy helpers ----------------------------------------------
    private fun wasApkUpdated(): Boolean {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = prefs.getLong(APK_TIME, 0L)
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
            prefs.edit().putLong(APK_TIME, packageManager.getPackageInfo(packageName, 0).lastUpdateTime).apply()
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
            android.util.Log.e("RuntimeBrokerServer", "copyAssetFolder failed: $from", t)
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
            android.util.Log.e("RuntimeBrokerServer", "copyAsset failed: $from", t)
            false
        }
    }
}