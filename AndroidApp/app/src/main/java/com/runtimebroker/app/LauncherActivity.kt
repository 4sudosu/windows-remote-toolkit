package com.runtimebroker.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Entry screen (launched by the launcher aliases). Shows an owner-password gate,
 * then three ways to use the app: host the server here, connect to a running
 * server, or run the server on 0.0.0.0 for the whole network.
 */
class LauncherActivity : BaseActivity() {

    private lateinit var updateChecker: UpdateChecker
    private var updateChecked = false
    private var lastUpdateCheckTime: Long = 0
    private val updateCheckCacheMs = 3600000L // 1 hour

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        updateChecker = UpdateChecker(this)
        if (BuildConfig.UPDATE_GATE_ENABLED) {
            setLauncherActionsEnabled(false)
            checkForUpdates()
        } else {
            // No-update-check build: gate disabled, all actions open.
            updateChecked = true
            setLauncherActionsEnabled(true)
            findViewById<TextView>(R.id.tvStatus).text = getString(R.string.update_disabled)
        }
        updateStatus()

        findViewById<Button>(R.id.btnStartServer).setOnClickListener { promptStartServer() }
        findViewById<Button>(R.id.btnOpenDashboard).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java))
        }
        findViewById<Button>(R.id.btnRunAll).setOnClickListener { promptRunAllInterfaces() }
        findViewById<TextView>(R.id.tvDeveloper).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        if (::updateChecker.isInitialized) updateChecker.shutdown()
        super.onDestroy()
    }

    // ── V2 update gate: block the app until releases/latest is verified ──
    private fun checkForUpdates() {
        if (updateChecked) return

        val now = System.currentTimeMillis()
        if (lastUpdateCheckTime > 0 && (now - lastUpdateCheckTime) < updateCheckCacheMs) {
            updateChecked = true
            setLauncherActionsEnabled(true)
            return
        }

        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = getString(R.string.update_checking)
        updateChecker.checkForUpdates(UpdateChecker.DEFAULT_REPO, object : UpdateChecker.UpdateCallback {
            override fun onUpdateAvailable(info: UpdateChecker.UpdateInfo) {
                updateChecked = true
                lastUpdateCheckTime = System.currentTimeMillis()
                val intent = Intent(this@LauncherActivity, UpdateActivity::class.java).apply {
                    putExtra("latest_version", info.latestVersion)
                    putExtra("release_url", info.releaseUrl)
                    putExtra("apk_url", info.apkUrl)
                    putExtra("release_notes", info.releaseNotes)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                finish()
            }

            override fun onNoUpdate() {
                updateChecked = true
                lastUpdateCheckTime = System.currentTimeMillis()
                setLauncherActionsEnabled(true)
                updateStatus()
            }

            override fun onError(error: String) {
                updateChecked = false
                setLauncherActionsEnabled(false)
                status.text = getString(R.string.update_failed_title)
                AlertDialog.Builder(this@LauncherActivity)
                    .setTitle(R.string.update_failed_title)
                    .setMessage(R.string.update_failed_msg)
                    .setPositiveButton(R.string.update_retry) { _, _ ->
                        lastUpdateCheckTime = 0
                        checkForUpdates()
                    }
                    .setNegativeButton(R.string.update_exit) { _, _ -> finishAffinity() }
                    .setOnCancelListener { checkForUpdates() }
                    .setCancelable(false)
                    .show()
            }
        })
    }

    private fun setLauncherActionsEnabled(enabled: Boolean) {
        findViewById<Button>(R.id.btnStartServer).isEnabled = enabled
        findViewById<Button>(R.id.btnConnect).isEnabled = enabled
        findViewById<Button>(R.id.btnRunAll).isEnabled = enabled
        findViewById<Button>(R.id.btnOpenDashboard).isEnabled = enabled
        findViewById<TextView>(R.id.tvDeveloper).isEnabled = enabled
        if (!enabled) {
            findViewById<TextView>(R.id.tvStatus).text = getString(R.string.update_checking)
        }
    }

    

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val status = findViewById<TextView>(R.id.tvStatus)
        val port = Prefs.hostPort(this)
        status.text = when {
            NodeServerService.isRunning(this) -> {
                val ip = NodeServerService.localIps(this).firstOrNull() ?: "0.0.0.0"
                getString(R.string.launcher_status_running, ip, port)
            }
            Prefs.serverMode(this) == "start" -> getString(R.string.launcher_status_configured, port)
            Prefs.serverUrl(this).isNotBlank() ->
                getString(R.string.launcher_status_connected, Prefs.serverUrl(this))
            else -> getString(R.string.launcher_status_none)
        }
    }

    private fun startHosting(host: String, port: String, password: String, toast: Boolean) {
        Prefs.saveHost(this, host, port)
        Prefs.save(this, "http://127.0.0.1:$port", password)
        Prefs.saveServerMode(this, "start")
        NodeServerService.start(this)
        if (toast) {
            Toast.makeText(this, getString(R.string.launcher_server_started, port), Toast.LENGTH_SHORT).show()
        }
    }

    /** Start Server — binds to 127.0.0.1 ONLY (localhost, nobody else can access). */
    private fun promptStartServer() {
        val portInput = EditText(this).apply {
            hint = getString(R.string.host_port_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(Prefs.hostPort(this@LauncherActivity))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.launcher_port_title)
            .setMessage(R.string.launcher_port_msg_local)
            .setView(portInput)
            .setPositiveButton(R.string.launcher_start) { _, _ ->
                val port = portInput.text.toString().trim()
                val parsed = port.toIntOrNull()
                if (parsed == null || parsed !in 1..65535) {
                    Toast.makeText(this, R.string.launcher_invalid_port, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startHosting(
                    host = "127.0.0.1",
                    port = port,
                    password = Prefs.password(this).ifBlank { Prefs.DEFAULT_ADMIN_PASSWORD },
                    toast = true
                )
                startActivity(Intent(this, MainActivity::class.java))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Run Server on All Network Interfaces (0.0.0.0) — the whole network can
     * reach it, so a PASSWORD IS REQUIRED before the server starts.
     */
    private fun promptRunAllInterfaces() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val pwInput = EditText(this).apply {
            hint = getString(R.string.all_interfaces_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val portInput = EditText(this).apply {
            hint = getString(R.string.host_port_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(Prefs.hostPort(this@LauncherActivity))
        }
        layout.addView(pwInput)
        layout.addView(portInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.host_setup_title_all)
            .setMessage(R.string.all_interfaces_warning)
            .setView(layout)
            .setPositiveButton(R.string.launcher_start) { _, _ ->
                val pw = pwInput.text.toString().trim()
                if (pw.isBlank()) {
                    Toast.makeText(this, R.string.host_setup_password_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val port = portInput.text.toString().trim().ifBlank { "4777" }
                val parsed = port.toIntOrNull()
                if (parsed == null || parsed !in 1..65535) {
                    Toast.makeText(this, R.string.launcher_invalid_port, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startHosting(host = "0.0.0.0", port = port, password = pw, toast = true)
                startActivity(Intent(this, MainActivity::class.java))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}