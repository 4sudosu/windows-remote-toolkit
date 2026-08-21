package com.runtimebroker.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityServicesBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ServicesActivity : BaseActivity() {

    private lateinit var binding: ActivityServicesBinding
    private lateinit var adapter: ServiceAdapter

    private var machineName = ""
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()

        adapter = ServiceAdapter { entry -> showActions(entry) }
        binding.serviceList.layoutManager = LinearLayoutManager(this)
        binding.serviceList.adapter = adapter

        binding.btnRefresh.setOnClickListener { loadServices() }

        loadServices()
    }

    private fun loadServices() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            binding.statusText.text = getString(R.string.loading)
            binding.emptyText.visibility = View.GONE
            val result = RuntimeBrokerApi.listServices(
                Prefs.serverUrl(this@ServicesActivity),
                machineName,
                Prefs.password(this@ServicesActivity)
            )
            if (result.success) {
                val arr = result.data as? JSONArray
                val entries = arr?.let { a ->
                    (0 until a.length()).map { i ->
                        val o = a.getJSONObject(i)
                        ServiceEntry(
                            name = o.optString("name"),
                            displayName = o.optString("displayName"),
                            status = o.optString("status"),
                            startType = o.optString("startType")
                        )
                    }
                } ?: emptyList()
                adapter.submit(entries)
                binding.statusText.text = getString(R.string.services_count, entries.size)
                binding.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            } else {
                binding.statusText.text = result.error ?: "error"
                Toast.makeText(this@ServicesActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showActions(entry: ServiceEntry) {
        val actions = buildList {
            add(getString(R.string.svc_start) to "start")
            add(getString(R.string.svc_stop) to "stop")
            add(getString(R.string.svc_restart) to "restart")
            add(getString(R.string.svc_auto) to "auto")
            add(getString(R.string.svc_manual) to "manual")
            add(getString(R.string.svc_disabled) to "disabled")
        }
        val labels = actions.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.svc_actions) + " — " + entry.name)
            .setItems(labels) { _, which ->
                runAction(entry, actions[which].second, labels[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runAction(entry: ServiceEntry, action: String, label: String) {
        actionJob?.cancel()
        actionJob = lifecycleScope.launch {
            binding.statusText.text = getString(R.string.running)
            val timeout = if (action == "restart" || action == "start" || action == "stop") 60 else 30
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@ServicesActivity),
                machineName,
                Prefs.password(this@ServicesActivity),
                "service_action",
                JSONObject().put("name", entry.name).put("action", action).put("timeoutSec", timeout)
            )
            if (result.success) {
                binding.statusText.text = result.output ?: getString(R.string.done)
            } else {
                binding.statusText.text = getString(R.string.svc_action_failed, label, result.error ?: "error")
                Toast.makeText(this@ServicesActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
            loadServices()
        }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        actionJob?.cancel()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}