package com.runtimebroker.app

import android.content.Context
import android.net.Uri

object Prefs {
    private const val NAME = "runtimebroker"
    const val DEFAULT_ADMIN_PASSWORD = ".\\itdtpadmin"
    private const val KEY_SERVER = "server_url"
    private const val KEY_PASSWORD = "password"
    private const val KEY_LAST_MACHINE = "last_machine"
    private const val KEY_LAST_HOST = "last_host"
    private const val KEY_THEME = "theme"
    private const val KEY_NOTIF_TONE = "notif_tone"
    private const val KEY_NOTIF_TONE_KEY = "notif_tone_key"
    private const val KEY_NOTIF_TONE_URI = "notif_tone_uri"
    private const val KEY_NOTIF_ENABLED = "notif_enabled"
    private const val KEY_NOTIF_ICON = "notif_icon"
    private const val KEY_APP_ICON = "app_icon"
    private const val KEY_ACTIVE_TAB = "active_tab"
    private const val KEY_ADMIN_PASSWORD = "admin_password"
    private const val KEY_KNOWN_AGENTS = "known_agents"
    private const val KEY_SERVER_MODE = "server_mode"
    private const val KEY_HOST_IP = "host_ip"
    private const val KEY_HOST_PORT = "host_port"

    private fun sp(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String =
        sp(context).getString(KEY_SERVER, "").orEmpty()

    fun password(context: Context): String =
        sp(context).getString(KEY_PASSWORD, "").orEmpty()

    fun save(context: Context, serverUrl: String, password: String) {
        sp(context).edit()
            .putString(KEY_SERVER, serverUrl.trim())
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun lastMachine(context: Context): String =
        sp(context).getString(KEY_LAST_MACHINE, "").orEmpty()

    fun lastHost(context: Context): String =
        sp(context).getString(KEY_LAST_HOST, "").orEmpty()

    fun saveLastDevice(context: Context, machine: String, host: String) {
        sp(context).edit()
            .putString(KEY_LAST_MACHINE, machine)
            .putString(KEY_LAST_HOST, host)
            .apply()
    }

    // ---- Appearance / notifications -------------------------------------
    fun themeIndex(context: Context): Int =
        sp(context).getInt(KEY_THEME, 0)

    fun saveTheme(context: Context, index: Int) {
        sp(context).edit().putInt(KEY_THEME, index).apply()
    }

    fun notifTone(context: Context): String =
        sp(context).getString(KEY_NOTIF_TONE, "").orEmpty()

    fun saveNotifTone(context: Context, uri: String) {
        sp(context).edit().putString(KEY_NOTIF_TONE, uri).apply()
    }

    fun notifEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_NOTIF_ENABLED, true)

    fun saveNotifEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_NOTIF_ENABLED, enabled).apply()
    }

    /** tone key: "system" | "chime" | "alert" | "ding" | "soft" | "custom" */
    fun notifToneKey(context: Context): String =
        sp(context).getString(KEY_NOTIF_TONE_KEY, "system").orEmpty()

    fun saveNotifToneKey(context: Context, key: String) {
        sp(context).edit().putString(KEY_NOTIF_TONE_KEY, key).apply()
    }

    fun customToneUri(context: Context): String? =
        sp(context).getString(KEY_NOTIF_TONE_URI, null)

    fun saveCustomToneUri(context: Context, uri: String?) {
        sp(context).edit().putString(KEY_NOTIF_TONE_URI, uri).apply()
    }

    /** Resolve the Uri to play for the chosen tone. Null => system default. */
    fun toneUri(context: Context): Uri? {
        val raw = when (notifToneKey(context)) {
            "chime" -> R.raw.tone_chime
            "alert" -> R.raw.tone_alert
            "ding" -> R.raw.tone_ding
            "soft" -> R.raw.tone_soft
            else -> null
        }
        if (raw != null) {
            return Uri.parse("android.resource://${context.packageName}/$raw")
        }
        return if (notifToneKey(context) == "custom") {
            customToneUri(context)?.let(Uri::parse)
                ?: notifTone(context).takeIf { it.isNotBlank() }?.let(Uri::parse)
        } else {
            notifTone(context).takeIf { it.isNotBlank() }?.let(Uri::parse)
        }
    }

    fun notifIcon(context: Context): String =
        sp(context).getString(KEY_NOTIF_ICON, "ic_notify").orEmpty()

    fun saveNotifIcon(context: Context, resName: String) {
        sp(context).edit().putString(KEY_NOTIF_ICON, resName).apply()
    }

    fun notifIconRes(context: Context): Int {
        val id = context.resources.getIdentifier(notifIcon(context), "drawable", context.packageName)
        return if (id != 0) id else R.drawable.ic_notify
    }

    fun appIcon(context: Context): String =
        sp(context).getString(KEY_APP_ICON, "Default").orEmpty()

    fun saveAppIcon(context: Context, key: String) {
        sp(context).edit().putString(KEY_APP_ICON, key).apply()
    }

    /** Owner password for the app lock gate (default matches the reference). */
    fun adminPassword(context: Context): String =
        sp(context).getString(KEY_ADMIN_PASSWORD, "Alok@1234").orEmpty()

    fun saveAdminPassword(context: Context, password: String) {
        sp(context).edit().putString(KEY_ADMIN_PASSWORD, password).apply()
    }

    fun activeTab(context: Context): Int =
        sp(context).getInt(KEY_ACTIVE_TAB, 0)

    fun saveActiveTab(context: Context, tab: Int) {
        sp(context).edit().putInt(KEY_ACTIVE_TAB, tab).apply()
    }

    fun knownAgents(context: Context): Set<String> =
        sp(context).getStringSet(KEY_KNOWN_AGENTS, emptySet()) ?: emptySet()

    fun saveKnownAgents(context: Context, agents: Set<String>) {
        sp(context).edit().putStringSet(KEY_KNOWN_AGENTS, agents).apply()
    }

    // ---- Embedded server mode -------------------------------------------
    /** "start" = host the server from this device, "connect" = join an existing one. */
    fun serverMode(context: Context): String =
        sp(context).getString(KEY_SERVER_MODE, "connect").orEmpty()

    fun saveServerMode(context: Context, mode: String) {
        sp(context).edit().putString(KEY_SERVER_MODE, mode).apply()
    }

    fun hostIp(context: Context): String =
        sp(context).getString(KEY_HOST_IP, "0.0.0.0").orEmpty()

    fun hostPort(context: Context): String =
        sp(context).getString(KEY_HOST_PORT, "4777").orEmpty()

    fun saveHost(context: Context, ip: String, port: String) {
        sp(context).edit()
            .putString(KEY_HOST_IP, ip.ifBlank { "0.0.0.0" })
            .putString(KEY_HOST_PORT, port.ifBlank { "4777" })
            .apply()
    }
}