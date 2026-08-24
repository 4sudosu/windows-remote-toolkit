package com.runtimebroker.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.runtimebroker.app.api.RuntimeBrokerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Fired by the STOP button on the "typing in progress" notification. Kills the
 * remote paragraph session even when its activity is long gone.
 */
class StopTypingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val machine = Prefs.lastMachine(context)
                if (machine.isNotBlank()) {
                    RuntimeBrokerApi.command(
                        Prefs.serverUrl(context),
                        machine,
                        Prefs.password(context),
                        "stop_typing",
                        JSONObject()
                    )
                }
            } catch (_: Exception) {
            } finally {
                Notifications.cancelTyping(context)
                pending.finish()
            }
        }
    }
}
