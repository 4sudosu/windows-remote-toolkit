package com.runtimebroker.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityRunAppBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class RunAppActivity : BaseActivity() {

    private lateinit var binding: ActivityRunAppBinding
    private lateinit var adapter: AppAdapter

    private var machineName = ""
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRunAppBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()
        supportActionBar?.title = getString(R.string.run_app_title)

        adapter = AppAdapter { app -> runApp(app) }
        binding.appsList.layoutManager = LinearLayoutManager(this)
        binding.appsList.adapter = adapter

        binding.btnRefreshApps.setOnClickListener { loadApps() }
        loadApps()
    }

    private fun loadApps() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            binding.statusText.text = getString(R.string.loading_apps)
            binding.emptyText.visibility = android.view.View.GONE
            
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@RunAppActivity),
                machineName,
                Prefs.password(this@RunAppActivity),
                "list_apps",
                JSONObject()
            )
            
            if (result.success) {
                val arr = result.data as? JSONArray
                val apps = arr?.let { a ->
                    (0 until a.length()).map { i ->
                        val o = a.getJSONObject(i)
                        AppEntry(
                            packageName = o.optString("packageName"),
                            label = o.optString("label"),
                            iconBase64 = o.optString("iconBase64")
                        )
                    }
                } ?: emptyList()
                
                if (apps.isEmpty()) {
                    binding.emptyText.visibility = android.view.View.VISIBLE
                    binding.statusText.text = getString(R.string.no_apps_found)
                } else {
                    binding.emptyText.visibility = android.view.View.GONE
                    binding.statusText.text = getString(R.string.processes_count, apps.size)
                }
                adapter.submitList(apps)
            } else {
                binding.statusText.text = result.error ?: "error"
                binding.emptyText.visibility = android.view.View.VISIBLE
                binding.emptyText.text = getString(R.string.no_apps_found)
                Toast.makeText(this@RunAppActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun runApp(app: AppEntry) {
        lifecycleScope.launch {
            binding.statusText.text = getString(R.string.app_running, app.label)
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@RunAppActivity),
                machineName,
                Prefs.password(this@RunAppActivity),
                "run_app",
                JSONObject().put("packageName", app.packageName)
            )
            
            if (result.success) {
                binding.statusText.text = getString(R.string.app_running, app.label)
                Toast.makeText(this@RunAppActivity, getString(R.string.app_running, app.label), Toast.LENGTH_SHORT).show()
            } else {
                binding.statusText.text = getString(R.string.run_failed, result.error ?: "error")
                Toast.makeText(this@RunAppActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    data class AppEntry(
        val packageName: String,
        val label: String,
        val iconBase64: String
    )

    class AppAdapter(private val onClick: (AppEntry) -> Unit) : ListAdapter<AppEntry, AppAdapter.VH>(DiffCallback()) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(getItem(position))
        }

        inner class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
            fun bind(item: AppEntry) {
                itemView.findViewById<TextView>(android.R.id.text1).text = item.label
                itemView.findViewById<TextView>(android.R.id.text2).text = item.packageName
                itemView.setOnClickListener { onClick(item) }
                
                // Try to load icon from base64
                if (item.iconBase64.isNotBlank()) {
                    try {
                        val bytes = android.util.Base64.decode(item.iconBase64, android.util.Base64.DEFAULT)
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            itemView.findViewById<ImageView>(android.R.id.icon).setImageBitmap(bmp)
                        }
                    } catch (e: Exception) {
                        // Ignore icon loading errors
                    }
                }
            }
        }

        class DiffCallback : DiffUtil.ItemCallback<AppEntry>() {
            override fun areItemsTheSame(oldItem: AppEntry, newItem: AppEntry) = oldItem.packageName == newItem.packageName
            override fun areContentsTheSame(oldItem: AppEntry, newItem: AppEntry) = oldItem == newItem
        }
    }
}