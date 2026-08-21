package com.runtimebroker.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityLiveBinding
import kotlinx.coroutines.Job
import kotlin.math.min
import okhttp3.WebSocket

class LiveScreenActivity : BaseActivity() {

    private lateinit var binding: ActivityLiveBinding

    private var machineName = ""
    private var ws: WebSocket? = null
    private var connected = false

    private var currentBitmap: Bitmap? = null
    private val matrix = Matrix()
    private var scaleFactor = 1.0f
    private var localRotateDegrees = 0
    private var fillMode = false
    private val minScale = 0.5f
    private val maxScale = 5.0f

    private var scaleDetector: ScaleGestureDetector? = null
    private var zoomUpdateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()
        supportActionBar?.title = getString(R.string.live_title)

        binding.btnStop.setOnClickListener { finish() }
        binding.btnRotateView.setOnClickListener {
            localRotateDegrees = (localRotateDegrees + 90) % 360
            updateImageMatrix()
        }
        binding.btnFitFill.setOnClickListener {
            fillMode = !fillMode
            binding.btnFitFill.text = getString(if (fillMode) R.string.fill else R.string.fit)
            updateImageMatrix()
        }

        binding.btnZoomIn.setOnClickListener { zoom(1.25f) }
        binding.btnZoomOut.setOnClickListener { zoom(0.8f) }
        binding.btnZoomReset.setOnClickListener { resetZoom() }

        scaleDetector = ScaleGestureDetector(this, ScaleListener())

        binding.liveImage.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            true
        }

        binding.liveImage.scaleType = android.widget.ImageView.ScaleType.MATRIX

        startStream()
    }

    private fun startStream() {
        val url = Prefs.serverUrl(this)
        if (url.isBlank() || machineName.isBlank()) {
            binding.statusText.text = getString(R.string.live_disconnected)
            return
        }
        binding.statusText.text = getString(R.string.live_connecting)
        ws = RuntimeBrokerApi.connectLive(url, machineName, Prefs.password(this), 500,
            object : com.runtimebroker.app.api.LiveListener {
                override fun onConnected() {
                    connected = true
                    runOnUiThread { binding.statusText.text = getString(R.string.live_connected) }
                }

                override fun onFrame(imageBase64: String) {
                    runOnUiThread {
                        val bytes = try {
                            Base64.decode(imageBase64, Base64.DEFAULT)
                        } catch (_: IllegalArgumentException) {
                            return@runOnUiThread
                        }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            val old = currentBitmap
                            currentBitmap = bmp
                            old?.recycle()
                            updateImageMatrix()
                        }
                    }
                }

                override fun onError(message: String) {
                    connected = false
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.live_disconnected)
                        Toast.makeText(this@LiveScreenActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onClosed() {
                    connected = false
                    runOnUiThread { binding.statusText.text = getString(R.string.live_disconnected) }
                }
            })
    }

    private fun updateImageMatrix() {
        val bmp = currentBitmap ?: return
        val viewWidth = binding.liveImage.width.toFloat()
        val viewHeight = binding.liveImage.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return

        // Rotation by 90/270 swaps which bitmap axis maps to the view width.
        val rotated = localRotateDegrees == 90 || localRotateDegrees == 270
        val bmpW = (if (rotated) bmp.height else bmp.width).toFloat()
        val bmpH = (if (rotated) bmp.width else bmp.height).toFloat()

        matrix.reset()
        // Rotate first (around the bitmap center), then scale/translate the
        // rotated bounds to fit or fill the view.
        matrix.postRotate(localRotateDegrees.toFloat(), bmp.width / 2f, bmp.height / 2f)

        val baseScale = if (fillMode) {
            maxOf(viewWidth / bmpW, viewHeight / bmpH)
        } else {
            min(viewWidth / bmpW, viewHeight / bmpH)
        }
        val s = baseScale * scaleFactor
        matrix.postScale(s, s, bmp.width / 2f, bmp.height / 2f)

        val scaledW = bmpW * s
        val scaledH = bmpH * s
        matrix.postTranslate((viewWidth - scaledW) / 2f, (viewHeight - scaledH) / 2f)

        binding.liveImage.setImageMatrix(matrix)
        binding.liveImage.setImageBitmap(bmp)
        updateZoomLevel()
    }

    private fun zoom(factor: Float) {
        scaleFactor = (scaleFactor * factor).coerceIn(minScale, maxScale)
        updateImageMatrix()
    }

    private fun resetZoom() {
        scaleFactor = 1.0f
        updateImageMatrix()
    }

    private fun updateZoomLevel() {
        binding.zoomLevelText.text = getString(R.string.zoom_level, scaleFactor * 100)
        binding.zoomLevelText.visibility =
            if (scaleFactor != 1.0f || localRotateDegrees != 0) View.VISIBLE else View.GONE
        if (scaleFactor != 1.0f || localRotateDegrees != 0) {
            binding.zoomLevelText.text = getString(R.string.zoom_level, scaleFactor * 100) +
                "  ·  ${localRotateDegrees}°"
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateImageMatrix()
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
            updateImageMatrix()
            return true
        }
    }

    override fun onDestroy() {
        zoomUpdateJob?.cancel()
        currentBitmap?.recycle()
        currentBitmap = null
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
