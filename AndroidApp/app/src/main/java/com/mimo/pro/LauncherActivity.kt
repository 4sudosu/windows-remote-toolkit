package com.mimo.pro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class LauncherActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        updateStatus()

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenServer).setOnClickListener { openRunningServer() }
        findViewById<Button>(R.id.btnRunServer).setOnClickListener { toggleServer() }
    }

    private fun toggleServer() {
        if (NodeServerService.isRunning(this)) {
            NodeServerService.stop(this)
            Toast.makeText(this, R.string.server_stopped, Toast.LENGTH_SHORT).show()
        } else {
            NodeServerService.configure(this, NodeServerService.DEFAULT_HOST, NodeServerService.DEFAULT_PORT)
            NodeServerService.start(this)
            val ip = NodeServerService.localIps(this).firstOrNull()
            val port = NodeServerService.currentPort(this)
            Toast.makeText(this, getString(R.string.server_started, "http://${ip ?: "127.0.0.1"}:$port"), Toast.LENGTH_LONG).show()
            // Open the local dashboard (its login page asks for the admin password).
            // Node copies assets + boots on first run, so give it a head start.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (NodeServerService.isRunning(this)) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port")))
                    } catch (_: Exception) {
                    }
                }
            }, 2500)
        }
        updateServerState()
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

    /** Opens the running server's web dashboard inside the app (auto-signed-in). */
    private fun openRunningServer() {
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