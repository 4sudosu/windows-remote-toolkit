package com.mimo.pro

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.mimo.pro.api.RuntimeBrokerApi
import com.mimo.pro.databinding.ActivityPowerMenuBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PowerMenuActivity : BaseActivity() {

    private lateinit var binding: ActivityPowerMenuBinding
    private var machineName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPowerMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()
        supportActionBar?.title = getString(R.string.power_menu_title)

        binding.btnShutdown.setOnClickListener { showConfirmDialog("shutdown") }
        binding.btnRestart.setOnClickListener { showConfirmDialog("restart") }
        binding.btnSleep.setOnClickListener { showConfirmDialog("sleep") }
        binding.btnHibernate.setOnClickListener { showConfirmDialog("hibernate") }
        binding.btnLock.setOnClickListener { showConfirmDialog("lock") }
        binding.btnLogoff.setOnClickListener { showConfirmDialog("logoff") }
    }

    private fun showConfirmDialog(action: String) {
        val (title, message) = when (action) {
            "shutdown" -> getString(R.string.power_shutdown) to getString(R.string.power_shutdown_confirm)
            "restart" -> getString(R.string.power_restart) to getString(R.string.power_restart_confirm)
            "sleep" -> getString(R.string.power_sleep) to getString(R.string.power_sleep_confirm)
            "hibernate" -> getString(R.string.power_hibernate) to getString(R.string.power_hibernate_confirm)
            "lock" -> getString(R.string.power_lock) to getString(R.string.power_lock_confirm)
            "logoff" -> getString(R.string.power_logoff) to getString(R.string.power_logoff_confirm)
            else -> "Action" to "Are you sure?"
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.confirm) { _, _ -> executePowerAction(action) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun executePowerAction(action: String) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        val buttons = listOf(binding.btnShutdown, binding.btnRestart, binding.btnSleep, binding.btnHibernate, binding.btnLock, binding.btnLogoff)
        buttons.forEach { it.isEnabled = false }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = Connection.resolveBase(this@PowerMenuActivity)
                val pw = Connection.resolvePassword(this@PowerMenuActivity)
                val result = RuntimeBrokerApi.powerAction(url, machineName, pw, action)

                runOnUiThread {
                    binding.progressBar.visibility = android.view.View.GONE
                    buttons.forEach { it.isEnabled = true }

                    if (result != null && result.success) {
                        Toast.makeText(this@PowerMenuActivity, getString(R.string.power_action_sent, action), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@PowerMenuActivity, getString(R.string.power_action_failed, result?.error ?: "Unknown error"), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = android.view.View.GONE
                    buttons.forEach { it.isEnabled = true }
                    Toast.makeText(this@PowerMenuActivity, getString(R.string.power_action_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}