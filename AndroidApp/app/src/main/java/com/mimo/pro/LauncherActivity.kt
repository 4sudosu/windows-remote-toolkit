package com.mimo.pro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

class LauncherActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        updateStatus()

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenServer).setOnClickListener { openRunningServer() }
        findViewById<Button>(R.id.btnLocalServer).setOnClickListener { openLocalDashboard() }
        findViewById<Button>(R.id.btnRunServer).setOnClickListener { toggleServer() }
    }

    /** While the local server runs, a "Local Server" button appears to open its dashboard. */
    private fun updateConnectButton() {
        val local = findViewById<Button>(R.id.btnLocalServer)
        local.visibility = if (NodeServerService.isRunning(this)) Button.VISIBLE else Button.GONE
    }

    private fun openLocalDashboard() {
        val port = NodeServerService.currentPort(this)
        val d = Intent(this, DashboardActivity::class.java)
        d.putExtra(DashboardActivity.EXTRA_BASE_URL, "http://127.0.0.1:$port")
        d.putExtra(DashboardActivity.EXTRA_PASSWORD, NodeServerService.adminPassword(this))
        startActivity(d)
    }

    private fun toggleServer() {
        if (NodeServerService.isRunning(this)) {
            NodeServerService.stop(this)
            Toast.makeText(this, R.string.server_stopped, Toast.LENGTH_SHORT).show()
            updateServerState()
            return
        }
        // Ask to create the admin password for the local server before it boots.
        val input = android.widget.EditText(this).apply {
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            hint = getString(R.string.local_password_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.local_password_title)
            .setMessage(R.string.local_password_desc)
            .setView(input)
            .setPositiveButton(R.string.local_password_create) { _, _ ->
                val pw = input.text.toString().trim()
                if (pw.isBlank()) {
                    Toast.makeText(this, R.string.password_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startLocalServer(pw)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startLocalServer(password: String) {
        NodeServerService.configure(this, NodeServerService.DEFAULT_HOST, NodeServerService.DEFAULT_PORT, password)
        NodeServerService.start(this)
        val ip = NodeServerService.localIps(this).firstOrNull()
        val port = NodeServerService.currentPort(this)
        Toast.makeText(this, getString(R.string.server_started, "http://${ip ?: "127.0.0.1"}:$port"), Toast.LENGTH_LONG).show()
        updateServerState()
        updateConnectButton()
        // Node copies assets + boots on first run; open the in-app dashboard then.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (NodeServerService.isRunning(this)) {
                val d = Intent(this, DashboardActivity::class.java)
                d.putExtra(DashboardActivity.EXTRA_BASE_URL, "http://127.0.0.1:$port")
                d.putExtra(DashboardActivity.EXTRA_PASSWORD, password)
                startActivity(d)
            }
        }, 2500)
    }

    /** Shows the embedded-server state under the action buttons. */
    private fun updateServerState() {
        val ip = NodeServerService.localIps(this).firstOrNull()
        val state = findViewById<TextView>(R.id.tvServerState)
        val run = findViewById<Button>(R.id.btnRunServer)
        if (NodeServerService.isRunning(this) && ip != null) {
            state.visibility = TextView.VISIBLE
            state.text = getString(R.string.server_state_running, "http://$ip:${NodeServerService.currentPort(this)}")
            run.text = getString(R.string.stop_server)
        } else {
            state.visibility = TextView.GONE
            run.text = getString(R.string.run_server_all_interfaces)
        }
    }

    /** Opens the local server's dashboard when it runs, else the connected (Render) server's. */
    private fun openRunningServer() {
        if (NodeServerService.isRunning(this)) {
            openLocalDashboard()
            return
        }
        val url = Prefs.serverUrl(this)
        if (url.isBlank()) {
            Toast.makeText(this, R.string.open_server_none, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, DashboardActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateServerState()
        updateConnectButton()
    }

    private fun updateStatus() {
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = when {
            Prefs.serverUrl(this).isNotBlank() ->
                getString(R.string.launcher_status_connected, Prefs.serverUrl(this))
            else -> getString(R.string.launcher_status_none)
        }
    }
}