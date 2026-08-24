package com.runtimebroker.app

import android.content.Context

object ServerConfig {
    private const val PREFS_NAME = "runtimebroker_security"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_FAIL_COUNT = "device_fail_count"
    private const val KEY_BLOCKED = "device_blocked"

    const val MAX_CONNECT_ATTEMPTS = 3

    private fun sp(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDeviceId(context: Context): String {
        val sp = sp(context)
        var id = sp.getString(KEY_DEVICE_ID, null)
        if (id == null || id.isBlank()) {
            id = java.util.UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getFailCount(context: Context): Int =
        sp(context).getInt(KEY_FAIL_COUNT, 0)

    fun isBlocked(context: Context): Boolean =
        sp(context).getBoolean(KEY_BLOCKED, false)

    fun recordFailure(context: Context): Int {
        val sp = sp(context)
        val newCount = sp.getInt(KEY_FAIL_COUNT, 0) + 1
        sp.edit().putInt(KEY_FAIL_COUNT, newCount).apply()
        if (newCount >= MAX_CONNECT_ATTEMPTS) {
            sp.edit().putBoolean(KEY_BLOCKED, true).apply()
        }
        return newCount
    }

    fun clearFailures(context: Context) {
        sp(context).edit()
            .putInt(KEY_FAIL_COUNT, 0)
            .putBoolean(KEY_BLOCKED, false)
            .apply()
    }

    fun remainingAttempts(context: Context): Int =
        (MAX_CONNECT_ATTEMPTS - getFailCount(context)).coerceAtLeast(0)

    fun isMaxAttemptsReached(context: Context): Boolean =
        getFailCount(context) >= MAX_CONNECT_ATTEMPTS
}