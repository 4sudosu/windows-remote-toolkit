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
    private var panX = 0f
    private var panY = 0f
    private val minScale = 1.0f
    private val maxScale = 6.0f

    private var scaleDetector: ScaleGestureDetector? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false

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
            clampPan()
            updateImageMatrix()
        }
        binding.btnFitFill.setOnClickListener {
            fillMode = !fillMode
            binding.btnFitFill.text = getString(if (fillMode) R.string.fill else R.string.fit)
            panX = 0f; panY = 0f; scaleFactor = 1.0f
            updateImageMatrix()
        }

        scaleDetector = ScaleGestureDetector(this, ScaleListener())

        binding.liveImage.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x; lastTouchY = event.y; dragging = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging && (scaleFactor > 1.001f || fillMode)) {
                        panX += event.x - lastTouchX
                        panY += event.y - lastTouchY
                        clampPan()
                        updateImageMatrix()
                    }
                    lastTouchX = event.x; lastTouchY = event.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            true
        }

        binding.liveImage.scaleType = android.widget.ImageView.ScaleType.MATRIX

        startStream()
    }

    /** Keeps the image from being dragged fully off-screen. */
    private fun clampPan() {
        val bmp = currentBitmap ?: return
        val vw = binding.liveImage.width.toFloat()
        val vh = binding.liveImage.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val rotated = localRotateDegrees == 90 || localRotateDegrees == 270
        val bmpW = (if (rotated) bmp.height else bmp.width).toFloat()
        val bmpH = (if (rotated) bmp.width else bmp.height).toFloat()
        val base = if (fillMode) maxOf(vw / bmpW, vh / bmpH) else min(vw / bmpW, vh / bmpH)
        val s = base * scaleFactor
        val overX = (bmpW * s - vw) / 2f
        val overY = (bmpH * s - vh) / 2f
        panX = panX.coerceIn(-overX.coerceAtLeast(0f), overX.coerceAtLeast(0f))
        panY = panY.coerceIn(-overY.coerceAtLeast(0f), overY.coerceAtLeast(0f))
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

        val baseScale = if (fillMode) {
            maxOf(viewWidth / bmpW, viewHeight / bmpH)
        } else {
            min(viewWidth / bmpW, viewHeight / bmpH)
        }
        val s = baseScale * scaleFactor

        matrix.reset()
        // Rotate around the bitmap center first…
        matrix.postRotate(localRotateDegrees.toFloat(), bmp.width / 2f, bmp.height / 2f)
        // …then scale around the bitmap center…
        matrix.postScale(s, s, bmp.width / 2f, bmp.height / 2f)
        // …then place centered in the view and apply the user's pan offset.
        val dx = (viewWidth - bmpW * s) / 2f + panX
        val dy = (viewHeight - bmpH * s) / 2f + panY
        matrix.postTranslate(dx, dy)

        binding.liveImage.setImageMatrix(matrix)
        binding.liveImage.setImageBitmap(bmp)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { clampPan(); updateImageMatrix() }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scaleFactor
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
            if (scaleFactor <= 1.001f) { panX = 0f; panY = 0f } else {
                // Zoom toward the pinch focus point.
                val fx = detector.focusX
                val fy = detector.focusY
                val vw = binding.liveImage.width.toFloat()
                val vh = binding.liveImage.height.toFloat()
                if (vw > 0 && vh > 0) {
                    val ratio = scaleFactor / old
                    panX = (panX + fx - vw / 2f) * ratio - (fx - vw / 2f)
                    panY = (panY + fy - vh / 2f) * ratio - (fy - vh / 2f)
                }
            }
            clampPan()
            updateImageMatrix()
            return true
        }
    }

    override fun onDestroy() {
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
