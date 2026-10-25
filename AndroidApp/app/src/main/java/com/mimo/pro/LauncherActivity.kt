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