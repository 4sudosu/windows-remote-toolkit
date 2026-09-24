package com.runtimebroker.app

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {

    fun declaredDangerous(context: Context): List<String> {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            ?: return emptyList()
        val requested = info.requestedPermissions ?: return emptyList()
        return requested.filter { perm ->
            runCatching {
                val pi = pm.getPermissionInfo(perm, 0)
                (pi.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
                    PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
        }
    }

    fun missing(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < 33) return emptyList()
        return declaredDangerous(context).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasAll(context: Context): Boolean = missing(context).isEmpty()

    fun requestAll(activity: Activity, requestCode: Int) {
        val toRequest = missing(activity)
        if (toRequest.isNotEmpty()) {
            activity.requestPermissions(toRequest.toTypedArray(), requestCode)
        }
    }
}