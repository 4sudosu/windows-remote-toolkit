package com.runtimebroker.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.runtimebroker.app.api.AgentInfo
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AgentAdapter

    private var refreshJob: Job? = null
    private var searchTimer: Job? = null
    private var currentQuery = ""
    private var welcomeShown = false
    private var activeTab = 0

    companion object {
        const val EXTRA_MACHINE = "machine_name"
        const val EXTRA_HOST = "hostname"
        private const val POLL_INTERVAL_MS = 5000L
        private const val REQ_PERMISSIONS = 2001
        private const val REQ_TONE = 4001

        private val toneLabels = mapOf(
            "system" to R.string.tone_system_default,
            "chime" to R.string.tone_chime,
            "alert" to R.string.tone_alert,
            "ding" to R.string.tone_ding,
            "soft" to R.string.tone_soft,
            "custom" to R.string.tone_custom
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        updateToolbarSubtitle()

        Permissions.requestAll(this, REQ_PERMISSIONS)

        adapter = AgentAdapter { agent -> openDeviceActions(agent) }
        binding.agentList.layoutManager = LinearLayoutManager(this)
        binding.agentList.adapter = adapter

        binding.refreshLayout.setOnRefreshListener {
            lifecycleScope.launch { refreshAgents() }
        }
        binding.btnRetry.setOnClickListener {
            lifecycleScope.launch { refreshAgents() }
        }
        binding.btnServerOptions.setOnClickListener {
            startActivity(Intent(this, LauncherActivity::class.java))
        }

        binding.deviceSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.trim().orEmpty()
                searchTimer?.cancel()
                searchTimer = lifecycleScope.launch {
                    delay(300)
                    refreshAgents()
                }
            }
        })

        binding.tabDevices.setOnClickListener { setTab(0) }
        binding.tabSettings.setOnClickListener { setTab(1) }

        setupDeveloperLinks()
        setupSettings()

        requestNotificationPermission()
        setTab(Prefs.activeTab(this))

        startPolling()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                lifecycleScope.launch { refreshAgents() }
                true
            }
            R.id.action_launcher -> {
                startActivity(Intent(this, LauncherActivity::class.java))
                true
            }
            R.id.action_connect -> {
                startActivity(Intent(this, ConnectActivity::class.java))
                true
            }
            R.id.action_stop_server -> {
                stopServer()
                true
            }
            R.id.action_web -> {
                val url = Prefs.serverUrl(this)
                if (url.isNotBlank()) open(url)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.serverMode(this) == "start") {
            if (Prefs.serverUrl(this).isBlank()) {
                val port = Prefs.hostPort(this)
                Prefs.save(this, "http://127.0.0.1:$port", Prefs.password(this))
            }
            NodeServerService.start(this)
        }
        updateToolbarSubtitle()
        updateServerState()
        if (!welcomeShown && Prefs.serverMode(this) == "connect" && Prefs.serverUrl(this).isBlank()) {
            welcomeShown = true
            showWelcomeDialog()
        }
    }

    private fun openDeviceActions(agent: AgentInfo) {
        val intent = Intent(this, DeviceActionsActivity::class.java)
        intent.putExtra(EXTRA_MACHINE, agent.machineName)
        intent.putExtra(EXTRA_HOST, agent.hostname.ifBlank { agent.machineName })
        intent.putExtra(DeviceActionsActivity.EXTRA_IP, agent.ip)
        intent.putExtra(DeviceActionsActivity.EXTRA_MODEL, agent.model)
        intent.putExtra(DeviceActionsActivity.EXTRA_SERIAL, agent.serial)
        intent.putExtra(DeviceActionsActivity.EXTRA_VERSION, agent.version)
        intent.putExtra(DeviceActionsActivity.EXTRA_ONLINE, agent.online)
        startActivity(intent)
    }

    private fun showWelcomeDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.welcome_title)
            .setMessage(R.string.welcome_desc)
            .setPositiveButton(R.string.welcome_start) { _, _ -> showServerSetupDialog(isHosting = true) }
            .setNegativeButton(R.string.welcome_connect) { _, _ -> showServerSetupDialog(isHosting = false) }
            .setCancelable(false)
            .show()
    }

    private fun showServerSetupDialog(isHosting: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_server_setup, null)
        val ipField = view.findViewById<android.widget.EditText>(R.id.inputIp)
        val portField = view.findViewById<android.widget.EditText>(R.id.inputPort)
        val pwField = view.findViewById<android.widget.EditText>(R.id.inputPassword)

        val currentUrl = Prefs.serverUrl(this)
        val defaultIp: String
        val defaultPort: String
        if (isHosting) {
            defaultIp = Prefs.hostIp(this)
            defaultPort = Prefs.hostPort(this)
        } else {
            defaultIp = runCatching { Uri.parse(currentUrl).host }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "192.168.1.10"
            defaultPort = runCatching { Uri.parse(currentUrl).port }
                .getOrNull()
                ?.toString()
                ?: Prefs.hostPort(this)
        }
        ipField.setText(defaultIp)
        portField.setText(defaultPort)
        pwField.setText(Prefs.password(this))

        AlertDialog.Builder(this)
            .setTitle(if (isHosting) R.string.host_setup_title else R.string.connect_setup_title)
            .setView(view)
            .setPositiveButton(if (isHosting) R.string.welcome_start else R.string.welcome_connect) { _, _ ->
                val ip = ipField.text.toString().trim()
                val port = portField.text.toString().trim().ifBlank { Prefs.hostPort(this) }
                val pw = pwField.text.toString().ifBlank { Prefs.DEFAULT_ADMIN_PASSWORD }
                if (isHosting) {
                    Prefs.saveHost(this, ip, port)
                    Prefs.save(this, "http://127.0.0.1:$port", pw)
                    Prefs.saveServerMode(this, "start")
                    NodeServerService.start(this)
                } else {
                    val url = if (ip.startsWith("http://") || ip.startsWith("https://")) {
                        ip
                    } else {
                        "http://$ip:$port"
                    }
                    Prefs.save(this, url, pw)
                    Prefs.saveServerMode(this, "connect")
                }
                updateToolbarSubtitle()
                updateServerState()
                lifecycleScope.launch { refreshAgents() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startPolling() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                refreshAgents()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshAgents() {
        val baseUrl = Prefs.serverUrl(this)
        if (baseUrl.isBlank()) {
            runOnUiThread {
                binding.refreshLayout.isRefreshing = false
                binding.statusText.text = getString(R.string.unconfigured)
                binding.errorBox.visibility = View.GONE
                binding.agentList.visibility = View.GONE
                binding.emptyText.visibility = View.GONE
            }
            return
        }

        runOnUiThread { binding.statusText.text = getString(R.string.status_connecting) }
        val agents = RuntimeBrokerApi.agents(baseUrl, currentQuery)
        runOnUiThread {
            binding.refreshLayout.isRefreshing = false
            if (agents == null) {
                binding.statusText.text = getString(R.string.status_invalid)
                binding.errorText.text = getString(R.string.error_connection)
                binding.errorBox.visibility = View.VISIBLE
                binding.emptyText.visibility = View.GONE
                binding.agentList.visibility = View.GONE
            } else {
                binding.errorBox.visibility = View.GONE
                binding.statusText.text = getString(
                    R.string.status_online,
                    agents.count { it.online },
                    agents.size
                )
                adapter.submit(agents)
                if (agents.isEmpty()) {
                    binding.emptyText.visibility = View.VISIBLE
                    binding.agentList.visibility = View.GONE
                } else {
                    binding.emptyText.visibility = View.GONE
                    binding.agentList.visibility = View.VISIBLE
                }
                notifyNewAgents(agents)
            }
        }
    }

    private fun notifyNewAgents(agents: List<AgentInfo>) {
        val known = Prefs.knownAgents(this).toMutableSet()
        var changed = false
        for (agent in agents) {
            if (agent.online && agent.machineName !in known) {
                Notifications.postNewAgent(
                    this,
                    agent.hostname.ifBlank { agent.machineName },
                    agent.machineName
                )
            }
            changed = known.add(agent.machineName) || changed
        }
        if (changed) Prefs.saveKnownAgents(this, known)
    }

    private fun updateToolbarSubtitle() {
        val url = Prefs.serverUrl(this)
        supportActionBar?.subtitle = when {
            url.isBlank() -> getString(R.string.unconfigured)
            Prefs.serverMode(this) == "start" -> {
                val ip = NodeServerService.localIps(this).firstOrNull() ?: "0.0.0.0"
                getString(R.string.server_running_on, ip, Prefs.hostPort(this))
            }
            else -> url.replaceFirst(Regex("^https?://"), "")
        }
    }

    // ── tabs ──────────────────────────────────────────────────────────────
    private fun setTab(i: Int) {
        activeTab = i
        Prefs.saveActiveTab(this, i)
        binding.devicesPanel.visibility = if (i == 0) View.VISIBLE else View.GONE
        binding.settingsPanel.visibility = if (i == 1) View.VISIBLE else View.GONE
        val accent = ThemeManager.current(this).accent
        val accentState = android.content.res.ColorStateList.valueOf(accent)
        val unselected = ContextCompat.getColorStateList(this, R.color.surface_variant)
        binding.tabDevices.backgroundTintList = if (i == 0) accentState else unselected
        binding.tabSettings.backgroundTintList = if (i == 1) accentState else unselected
    }

    // ── settings ──────────────────────────────────────────────────────────
    private fun setupSettings() {
        binding.switchNotif.isChecked = Prefs.notifEnabled(this)
        binding.switchNotif.setOnCheckedChangeListener { _, checked ->
            Prefs.saveNotifEnabled(this, checked)
        }
        updateToneLabel()
        binding.btnTone.setOnClickListener { showTonePicker() }
        binding.btnStopServer.setOnClickListener { toggleServer() }
        updateServerState()
        renderThemeSwatches()
        renderIconSwatches()
        renderAppIcons()
    }

    private fun updateToneLabel() {
        binding.tvTone.text = getString(toneLabels[Prefs.notifToneKey(this)] ?: R.string.tone_system_default)
    }

    private fun showTonePicker() {
        val options = arrayOf(
            getString(R.string.tone_system_default),
            getString(R.string.tone_chime),
            getString(R.string.tone_alert),
            getString(R.string.tone_ding),
            getString(R.string.tone_soft),
            getString(R.string.tone_custom)
        )
        val values = arrayOf("system", "chime", "alert", "ding", "soft", "custom")
        val current = Prefs.notifToneKey(this)
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.tone_picker_title)
            .setSingleChoiceItems(options, checked) { d, which ->
                val v = values[which]
                if (v == "custom") {
                    d.dismiss()
                    openRingtonePicker()
                } else {
                    Prefs.saveNotifToneKey(this, v)
                    updateToneLabel()
                    d.dismiss()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.custom_tone_picker_title))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                Prefs.customToneUri(this@MainActivity)?.let(Uri::parse)
                    ?: Prefs.notifTone(this@MainActivity).takeIf { it.isNotBlank() }?.let(Uri::parse)
            )
        }
        startActivityForResult(intent, REQ_TONE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TONE && resultCode == RESULT_OK) {
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

    private fun updateServerState() {
        val hosting = Prefs.serverMode(this) == "start"
        val running = hosting && NodeServerService.isRunning(this)
        binding.tvServerState.text = when {
            !hosting -> getString(R.string.server_state_connect_mode)
            running -> {
                val ip = NodeServerService.localIps(this).firstOrNull() ?: "0.0.0.0"
                getString(R.string.server_state_running, ip, Prefs.hostPort(this))
            }
            else -> getString(R.string.server_state_stopped)
        }
        binding.btnStopServer.text = getString(if (running) R.string.stop else R.string.start)
        binding.btnStopServer.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.error_red else R.color.online_green)
        )
    }

    private fun toggleServer() {
        if (Prefs.serverMode(this) != "start") {
            Toast.makeText(this, R.string.not_in_host_mode, Toast.LENGTH_SHORT).show()
            return
        }
        if (NodeServerService.isRunning(this)) {
            stopServer()
        } else {
            startServer()
        }
    }

    private fun startServer() {
        Toast.makeText(this, R.string.server_starting, Toast.LENGTH_SHORT).show()
        NodeServerService.start(this)
        updateServerState()
        updateToolbarSubtitle()
        lifecycleScope.launch {
            delay(900)
            updateServerState()
        }
    }

    private fun stopServer() {
        Toast.makeText(this, R.string.server_stopping, Toast.LENGTH_SHORT).show()
        NodeServerService.stop(this)
        updateServerState()
        updateToolbarSubtitle()
    }

    private fun renderThemeSwatches() {
        binding.themeRow.removeAllViews()
        val themes = ThemeManager.themes
        val sel = Prefs.themeIndex(this)
        for (i in themes.indices) {
            val cell = FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    height = dp(52)
                    setMargins(0, 0, 0, dp(8))
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setGravity(android.view.Gravity.CENTER)
                }
                contentDescription = themes[i].name
                setOnClickListener {
                    Prefs.saveTheme(this@MainActivity, i)
                    applyThemeNow()
                }
            }
            val outer = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(46), dp(46)).apply {
                    gravity = android.view.Gravity.CENTER
                }
                background = if (i == sel) ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_ring)
                    else null
            }
            val inner = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                    gravity = android.view.Gravity.CENTER
                }
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_swatch)
                background.setTint(themes[i].accent)
            }
            outer.addView(inner)
            cell.addView(outer)
            binding.themeRow.addView(cell)
        }
    }

    private fun renderIconSwatches() {
        binding.iconRow.removeAllViews()
        val res = resources.getStringArray(R.array.notif_icon_res)
        val names = resources.getStringArray(R.array.notif_icon_names)
        val sel = Prefs.notifIcon(this)
        for (i in res.indices) {
            val id = resources.getIdentifier(res[i], "drawable", packageName)
            if (id == 0) continue
            val selected = res[i] == sel
            val cell = FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    height = dp(66)
                    setMargins(0, 0, 0, dp(10))
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setGravity(android.view.Gravity.CENTER)
                }
                contentDescription = names[i]
                setOnClickListener {
                    Prefs.saveNotifIcon(this@MainActivity, res[i])
                    renderIconSwatches()
                }
            }
            val outer = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(52), dp(52)).apply {
                    gravity = android.view.Gravity.CENTER
                }
                background = if (selected) ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_ring)
                    else null
            }
            val img = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(40), dp(40)).apply {
                    gravity = android.view.Gravity.CENTER
                }
                setImageResource(id)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_swatch)
                background.setTint(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (selected) R.color.cyan else R.color.surface_variant
                    )
                )
                setPadding(dp(9), dp(9), dp(9), dp(9))
            }
            outer.addView(img)
            cell.addView(outer)
            binding.iconRow.addView(cell)
        }
    }

    private fun renderAppIcons() {
        binding.appIconRow.removeAllViews()
        val res = resources.getStringArray(R.array.app_icon_res)
        val names = resources.getStringArray(R.array.app_icon_names)
        val sel = Prefs.appIcon(this)
        for (i in res.indices) {
            val id = resources.getIdentifier(res[i], "mipmap", packageName)
            if (id == 0) continue
            val selected = names[i] == sel
            val cell = FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    height = dp(84)
                    setMargins(0, 0, 0, dp(10))
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setGravity(android.view.Gravity.CENTER)
                }
                contentDescription = names[i]
                setOnClickListener {
                    Prefs.saveAppIcon(this@MainActivity, names[i])
                    setAppIcon(i)
                    renderAppIcons()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.app_icon_set, names[i]),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            val outer = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(64), dp(64)).apply {
                    gravity = android.view.Gravity.CENTER
                }
                background = if (selected) ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_ring)
                    else null
            }
            val img = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                    gravity = android.view.Gravity.CENTER
                }
                setImageResource(id)
            }
            outer.addView(img)
            cell.addView(outer)
            binding.appIconRow.addView(cell)
        }
    }

    private fun setAppIcon(iconIndex: Int) {
        val aliases = resources.getStringArray(R.array.app_icon_alias)
        val enabled = ComponentName(packageName, aliases[iconIndex])
        packageManager.setComponentEnabledSetting(
            enabled,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        for ((i, a) in aliases.withIndex()) {
            if (i == iconIndex) continue
            packageManager.setComponentEnabledSetting(
                ComponentName(packageName, a),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    // ── developer links ───────────────────────────────────────────────────
    private fun setupDeveloperLinks() {
        binding.btnDevTelegram.setOnClickListener { open("https://t.me/verifiedharyanvi") }
        binding.btnDevInstagram.setOnClickListener { open("https://www.instagram.com/4sudo.su") }
        binding.btnDevGmail.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:4sudo.su@gmail.com")))
            }.onFailure {
                Toast.makeText(this, "4sudo.su@gmail.com", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnDevGithub.setOnClickListener { open("https://github.com/4sudosu") }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}