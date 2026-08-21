package com.runtimebroker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val CHANNEL_AGENTS = "agent_connect"
    const val CHANNEL_SERVER = "node_server"

    fun ensureChannels(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val agents = NotificationChannel(
            CHANNEL_AGENTS,
            "Agent connect alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alert when a new agent comes online"
        }
        val server = NotificationChannel(
            CHANNEL_SERVER,
            "Embedded server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Runtime Broker server hosted on this device"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(agents)
        mgr.createNotificationChannel(server)
    }

    fun postNewAgent(context: Context, hostname: String, machine: String) {
        if (!Prefs.notifEnabled(context)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_MACHINE, machine)
        }
        val pi = PendingIntent.getActivity(
            context, machine.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val tone = Prefs.toneUri(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_AGENTS)
            .setSmallIcon(Prefs.notifIconRes(context))
            .setContentTitle("Agent connected")
            .setContentText("$hostname is now online")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (tone != null) {
            try {
                builder.setSound(tone)
            } catch (_: Exception) {
            }
        } else {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        }

        try {
            NotificationManagerCompat.from(context).notify(machine.hashCode(), builder.build())
        } catch (_: Exception) {
        }
    }

    fun postServerRunning(context: Context, ip: String, port: String) {
        try {
            NotificationManagerCompat.from(context).notify(0xBEEF, buildServerNotification(context, ip, port))
        } catch (_: Exception) {
        }
    }

    fun buildServerNotification(context: Context, ip: String, port: String): Notification {
        val open = Intent(context, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Runtime Broker server")
            .setContentText("Hosting agents on ${ip.ifBlank { "this device" }}:$port")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun cancelServer(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(0xBEEF)
        } catch (_: Exception) {
        }
    }
}