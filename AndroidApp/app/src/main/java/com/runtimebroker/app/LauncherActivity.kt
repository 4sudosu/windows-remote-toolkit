package com.runtimebroker.app

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Window
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

    companion object {
        /** Password only asked once per process lifetime. */
        var unlocked = false
    }

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
        findViewById<Button>(R.id.btnRunAll).setOnClickListener {
            startHosting(port = Prefs.hostPort(this), toast = true)
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<TextView>(R.id.tvDeveloper).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        if (!unlocked) showLoginGate()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun showLoginGate() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_unlock)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val input = dialog.findViewById<EditText>(R.id.etPassword)
        input.setOnEditorActionListener { _, _, _ -> unlockAttempt(dialog, input); true }

        dialog.findViewById<Button>(R.id.btnUnlock).setOnClickListener {
            unlockAttempt(dialog, input)
        }
        dialog.findViewById<Button>(R.id.btnTelegram).setOnClickListener {
            openUrl("https://t.me/verifiedharyanvi")
        }
        dialog.findViewById<Button>(R.id.btnInstagram).setOnClickListener {
            openUrl("https://www.instagram.com/4sudo.su")
        }
        dialog.findViewById<Button>(R.id.btnGitHub).setOnClickListener {
            openUrl("https://github.com/4sudosu")
        }
        dialog.findViewById<Button>(R.id.btnGmail).setOnClickListener {
            openUrl("mailto:4sudo.su@gmail.com")
        }
        dialog.show()
    }

    private fun unlockAttempt(dialog: Dialog, input: EditText) {
        val entered = input.text.toString()
        if (entered.isNotEmpty() && entered == Prefs.adminPassword(this)) {
            Prefs.saveAdminPassword(this, entered)
            unlocked = true
            dialog.dismiss()
        } else {
            input.error = getString(R.string.unlock_wrong)
            input.text?.clear()
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

    private fun startHosting(port: String, toast: Boolean) {
        Prefs.saveHost(this, "0.0.0.0", port)
        val password = Prefs.password(this).ifBlank { Prefs.DEFAULT_ADMIN_PASSWORD }
        Prefs.save(this, "http://127.0.0.1:$port", password)
        Prefs.saveServerMode(this, "start")
        NodeServerService.start(this)
        if (toast) {
            Toast.makeText(this, getString(R.string.launcher_server_started, port), Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptStartServer() {
        val portInput = EditText(this).apply {
            hint = getString(R.string.host_port_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(Prefs.hostPort(this@LauncherActivity))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.launcher_port_title)
            .setMessage(R.string.launcher_port_msg)
            .setView(portInput)
            .setPositiveButton(R.string.launcher_start) { _, _ ->
                val port = portInput.text.toString().trim()
                val parsed = port.toIntOrNull()
                if (parsed == null || parsed !in 1..65535) {
                    Toast.makeText(this, R.string.launcher_invalid_port, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startHosting(port = port, toast = true)
                startActivity(Intent(this, MainActivity::class.java))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}