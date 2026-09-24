package com.runtimebroker.app

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityMicBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

class MicActivity : BaseActivity() {

    private lateinit var binding: ActivityMicBinding

    private var machineName = ""
    private var job: Job? = null
    private var lastUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMicBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()

        binding.btnRecord.setOnClickListener { record() }
        binding.btnPlay.setOnClickListener { play() }
    }

    private fun seconds(): Int =
        binding.secondsInput.text.toString().toIntOrNull()?.coerceIn(1, 300) ?: 10

    private fun record() {
        job?.cancel()
        job = lifecycleScope.launch {
            binding.btnRecord.isEnabled = false
            binding.statusText.text = getString(R.string.recording)
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@MicActivity),
                machineName,
                Prefs.password(this@MicActivity),
                "mic_record",
                JSONObject().put("seconds", seconds()).put("timeoutSec", seconds() + 90)
            )
            binding.btnRecord.isEnabled = true
            if (result.success && !result.output.isNullOrBlank()) {
                val name = "wsm-mic-${System.currentTimeMillis()}.m4a"
                val uri = MediaSaver.save(this@MicActivity, "audio/mp4", name, result.output, "mic")
                if (uri != null) {
                    lastUri = uri
                    binding.btnPlay.isEnabled = true
                    binding.statusText.text = getString(R.string.audio_saved)
                    Toast.makeText(this@MicActivity, R.string.audio_saved, Toast.LENGTH_SHORT).show()
                } else {
                    binding.statusText.text = getString(R.string.save_failed)
                }
            } else {
                binding.statusText.text = result.error ?: "error"
                Toast.makeText(this@MicActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun play() {
        val uri = lastUri ?: return
        if (!MediaSaver.open(this, uri, "audio/mp4")) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}