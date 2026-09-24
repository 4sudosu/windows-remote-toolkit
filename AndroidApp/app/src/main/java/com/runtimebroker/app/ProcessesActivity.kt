package com.runtimebroker.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityProcessesBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray

class ProcessesActivity : BaseActivity() {

    private lateinit var binding: ActivityProcessesBinding
    private lateinit var adapter: ProcessAdapter

    private var machineName = ""
    private var loadJob: Job? = null
    private var killJob: Job? = null
    private var allEntries: List<ProcessEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProcessesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()

        adapter = ProcessAdapter { entry -> confirmKill(entry) }
        binding.processList.layoutManager = LinearLayoutManager(this)
        binding.processList.adapter = adapter

        binding.btnRefresh.setOnClickListener { loadProcesses() }
        binding.btnClearFilter.setOnClickListener { clearFilter() }

        binding.catGroup.setOnCheckedStateChangeListener { _, _ ->
            applyCategory()
        }

        binding.processSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = applyCategory()
        })

        loadProcesses()
    }

    private fun loadProcesses() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            binding.statusText.text = getString(R.string.loading)
            binding.emptyText.visibility = View.GONE
            val result = RuntimeBrokerApi.listProcesses(
                Prefs.serverUrl(this@ProcessesActivity),
                machineName,
                Prefs.password(this@ProcessesActivity)
            )
            if (result.success) {
                val arr = result.data as? JSONArray
                allEntries = arr?.let { a ->
                    (0 until a.length()).map { i ->
                        val o = a.getJSONObject(i)
                        ProcessEntry(
                            pid = o.optInt("pid"),
                            name = o.optString("name"),
                            title = o.optString("title"),
                            memMB = o.optInt("memMB"),
                            cpu = o.optDouble("cpu", 0.0),
                            connections = o.optInt("connections"),
                            session = o.optInt("session", -1),
                            hasWindow = o.optBoolean("hasWindow")
                        )
                    }
                } ?: emptyList()
                binding.statusText.text = getString(R.string.processes_count, allEntries.size)
                binding.emptyText.visibility = View.GONE
                applyCategory()
            } else {
                binding.statusText.text = result.error ?: "error"
                Toast.makeText(this@ProcessesActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyCategory() {
        val query = binding.processSearch.text.toString().trim().lowercase()
        val base = when (binding.catGroup.checkedChipId) {
            R.id.catApps -> allEntries.filter { it.hasWindow }
            R.id.catBackground -> allEntries.filter { !it.hasWindow }
            R.id.catRam -> allEntries.sortedByDescending { it.memMB }
            R.id.catCpu -> allEntries.sortedByDescending { it.cpu }
            R.id.catInternet -> allEntries.filter { it.connections > 0 }.sortedByDescending { it.connections }
            else -> allEntries
        }
        val filtered = if (query.isEmpty()) base else base.filter {
            it.name.lowercase().contains(query) ||
                (it.title ?: "").lowercase().contains(query) ||
                it.pid.toString() == query
        }
        adapter.submit(filtered)
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        // Show/hide clear filter button
        val isFiltered = binding.catGroup.checkedChipId != R.id.catAll || query.isNotEmpty()
        binding.btnClearFilter.visibility = if (isFiltered) View.VISIBLE else View.GONE
    }

    private fun clearFilter() {
        binding.processSearch.setText("")
        binding.catGroup.check(R.id.catAll)
        binding.btnClearFilter.visibility = View.GONE
    }

    private fun confirmKill(entry: ProcessEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.kill)
            .setMessage(getString(R.string.kill_confirm, entry.name, entry.pid))
            .setPositiveButton(R.string.kill) { _, _ -> killProcess(entry) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun killProcess(entry: ProcessEntry) {
        killJob?.cancel()
        killJob = lifecycleScope.launch {
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@ProcessesActivity),
                machineName,
                Prefs.password(this@ProcessesActivity),
                "kill_process",
                org.json.JSONObject().put("pid", entry.pid)
            )
            if (result.success) {
                Toast.makeText(this@ProcessesActivity, getString(R.string.killed, entry.name, entry.pid), Toast.LENGTH_SHORT).show()
                loadProcesses()
            } else {
                Toast.makeText(this@ProcessesActivity, getString(R.string.kill_failed, result.error ?: "error"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        killJob?.cancel()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}