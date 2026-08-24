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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

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