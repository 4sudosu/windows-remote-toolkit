package com.runtimebroker.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityConnectBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConnectActivity : BaseActivity() {

    private lateinit var binding: ActivityConnectBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Initialize device ID
        val deviceId = ServerConfig.getDeviceId(this)

        val cfg = Prefs.serverUrl(this)
        if (Prefs.serverMode(this) == "connect" && cfg.isNotBlank()) {
            val host = runCatching { Uri.parse(cfg).host }.getOrNull()
            val port = runCatching { Uri.parse(cfg).port }.getOrNull()
            if (!host.isNullOrBlank()) binding.inputIp.setText(host)
            if (port != null && port > 0) binding.inputPort.setText(port.toString())
        }
        binding.inputPassword.setText(Prefs.password(this))

        updateLockUI()

        binding.btnConnect.setOnClickListener {
            if (ServerConfig.isBlocked(this)) {
                showBlockedToast()
                return@setOnClickListener
            }
            attemptConnection()
        }

        binding.btnCheckStatus.setOnClickListener {
            checkStatusWithServer()
        }
    }

    private fun attemptConnection() {
        val ip = binding.inputIp.text.toString().trim()
        val port = binding.inputPort.text.toString().trim().ifBlank { Prefs.hostPort(this) }
        val pw = binding.inputPassword.text.toString().trim()
        if (ip.isBlank()) {
            Toast.makeText(this, R.string.connect_enter_ip, Toast.LENGTH_SHORT).show()
            return
        }
        if (pw.isBlank()) {
            Toast.makeText(this, R.string.password_required, Toast.LENGTH_SHORT).show()
            return
        }
        val url = if (ip.startsWith("http://") || ip.startsWith("https://")) {
            ip
        } else {
            "http://$ip:$port"
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = RuntimeBrokerApi.checkConnection(url, pw, ServerConfig.getDeviceId(this@ConnectActivity))
            runOnUiThread {
                when (result) {
                    is ConnectionResult.Success -> {
                        ServerConfig.clearFailures(this@ConnectActivity)
                        Prefs.save(this@ConnectActivity, url, pw)
                        Prefs.saveServerMode(this@ConnectActivity, "connect")
                        Toast.makeText(this@ConnectActivity, getString(R.string.connect_ok, url), Toast.LENGTH_SHORT).show()
                        startActivity(
                            Intent(this@ConnectActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        finish()
                    }
                    is ConnectionResult.AuthFailed -> {
                        val attempts = ServerConfig.recordFailure(this@ConnectActivity)
                        updateLockUI()
                        if (ServerConfig.isBlocked(this@ConnectActivity)) {
                            showBlockedToast()
                        } else {
                            Toast.makeText(this@ConnectActivity,
                                getString(R.string.connect_auth_failed, attempts, ServerConfig.MAX_CONNECT_ATTEMPTS),
                                Toast.LENGTH_LONG).show()
                        }
                    }
                    is ConnectionResult.Error -> {
                        Toast.makeText(this@ConnectActivity, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun checkStatusWithServer() {
        val url = Prefs.serverUrl(this)
        if (url.isBlank()) {
            Toast.makeText(this, R.string.connect_enter_ip, Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnCheckStatus.isEnabled = false
        binding.btnCheckStatus.text = getString(R.string.checking_status)

        lifecycleScope.launch(Dispatchers.IO) {
            val result = RuntimeBrokerApi.checkDeviceStatus(url, ServerConfig.getDeviceId(this@ConnectActivity))
            runOnUiThread {
                binding.btnCheckStatus.isEnabled = true
                binding.btnCheckStatus.text = getString(R.string.check_status)
                when (result) {
                    is ConnectionResult.Success -> {
                        ServerConfig.clearFailures(this@ConnectActivity)
                        updateLockUI()
                        Toast.makeText(this@ConnectActivity, R.string.status_unlocked, Toast.LENGTH_SHORT).show()
                    }
                    is ConnectionResult.AuthFailed -> {
                        // Still blocked, keep UI locked
                        Toast.makeText(this@ConnectActivity, R.string.status_still_locked, Toast.LENGTH_SHORT).show()
                    }
                    is ConnectionResult.Error -> {
                        Toast.makeText(this@ConnectActivity, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateLockUI() {
        val blocked = ServerConfig.isBlocked(this)
        val attempts = ServerConfig.getFailCount(this)
        val remaining = ServerConfig.remainingAttempts(this)

        binding.inputPassword.isEnabled = !blocked
        binding.btnConnect.isEnabled = !blocked
        binding.btnCheckStatus.visibility = if (blocked) android.view.View.VISIBLE else android.view.View.GONE

        if (blocked) {
            binding.inputPassword.hint = getString(R.string.device_locked_hint)
        } else if (attempts > 0) {
            binding.inputPassword.hint = getString(R.string.attempts_remaining_hint, remaining)
        }
    }

    private fun showBlockedToast() {
        Toast.makeText(this, R.string.device_locked_message, Toast.LENGTH_LONG).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    sealed interface ConnectionResult {
        data class Success(val url: String) : ConnectionResult
        data class AuthFailed(val message: String) : ConnectionResult
        data class Error(val message: String) : ConnectionResult
    }
}