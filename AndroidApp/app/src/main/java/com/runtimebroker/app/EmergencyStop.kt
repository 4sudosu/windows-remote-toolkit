package com.runtimebroker.app

import android.content.Context
import android.widget.Toast
import com.runtimebroker.app.api.RuntimeBrokerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

object EmergencyStop {
    fun request(context: Context, machineName: String = "") {
        val app = context.applicationContext
        val machine = machineName.ifBlank { Prefs.lastMachine(app) }
        if (machine.isBlank() || Prefs.serverUrl(app).isBlank()) {
            Toast.makeText(app, R.string.stop_all_no_device, Toast.LENGTH_SHORT).show()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(app), machine, Prefs.password(app), "stop_all", JSONObject()
            )
            Notifications.cancelTyping(app)
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(
                    app,
                    if (result.success) R.string.stop_all_done else R.string.stop_all_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
