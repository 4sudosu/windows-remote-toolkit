package com.runtimebroker.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.runtimebroker.app.databinding.ActivityConnectBinding

/**
 * Connect-mode form: point the app at an existing Runtime Broker server
 * (IP + port). Mirrors the reference app's connect screen.
 */
class ConnectActivity : BaseActivity() {

    private lateinit var binding: ActivityConnectBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val cfg = Prefs.serverUrl(this)
        if (Prefs.serverMode(this) == "connect" && cfg.isNotBlank()) {
            val host = runCatching { Uri.parse(cfg).host }.getOrNull()
            val port = runCatching { Uri.parse(cfg).port }.getOrNull()
            if (!host.isNullOrBlank()) binding.inputIp.setText(host)
            if (port != null && port > 0) binding.inputPort.setText(port.toString())
        }

        binding.btnConnect.setOnClickListener {
            val ip = binding.inputIp.text.toString().trim()
            val port = binding.inputPort.text.toString().trim().ifBlank { Prefs.hostPort(this) }
            if (ip.isBlank()) {
                Toast.makeText(this, R.string.connect_enter_ip, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val url = if (ip.startsWith("http://") || ip.startsWith("https://")) {
                ip
            } else {
                "http://$ip:$port"
            }
            Prefs.save(this, url, Prefs.password(this))
            Prefs.saveServerMode(this, "connect")
            Toast.makeText(this, getString(R.string.connect_ok, url), Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}