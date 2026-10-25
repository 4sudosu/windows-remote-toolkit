package com.mimo.pro

import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mimo.pro.api.AgentInfo
import com.mimo.pro.api.RuntimeBrokerApi
import com.mimo.pro.databinding.ActivityDashboardBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Native dashboard of the running server — same UI language as the connect
 * flow: device cards from the connected server, swipe to refresh, tap a
 * device to control it. No web view involved.
 */
class DashboardActivity : BaseActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var adapter: AgentAdapter
    private var refreshJob: Job? = null

    companion object {
        /** Optional overrides so this dashboard can also show a LOCAL server. */
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_PASSWORD = "password"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = AgentAdapter { agent -> openDeviceActions(agent) }
        binding.agentList.layoutManager = LinearLayoutManager(this)
        binding.agentList.adapter = adapter

        binding.refreshLayout.setOnRefreshListener {
            restartPolling()
        }

        startPolling()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** One polling loop, restarted on pull-to-refresh (mirrors MainActivity). */
    private fun startPolling() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                refreshAgents()
                delay(3000)
            }
        }
    }

    private fun restartPolling() = startPolling()

    private fun openDeviceActions(agent: AgentInfo) {
        Prefs.saveLastDevice(this, agent.machineName, agent.hostname.ifBlank { agent.machineName })
        val intent = Intent(this, DeviceActionsActivity::class.java)
        intent.putExtra(MainActivity.EXTRA_MACHINE, agent.machineName)
        intent.putExtra(MainActivity.EXTRA_HOST, agent.hostname.ifBlank { agent.machineName })
        intent.putExtra(DeviceActionsActivity.EXTRA_IP, agent.ip)
        intent.putExtra(DeviceActionsActivity.EXTRA_MODEL, agent.model)
        intent.putExtra(DeviceActionsActivity.EXTRA_SERIAL, agent.serial)
        intent.putExtra(DeviceActionsActivity.EXTRA_VERSION, agent.version)
        intent.putExtra(DeviceActionsActivity.EXTRA_ONLINE, agent.online)
        startActivity(intent)
    }

    private suspend fun refreshAgents() {
        val base = intent.getStringExtra(EXTRA_BASE_URL)?.ifBlank { null } ?: Prefs.serverUrl(this)
        if (base.isBlank()) {
            runOnUiThread {
                binding.refreshLayout.isRefreshing = false
                binding.statusText.text = getString(R.string.open_server_none)
            }
            return
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            runOnUiThread {
                binding.refreshLayout.isRefreshing = false
                binding.statusText.text = getString(R.string.error_no_network)
            }
            return
        }

        runOnUiThread { binding.statusText.text = getString(R.string.status_connecting) }
        val pwd = intent.getStringExtra(EXTRA_PASSWORD)?.ifBlank { null } ?: Prefs.password(this)
        val agents = RuntimeBrokerApi.agents(base, pwd, "")
        runOnUiThread {
            binding.refreshLayout.isRefreshing = false
            if (agents == null) {
                binding.statusText.text = getString(R.string.status_invalid)
                adapter.submit(emptyList())
            } else {
                binding.statusText.text = getString(
                    R.string.status_online,
                    agents.count { it.online },
                    agents.size
                )
                adapter.submit(agents)
            }
        }
    }
}
