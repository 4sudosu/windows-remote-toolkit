package com.runtimebroker.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.runtimebroker.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    companion object {
        private const val REQ_TONE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bindServerActions()
        bindTheme()
        bindNotifications()
        bindContact()
    }

    override fun onResume() {
        super.onResume()
        refreshServerState()
    }

    private fun bindServerActions() {
        binding.btnStartServer.setOnClickListener {
            if (NodeServerService.isRunning(this)) {
                NodeServerService.stop(this)
                Toast.makeText(this, R.string.server_stopped, Toast.LENGTH_SHORT).show()
            } else {
                startServer()
            }
            refreshServerState()
        }
    }

    private fun startServer() {
        val port = Prefs.hostPort(this).ifBlank { "4777" }
        val pw = Prefs.password(this).ifBlank { Prefs.DEFAULT_ADMIN_PASSWORD }
        Prefs.saveHost(this, "0.0.0.0", port)
        Prefs.save(this, "http://127.0.0.1:$port", pw)
        Prefs.saveServerMode(this, "start")
        NodeServerService.start(this)
        Toast.makeText(this, R.string.server_started, Toast.LENGTH_SHORT).show()
    }

    private fun refreshServerState() {
        if (!::binding.isInitialized) return
        val running = NodeServerService.isRunning(this)
        binding.btnStartServer.text = getString(
            if (running) R.string.stop_server else R.string.start_server
        )
        binding.serverStatus.text = if (running) {
            val ip = NodeServerService.localIps(this).firstOrNull() ?: "0.0.0.0"
            getString(R.string.launcher_status_running, ip, Prefs.hostPort(this))
        } else {
            getString(R.string.server_stopped_line)
        }
        binding.statusText.text = ""
    }

    // ---- Appearance ------------------------------------------------------
    private fun bindTheme() {
        binding.tvThemeName.text = ThemeManager.currentName(this)
        binding.btnTheme.setOnClickListener { showThemePicker() }
    }

    private fun showThemePicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_theme_picker, null)
        val list = dialogView.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.theme_picker_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
        val dialog = builder.create()

        val adapter = ThemePickerAdapter { which ->
            Prefs.saveTheme(this, which)
            binding.tvThemeName.text = ThemeManager.themes[which].name
            applyThemeNow()
            dialog.dismiss()
        }
        list.adapter = adapter
        adapter.setSelected(Prefs.themeIndex(this).coerceIn(0, ThemeManager.themes.size - 1))
        dialog.show()
    }

    // ---- Notifications ----------------------------------------------------
    private fun bindNotifications() {
        binding.swNotif.isChecked = Prefs.notifEnabled(this)
        binding.swNotif.setOnCheckedChangeListener { _, checked ->
            Prefs.saveNotifEnabled(this, checked)
        }
        binding.btnTone.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.tone_picker_title))
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                val current = Prefs.customToneUri(this@SettingsActivity)
                    ?: Prefs.notifTone(this@SettingsActivity).takeIf { it.isNotBlank() }
                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    if (current.isNullOrBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else Uri.parse(current)
                )
            }
            try {
                startActivityForResult(intent, REQ_TONE)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.tone_picker_title, Toast.LENGTH_SHORT).show()
            }
        }
        updateToneLabel()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TONE && resultCode == Activity.RESULT_OK) {
            val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                Prefs.saveNotifToneKey(this, "custom")
                Prefs.saveCustomToneUri(this, uri.toString())
            } else {
                Prefs.saveNotifToneKey(this, "system")
                Prefs.saveCustomToneUri(this, null)
            }
            updateToneLabel()
        }
    }

    private fun updateToneLabel() {
        val label = when (Prefs.notifToneKey(this)) {
            "chime" -> getString(R.string.tone_chime)
            "alert" -> getString(R.string.tone_alert)
            "ding" -> getString(R.string.tone_ding)
            "soft" -> getString(R.string.tone_soft)
            "custom" -> Prefs.customToneUri(this)?.let { uri ->
                try {
                    RingtoneManager.getRingtone(this, Uri.parse(uri))?.getTitle(this)
                } catch (_: Exception) {
                    null
                }
            } ?: getString(R.string.notif_tone_default)
            else -> getString(R.string.notif_tone_default)
        }
        binding.tvToneName.text = label
    }

    // ---- Contact -----------------------------------------------------------
    private fun bindContact() {
        binding.btnTelegram.setOnClickListener { openUrl("https://t.me/verifiedharyanvi") }
        binding.btnInstagram.setOnClickListener { openUrl("https://www.instagram.com/4sudo.su") }
        binding.btnGmail.setOnClickListener { openUrl("mailto:4sudo.su@gmail.com") }
        binding.btnGithub.setOnClickListener { openUrl("https://github.com/4sudosu") }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
