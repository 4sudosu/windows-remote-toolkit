package com.runtimebroker.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityParagraphBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONObject

class InputParagraphActivity : BaseActivity() {

    private lateinit var binding: ActivityParagraphBinding

    private var machineName = ""
    private var ws: WebSocket? = null
    private var typingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParagraphBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()

        binding.wpmInput.setText("50")

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateEstimate() }
        }
        binding.textInput.addTextChangedListener(watcher)
        binding.wpmInput.addTextChangedListener(watcher)

        binding.btnStart.setOnClickListener { startTyping() }
        binding.btnStopTyping.setOnClickListener { stopTyping() }

        updateEstimate()
        startLiveView()
    }

    private fun currentWords(): Int {
        val t = binding.textInput.text.toString().trim()
        return if (t.isEmpty()) 0 else t.split(Regex("\\s+")).size
    }

    private fun updateEstimate() {
        val words = currentWords()
        binding.wordCount.text = getString(R.string.paragraph_words, words)
        val over = words > 1000
        binding.wordCount.setTextColor(getColor(if (over) R.color.error_red else R.color.offline_gray))
        val wpm = binding.wpmInput.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: 50
        val secs = words * 60 / wpm
        binding.etaText.text = getString(R.string.eta_estimate, formatEta(secs))
    }

    private fun formatEta(secs: Int): String {
        if (secs <= 0) return "0s"
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return when {
            h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
            m > 0 -> if (s > 0) "${m}m ${s}s" else "${m}m"
            else -> "${s}s"
        }
    }

    private fun startTyping() {
        val text = binding.textInput.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.empty_paragraph), Toast.LENGTH_SHORT).show()
            return
        }
        val words = currentWords()
        if (words > 1000) {
            Toast.makeText(this, getString(R.string.paragraph_too_long), Toast.LENGTH_SHORT).show()
            return
        }
        val wpm = binding.wpmInput.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: 50
        val addEnter = binding.enterCheck.isChecked

        lifecycleScope.launch {
            binding.btnStart.isEnabled = false
            binding.btnStopTyping.visibility = android.view.View.VISIBLE
            binding.statusText.text = getString(R.string.running)
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@InputParagraphActivity),
                machineName,
                Prefs.password(this@InputParagraphActivity),
                "input_paragraph",
                JSONObject()
                    .put("text", text)
                    .put("wpm", wpm)
                    .put("addEnter", addEnter)
                    .put("async", true)
            )
            binding.btnStart.isEnabled = true
            if (result.success) {
                val eta = formatEta(words * 60 / wpm)
                binding.statusText.text = getString(R.string.typing_started, eta)
            } else {
                binding.statusText.text = getString(R.string.typing_failed, result.error ?: "error")
                binding.btnStopTyping.visibility = android.view.View.GONE
                Toast.makeText(this@InputParagraphActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopTyping() {
        lifecycleScope.launch {
            binding.btnStopTyping.isEnabled = false
            binding.statusText.text = getString(R.string.running)
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@InputParagraphActivity),
                machineName,
                Prefs.password(this@InputParagraphActivity),
                "stop_typing",
                JSONObject()
            )
            binding.btnStopTyping.isEnabled = true
            binding.btnStopTyping.visibility = android.view.View.GONE
            if (result.success) {
                binding.statusText.text = getString(R.string.typing_stopped)
            } else {
                binding.statusText.text = getString(R.string.typing_stop_failed, result.error ?: "error")
                Toast.makeText(this@InputParagraphActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startLiveView() {
        val url = Prefs.serverUrl(this)
        if (url.isBlank() || machineName.isBlank()) return
        ws = RuntimeBrokerApi.connectLive(url, machineName, Prefs.password(this), 1500,
            object : com.runtimebroker.app.api.LiveListener {
                override fun onConnected() {}

                override fun onFrame(imageBase64: String) {
                    runOnUiThread {
                        val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
                        val opts = BitmapFactory.Options()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val maxSide = 1600
                        var sample = 1
                        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
                        opts.inSampleSize = sample
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (bmp != null) {
                            binding.liveImage.setImageBitmap(bmp)
                        }
                    }
                }

                override fun onError(message: String) {}

                override fun onClosed() {}
            })
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b

    override fun onDestroy() {
        typingJob?.cancel()
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}