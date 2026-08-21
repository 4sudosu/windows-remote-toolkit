package com.runtimebroker.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityParagraphBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONObject

class InputParagraphActivity : BaseActivity() {

    private lateinit var binding: ActivityParagraphBinding

    private var machineName = ""
    private var ws: WebSocket? = null
    private var typingJob: Job? = null
    private var progressJob: Job? = null

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
        // No word limit — long paragraphs are fine; the ETA scales with length.
        val words = currentWords()
        val wpm = binding.wpmInput.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: 50
        val addEnter = binding.enterCheck.isChecked
        val totalSecs = (words * 60.0 / wpm).toInt() + 30

        lifecycleScope.launch {
            binding.btnStart.isEnabled = false
            binding.btnStopTyping.visibility = View.VISIBLE
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
                    .put("timeoutSec", totalSecs + 120)
            )
            if (result.success) {
                val eta = formatEta(words * 60 / wpm)
                binding.statusText.text = getString(R.string.typing_started, eta)
                startProgressCountdown(words * 60 / wpm)
            } else {
                binding.btnStart.isEnabled = true
                binding.btnStopTyping.visibility = View.GONE
                binding.statusText.text = getString(R.string.typing_failed, result.error ?: "error")
                Toast.makeText(this@InputParagraphActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Live progress bar + seconds-left countdown while the agent types. */
    private fun startProgressCountdown(totalSecs: Int) {
        progressJob?.cancel()
        binding.progressBar.max = totalSecs
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE
        binding.timeLeftText.visibility = View.VISIBLE
        progressJob = lifecycleScope.launch {
            var elapsed = 0
            while (isActive && elapsed < totalSecs) {
                delay(1000)
                elapsed++
                binding.progressBar.progress = elapsed
                binding.timeLeftText.text =
                    "${getString(R.string.time_left)} ${formatEta(totalSecs - elapsed)}"
            }
            finishProgress()
        }
    }

    private fun finishProgress() {
        binding.progressBar.visibility = View.GONE
        binding.timeLeftText.visibility = View.GONE
        binding.btnStart.isEnabled = true
        binding.btnStopTyping.visibility = View.GONE
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
            progressJob?.cancel()
            finishProgress()
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
        ws = RuntimeBrokerApi.connectLive(url, machineName, Prefs.password(this), 1000,
            object : com.runtimebroker.app.api.LiveListener {
                override fun onConnected() {}

                override fun onFrame(imageBase64: String) {
                    runOnUiThread {
                        val bytes = try {
                            Base64.decode(imageBase64, Base64.DEFAULT)
                        } catch (_: IllegalArgumentException) {
                            return@runOnUiThread
                        }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) applyCropFill(bmp)
                    }
                }

                override fun onError(message: String) {}

                override fun onClosed() {}
            })
    }

    /**
     * Scales the frame to FILL the preview box (center-crop) so the stream
     * matches the agent's aspect ratio with no black bars top/bottom.
     */
    private fun applyCropFill(bmp: Bitmap) {
        val view = binding.liveImage
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw <= 0 || vh <= 0) {
            view.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(bmp)
            return
        }
        val scale = maxOf(vw / bmp.width, vh / bmp.height)
        val matrix = Matrix().apply { postScale(scale, scale) }
        val dx = (vw - bmp.width * scale) / 2f
        val dy = (vh - bmp.height * scale) / 2f
        matrix.postTranslate(dx, dy)
        view.scaleType = android.widget.ImageView.ScaleType.MATRIX
        view.imageMatrix = matrix
        view.setImageBitmap(bmp)
    }

    override fun onDestroy() {
        typingJob?.cancel()
        progressJob?.cancel()
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
