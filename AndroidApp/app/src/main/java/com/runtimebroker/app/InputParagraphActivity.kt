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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var sessionActive = false

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
        setupLiveGestures()
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
        // Prevent duplicate typing jobs — if already typing, ignore the click
        if (isTyping) return
        // No word limit — long paragraphs are fine; the ETA scales with length.
        val words = currentWords()
        val wpm = binding.wpmInput.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: 50
        val addEnter = binding.enterCheck.isChecked
        val totalSecs = (words * 60.0 / wpm).toInt() + 30

        lifecycleScope.launch {
            binding.btnStart.isEnabled = false
            // Button always visible - no visibility toggle needed
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
                isTyping = true
                val eta = formatEta(words * 60 / wpm)
                binding.statusText.text = getString(R.string.typing_started, eta)
                // Remember the target so the notification's STOP button works
                // even after this screen is gone.
                Prefs.saveLastDevice(this@InputParagraphActivity, machineName, Prefs.lastHost(this@InputParagraphActivity))
                Notifications.showTyping(this@InputParagraphActivity)
                startProgressCountdown(words * 60 / wpm)
} else {
                binding.btnStart.isEnabled = true
                // Button always visible - no visibility toggle needed
                binding.statusText.text = getString(R.string.typing_failed, result.error ?: "error")
                Toast.makeText(this@InputParagraphActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
                isTyping = false
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
        isTyping = false
        Notifications.cancelTyping(this)
        binding.progressBar.visibility = View.GONE
        binding.timeLeftText.visibility = View.GONE
        binding.btnStart.isEnabled = true
        // Button always visible - no visibility toggle needed
    }

    private fun stopTyping() {
        // Disable button immediately to prevent double-click
        binding.btnStopTyping.isEnabled = false
        lifecycleScope.launch {
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@InputParagraphActivity),
                machineName,
                Prefs.password(this@InputParagraphActivity),
                "stop_typing",
                JSONObject()
            )
            // Button re-enabled after command completes
            binding.btnStopTyping.isEnabled = true
            progressJob?.cancel()
            // Button always visible - no visibility toggle needed
            if (result.success) {
                binding.statusText.text = getString(R.string.typing_stopped)
                isTyping = false
            } else {
                binding.statusText.text = getString(R.string.typing_stop_failed, result.error ?: "error")
                Toast.makeText(this@InputParagraphActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
                isTyping = false
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
                        if (bmp != null) {
                            val old = liveBitmap
                            liveBitmap = bmp
                            old?.recycle()
                            updateLiveMatrix()
                        }
                    }
                }

                override fun onError(message: String) {}

                override fun onClosed() {
                    binding.liveImage.postDelayed({ startLiveView() }, 3000)
                }
            })
    }

    // ---- live preview: whole agent screen, draggable + pinch-zoom --------
    private var liveBitmap: Bitmap? = null
    private val liveMatrix = Matrix()
    private var liveScale = 1.0f
    private var livePanX = 0f
    private var livePanY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var scaleDetector: android.view.ScaleGestureDetector? = null

    private fun setupLiveGestures() {
        scaleDetector = android.view.ScaleGestureDetector(this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                    liveScale = (liveScale * d.scaleFactor).coerceIn(1f, 6f)
                    if (liveScale <= 1.001f) { livePanX = 0f; livePanY = 0f }
                    clampLivePan()
                    updateLiveMatrix()
                    return true
                }
            })
        binding.liveImage.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (liveScale > 1.001f) {
                        livePanX += event.x - lastX
                        livePanY += event.y - lastY
                        clampLivePan()
                        updateLiveMatrix()
                    }
                    lastX = event.x; lastY = event.y
                }
            }
            true
        }
        binding.liveImage.scaleType = android.widget.ImageView.ScaleType.MATRIX
    }

    private fun clampLivePan() {
        val bmp = liveBitmap ?: return
        val vw = binding.liveImage.width.toFloat()
        val vh = binding.liveImage.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val s = minOf(vw / bmp.width, vh / bmp.height) * liveScale
        val overX = ((bmp.width * s - vw) / 2f).coerceAtLeast(0f)
        val overY = ((bmp.height * s - vh) / 2f).coerceAtLeast(0f)
        livePanX = livePanX.coerceIn(-overX, overX)
        livePanY = livePanY.coerceIn(-overY, overY)
    }

    /** Fits the WHOLE agent frame in the preview — correct aspect ratio, no crop. */
    private fun updateLiveMatrix() {
        val bmp = liveBitmap ?: return
        val view = binding.liveImage
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw <= 0 || vh <= 0) {
            view.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            view.setImageBitmap(bmp)
            return
        }
        val base = minOf(vw / bmp.width, vh / bmp.height)
        val s = base * liveScale
        liveMatrix.reset()
        liveMatrix.postScale(s, s)
        liveMatrix.postTranslate(
            (vw - bmp.width * s) / 2f + livePanX,
            (vh - bmp.height * s) / 2f + livePanY
        )
        view.imageMatrix = liveMatrix
        view.setImageBitmap(bmp)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateLiveMatrix()
    }

    private var isTyping = false

    override fun onDestroy() {
        if (isTyping) {
            // lifecycleScope is already DEAD here, so the stop command must go
            // out through an independent scope — otherwise closing the screen
            // leaves the remote paragraph typing forever.
            val app = applicationContext
            val url = Prefs.serverUrl(app)
            val machine = machineName
            val pw = Prefs.password(app)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (url.isNotBlank() && machine.isNotBlank()) {
                        RuntimeBrokerApi.command(url, machine, pw, "stop_typing", JSONObject())
                    }
                } catch (_: Exception) {
                } finally {
                    Notifications.cancelTyping(app)
                }
            }
        }
        Notifications.cancelTyping(this)
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
