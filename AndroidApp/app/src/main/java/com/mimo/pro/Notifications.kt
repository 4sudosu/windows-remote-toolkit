package com.mimo.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val CHANNEL_AGENTS = "agent_connect_v5"
    const val CHANNEL_TYPING = "typing_progress"
    const val ID_TYPING = 0xBEEF + 7
    const val ACTION_STOP_TYPING = "com.mimo.pro.STOP_TYPING"

    fun ensureChannels(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Delete old channels to force recreation
        mgr.deleteNotificationChannel("agent_connect_v2")
        mgr.deleteNotificationChannel("agent_connect_v3")
        mgr.deleteNotificationChannel("agent_connect_v4")
        mgr.deleteNotificationChannel("node_server")

        val agents = NotificationChannel(
            CHANNEL_AGENTS,
            "Agent connect alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alert when a new agent comes online"
            enableVibration(true)
            enableLights(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        val typing = NotificationChannel(
            CHANNEL_TYPING,
            "Paragraph typing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while a paragraph is being typed on a remote device"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(agents)
        mgr.createNotificationChannel(typing)
    }

    /**
     * Ongoing "typing in progress" notification with a STOP button so a running
     * paragraph can always be killed — even if the app screen was closed.
     */
    fun showTyping(context: Context) {
        try {
            val stopIntent = Intent(context, StopTypingReceiver::class.java).apply {
                action = ACTION_STOP_TYPING
            }
            val stopPi = PendingIntent.getBroadcast(
                context, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL_TYPING)
                .setSmallIcon(R.drawable.ic_keyboard)
                .setContentTitle("Typing in progress")
                .setContentText("Paragraph is being typed on the remote device")
                .addAction(R.drawable.ic_delete, "STOP TYPING", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            NotificationManagerCompat.from(context).notify(ID_TYPING, n)
        } catch (_: Exception) {
        }
    }

    fun cancelTyping(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(ID_TYPING)
        } catch (_: Exception) {
        }
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
        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val builder = NotificationCompat.Builder(context, CHANNEL_AGENTS)
            .setSmallIcon(Prefs.notifIconRes(context))
            .setContentTitle("Agent connected")
            .setContentText("$hostname is now online")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSound(tone ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 200, 100, 200))
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(machine.hashCode(), builder.build())
        } catch (_: Exception) {
        }
    }
}