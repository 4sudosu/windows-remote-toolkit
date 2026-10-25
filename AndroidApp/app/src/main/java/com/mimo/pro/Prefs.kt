package com.mimo.pro

import android.content.Context
import android.net.Uri

object Prefs {
    private const val NAME = "mimo_pro"
    const val DEFAULT_ADMIN_PASSWORD = ".\\itdtpadmin"
    private const val KEY_SERVER = "server_url"
    private const val KEY_PASSWORD = "password"
    private const val KEY_LAST_MACHINE = "last_machine"
    private const val KEY_LAST_HOST = "last_host"
    private const val KEY_NOTIF_TONE = "notif_tone"
    private const val KEY_NOTIF_TONE_KEY = "notif_tone_key"
    private const val KEY_NOTIF_TONE_URI = "notif_tone_uri"
    private const val KEY_NOTIF_ENABLED = "notif_enabled"
    private const val KEY_NOTIF_ICON = "notif_icon"
    private const val KEY_ACTIVE_TAB = "active_tab"
    private const val KEY_ADMIN_PASSWORD = "admin_password"
    private const val KEY_KNOWN_AGENTS = "known_agents"
    private const val KEY_REFRESH_SECS = "refresh_secs"
    private const val KEY_GEMINI_PROMPT = "gemini_default_prompt"

    const val DEFAULT_GEMINI_PROMPT =
        "Analyze this screenshot and describe what you see. Highlight any issues, errors, or unusual activity."

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

    // ---- Notifications -----------------------------------------------
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

    // ---- V2 live device list: persisted auto-refresh interval --------------
    /** Device-list poll interval in seconds (0 = off … but MainActivity always polls at least once). */
    fun refreshSecs(context: Context): Int =
        sp(context).getInt(KEY_REFRESH_SECS, 5)

    fun saveRefreshSecs(context: Context, secs: Int) {
        sp(context).edit().putInt(KEY_REFRESH_SECS, secs).apply()
    }

    // ---- Gemini AI (screenshot share-to-app, no API key) ------------------
    fun geminiPrompt(context: Context): String =
        sp(context).getString(KEY_GEMINI_PROMPT, DEFAULT_GEMINI_PROMPT)
            ?: DEFAULT_GEMINI_PROMPT

    fun saveGeminiPrompt(context: Context, prompt: String) {
        sp(context).edit().putString(KEY_GEMINI_PROMPT, prompt).apply()
    }
}