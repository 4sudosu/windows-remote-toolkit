package com.runtimebroker.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityLiveBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
        ws = RuntimeBrokerApi.connectLive(url, machineName, Prefs.password(this), 800,
            object : com.runtimebroker.app.api.LiveListener {
                override fun onConnected() {
                    connected = true
                    runOnUiThread { binding.statusText.text = getString(R.string.live_connected) }
                }

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
                            currentBitmap?.recycle()
                            currentBitmap = bmp
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
        currentBitmap?.let { bmp ->
            matrix.reset()
            val viewWidth = binding.liveImage.width.toFloat()
            val viewHeight = binding.liveImage.height.toFloat()
            if (viewWidth > 0 && viewHeight > 0) {
                val bmpWidth: Float = bmp.width.toFloat()
                val bmpHeight: Float = bmp.height.toFloat()
                val fitScale = kotlin.math.min(viewWidth / bmpWidth, viewHeight / bmpHeight)
                val scaledWidth = bmpWidth * fitScale * scaleFactor
                val scaledHeight = bmpHeight * fitScale * scaleFactor
                val dx = (viewWidth - scaledWidth) / 2f
                val dy = (viewHeight - scaledHeight) / 2f
                matrix.postScale(fitScale * scaleFactor, fitScale * scaleFactor)
                matrix.postTranslate(dx, dy)
                binding.liveImage.setImageMatrix(matrix)
                binding.liveImage.setImageBitmap(bmp)
                updateZoomLevel()
            }
        }
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
        binding.zoomLevelText.visibility = if (scaleFactor != 1.0f) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b

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
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}