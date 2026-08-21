package com.runtimebroker.app

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityInputtextBinding
import kotlinx.coroutines.launch

class InputTextActivity : BaseActivity() {

    private lateinit var binding: ActivityInputtextBinding
    private var machineName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInputtextBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()

        binding.btnSend.setOnClickListener { sendText() }
    }

    private fun sendText() {
        var text = binding.textInput.text.toString()
        if (text.isEmpty()) return
        if (binding.enterCheck.isChecked) text += "\n"
        lifecycleScope.launch {
            binding.btnSend.isEnabled = false
            binding.statusText.text = getString(R.string.running)
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@InputTextActivity),
                machineName,
                Prefs.password(this@InputTextActivity),
                "input_text",
                org.json.JSONObject().put("text", text)
            )
            binding.btnSend.isEnabled = true
            if (result.success) {
                binding.statusText.text = result.output ?: getString(R.string.done)
            } else {
                binding.statusText.text = result.error ?: "error"
                Toast.makeText(this@InputTextActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}