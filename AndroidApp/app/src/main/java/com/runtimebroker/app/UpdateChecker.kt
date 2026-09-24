package com.runtimebroker.app

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Mandatory update check against this project's GitHub releases
 * (ported from WinSysMonitor V2's update gate).
 *
 * Compares the installed versionName with releases/latest and reports
 * the first .apk asset (falling back to the release page URL).
 */
class UpdateChecker(private val context: Context) {

    companion object {
        const val DEFAULT_REPO = "4sudosu/WindowRemoteToolkitV2"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class UpdateInfo(
        val latestVersion: String,
        val releaseUrl: String,
        val apkUrl: String,
        val releaseNotes: String?
    )

    interface UpdateCallback {
        fun onUpdateAvailable(info: UpdateInfo)
        fun onNoUpdate()
        fun onError(error: String)
    }

    fun checkForUpdates(githubRepo: String = DEFAULT_REPO, callback: UpdateCallback) {
        scope.launch {
            try {
                val currentVersion = getCurrentVersion()
                val latestRelease = fetchLatestRelease(githubRepo)

                if (latestRelease != null) {
                    if (isNewerVersion(latestRelease.latestVersion, currentVersion)) {
                        withContext(Dispatchers.Main) {
                            callback.onUpdateAvailable(
                                UpdateInfo(
                                    latestRelease.latestVersion,
                                    latestRelease.releaseUrl,
                                    latestRelease.apkUrl,
                                    latestRelease.releaseNotes
                                )
                            )
                        }
                    } else {
                        withContext(Dispatchers.Main) { callback.onNoUpdate() }
                    }
                } else {
                    withContext(Dispatchers.Main) { callback.onError("Failed to fetch release info") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.message ?: "Unknown error") }
            }
        }
    }

    private fun getCurrentVersion(): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "0.0.0"
        }
    }

    private data class ReleaseData(
        val latestVersion: String,
        val releaseUrl: String,
        val apkUrl: String,
        val releaseNotes: String?
    )

    private fun fetchLatestRelease(githubRepo: String): ReleaseData? = try {
        val url = "https://api.github.com/repos/$githubRepo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "RuntimeBroker-Android")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null

            val body = response.body?.string() ?: return@use null
            val json = JSONObject(body)

            val tagName = json.getString("tag_name")
            val htmlUrl = json.getString("html_url")
            val bodyText = json.optString("body").takeIf { it.isNotBlank() }
            val assets = json.optJSONArray("assets")
            val apkUrl = (0 until (assets?.length() ?: 0))
                .asSequence()
                .map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                ?.takeIf { it.isNotBlank() }
                ?: htmlUrl
            val version = tagName.removePrefix("v")
            ReleaseData(version, htmlUrl, apkUrl, bodyText)
        }
    } catch (e: Exception) {
        null
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val latestParts = latest.split('.').map { it.toInt() }
            val currentParts = current.split('.').map { it.toInt() }

            val maxSize = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxSize) {
                val l = if (i < latestParts.size) latestParts[i] else 0
                val c = if (i < currentParts.size) currentParts[i] else 0
                if (l > c) return true
                if (l < c) return false
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}
