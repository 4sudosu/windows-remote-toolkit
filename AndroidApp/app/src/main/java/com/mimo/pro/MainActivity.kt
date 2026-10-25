package com.mimo.pro

import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mimo.pro.api.AgentInfo
import com.mimo.pro.api.RuntimeBrokerApi
import com.mimo.pro.databinding.ActivityMainBinding
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
    private var sseUrl = ""

    companion object {
        const val EXTRA_MACHINE = "machine_name"
        const val EXTRA_HOST = "hostname"
        private const val REQ_PERMISSIONS = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        // Only the file permission needed for saving downloads is requested.
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
        binding.btnOpenServer.setOnClickListener { openRunningServer() }
        binding.btnRunServer.setOnClickListener { toggleServer() }

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

        // Auto-connect if server is configured
        if (Prefs.serverUrl(this).isNotBlank()) {
            lifecycleScope.launch { refreshAgents() }
        } else {
            // No server configured - show welcome dialog to connect
            if (!welcomeShown) {
                welcomeShown = true
                showWelcomeDialog()
            }
        }
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
            R.id.action_connect -> {
                startActivity(Intent(this, ConnectActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if server URL is configured, if not show welcome dialog
        if (!welcomeShown && Prefs.serverUrl(this).isBlank()) {
            welcomeShown = true
            showWelcomeDialog()
        }
        updateServerState()
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

    private fun toggleServer() {
        if (NodeServerService.isRunning(this)) {
            NodeServerService.stop(this)
            Toast.makeText(this, R.string.server_stopped, Toast.LENGTH_SHORT).show()
        } else {
            NodeServerService.configure(this, NodeServerService.DEFAULT_HOST, NodeServerService.DEFAULT_PORT)
            NodeServerService.start(this)
            val ip = NodeServerService.localIps(this).firstOrNull()
            Toast.makeText(this, getString(R.string.server_started, "http://${ip ?: "127.0.0.1"}:${NodeServerService.currentPort(this)}"), Toast.LENGTH_LONG).show()
        }
        updateServerState()
    }

    /** Shows the embedded-server state line under the action buttons. */
    private fun updateServerState() {
        val ip = NodeServerService.localIps(this).firstOrNull()
        if (NodeServerService.isRunning(this) && ip != null) {
            binding.tvServerState.visibility = View.VISIBLE
            binding.tvServerState.text = getString(R.string.server_state_running, "http://$ip:${NodeServerService.currentPort(this)}")
            binding.btnRunServer.text = getString(R.string.stop_server)
        } else {
            binding.tvServerState.visibility = View.GONE
            binding.btnRunServer.text = getString(R.string.run_server_all_interfaces)
        }
    }

    private fun openDeviceActions(agent: AgentInfo) {
        Prefs.saveLastDevice(this, agent.machineName, agent.hostname.ifBlank { agent.machineName })
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
            .setPositiveButton(R.string.connect_button) { _, _ -> startActivity(Intent(this, ConnectActivity::class.java)) }
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            .show()
    }

    /** V2 live device list: poll interval comes from prefs (3s/5s/10s). */
    fun pollIntervalMs(): Long = (Prefs.refreshSecs(this).coerceIn(3, 10)) * 1000L

    /** Restart polling (e.g. after the refresh interval changes). */
    fun restartPolling() {
        startPolling()
    }

    private fun startPolling() {
        refreshJob?.cancel()
        maybeStartEventStream()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                refreshAgents()
                delay(pollIntervalMs())
            }
        }
    }

    /** V2 instant connect notifications: stream /api/events (SSE) in a foreground service. */
    private fun maybeStartEventStream() {
        val baseUrl = Prefs.serverUrl(this)
        if (baseUrl.isBlank() || baseUrl == sseUrl) return
        sseUrl = baseUrl
        try {
            AgentEventService.start(this, baseUrl)
        } catch (_: Exception) {
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

        // Check network connectivity
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return
        val capabilities = cm.getNetworkCapabilities(activeNetwork)
            ?: run {
                runOnUiThread {
                    binding.refreshLayout.isRefreshing = false
                    binding.statusText.text = getString(R.string.error_no_network)
                    binding.errorBox.visibility = View.VISIBLE
                    binding.errorText.text = getString(R.string.error_no_network)
                }
                return
            }
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            runOnUiThread {
                binding.refreshLayout.isRefreshing = false
                binding.statusText.text = getString(R.string.error_no_network)
                binding.errorBox.visibility = View.VISIBLE
                binding.errorText.text = getString(R.string.error_no_network)
            }
            return
        }

        runOnUiThread { binding.statusText.text = getString(R.string.status_connecting) }
        val agents = RuntimeBrokerApi.agents(baseUrl, Prefs.password(this), currentQuery)
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
}
