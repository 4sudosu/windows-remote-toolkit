package com.mimo.pro

import android.content.Context

/**
 * Session-scoped connection override. When the Launcher opens the local-server
 * dashboard (extra base/password), DeviceActions and its child action screens
 * must talk to that SAME server instead of the stale Prefs values.
 */
object Connection {
    var baseUrl: String? = null
    var password: String? = null

    /** Base URL for action screens: session override > local running server > Prefs. */
    fun resolveBase(context: Context): String {
        baseUrl?.let { return it }
        if (NodeServerService.isRunning(context)) {
            return "http://127.0.0.1:${NodeServerService.currentPort(context)}"
        }
        return Prefs.serverUrl(context)
    }

    /** Password mirror of resolveBase(): the same winning tier must supply it. */
    fun resolvePassword(context: Context): String {
        password?.let { return it }
        if (NodeServerService.isRunning(context)) {
            return NodeServerService.adminPassword(context)
        }
        return Prefs.password(context)
    }

    fun setForDashboard(base: String, pwd: String) {
        android.util.Log.d("MimoConn", "setForDashboard base=$base pwdLen=${pwd.length}")
        baseUrl = base
        password = pwd
    }

    fun clear() {
        android.util.Log.d("MimoConn", "Connection.clear()")
        baseUrl = null
        password = null
    }
}
