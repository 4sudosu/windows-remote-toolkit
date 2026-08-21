package com.runtimebroker.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Base64
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityScreenControlBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

class ScreenControlActivity : BaseActivity() {

    private lateinit var binding: ActivityScreenControlBinding

    private var machineName = ""
    private var ws: WebSocket? = null
    private var connected = false
    private var localRotateDegrees = 0

    private var bitmapW = 0
    private var bitmapH = 0
    private var remoteX = -1f
    private var remoteY = -1f
    private var downX = 0f
    private var downY = 0f
    private var downTotal = 0f
    private var lastMoveMs = 0L

    private lateinit var gestureDetector: GestureDetector
    private val matrix = Matrix()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()
        supportActionBar?.title = getString(R.string.screen_control_title)

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                sendMouse("right")
            }
        })

        binding.liveImage.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            handleTouch(event)
            true
        }

        binding.btnStop.setOnClickListener { finish() }
        binding.btnLeft.setOnClickListener { sendMouse("left") }
        binding.btnRight.setOnClickListener { sendMouse("right") }
        binding.btnWheelUp.setOnClickListener { sendMouse("wheel", delta = 120) }
        binding.btnWheelDown.setOnClickListener { sendMouse("wheel", delta = -120) }
        binding.btnRotateLocal.setOnClickListener { rotateLocalView() }
        binding.btnRotateRemote.setOnClickListener { rotateRemoteScreen() }

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
                            bitmapW = bmp.width
                            bitmapH = bmp.height
                            updateImageMatrix(bmp)
                        }
                    }
                }

                override fun onError(message: String) {
                    connected = false
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.live_disconnected)
                        Toast.makeText(this@ScreenControlActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onClosed() {
                    connected = false
                    runOnUiThread { binding.statusText.text = getString(R.string.live_disconnected) }
                }
            })
    }

    private fun updateImageMatrix(bmp: Bitmap) {
        matrix.reset()
        val viewWidth = binding.liveImage.width.toFloat()
        val viewHeight = binding.liveImage.height.toFloat()
        if (viewWidth > 0 && viewHeight > 0) {
            val fitScale = min(viewWidth / bmp.width, viewHeight / bmp.height)
            val scaledWidth = bmp.width * fitScale
            val scaledHeight = bmp.height * fitScale
            val dx = (viewWidth - scaledWidth) / 2
            val dy = (viewHeight - scaledHeight) / 2
            matrix.postTranslate(dx, dy)
            matrix.postScale(fitScale, fitScale)
            // Apply local rotation
            if (localRotateDegrees != 0) {
                matrix.postRotate(localRotateDegrees.toFloat(), viewWidth / 2, viewHeight / 2)
            }
            binding.liveImage.setImageMatrix(matrix)
            binding.liveImage.setImageBitmap(bmp)
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (!connected || bitmapW == 0 || bitmapH == 0) return true
        val (vx, vy) = viewToBitmap(event.x, event.y) ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = vx
                downY = vy
                downTotal = 0f
                remoteX = vx
                remoteY = vy
                sendMouse("move")
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = vx - downX
                val dy = vy - downY
                downTotal += abs(dx) + abs(dy)
                remoteX = vx
                remoteY = vy
                val now = System.currentTimeMillis()
                if (now - lastMoveMs > 50) {
                    lastMoveMs = now
                    sendMouse("move")
                }
                showPointer()
            }
            MotionEvent.ACTION_UP -> {
                if (downTotal < 24f) {
                    sendMouse("left")
                }
                showPointer()
            }
        }
        return true
    }

    /** Maps a touch point on the ImageView to a remote (bitmap) coordinate. */
    private fun viewToBitmap(vx: Float, vy: Float): Pair<Float, Float>? {
        val view = binding.liveImage
        if (view.width <= 0 || view.height <= 0) return null
        // Inverse matrix to map view coordinates to bitmap coordinates
        val inverseMatrix = Matrix()
        if (!matrix.invert(inverseMatrix)) return null
        val pts = floatArrayOf(vx, vy)
        inverseMatrix.mapPoints(pts)
        val bx = pts[0]
        val by = pts[1]
        if (bx < 0 || by < 0 || bx >= bitmapW || by >= bitmapH) return null
        return bx to by
    }

    private fun showPointer() {
        val view = binding.liveImage
        if (bitmapW == 0 || bitmapH == 0) return
        val inverseMatrix = Matrix()
        if (!matrix.invert(inverseMatrix)) return
        val pts = floatArrayOf(remoteX, remoteY)
        matrix.mapPoints(pts)
        binding.pointerLayer.x = pts[0] - 9f
        binding.pointerLayer.y = pts[1] - 9f
        binding.pointerLayer.visibility = View.VISIBLE
    }

    private fun sendMouse(action: String, delta: Int = 0) {
        val sock = ws ?: return
        val payload = JSONObject()
            .put("type", "mouse")
            .put("x", remoteX.toInt().coerceAtLeast(0))
            .put("y", remoteY.toInt().coerceAtLeast(0))
            .put("action", action)
        if (action == "wheel") payload.put("delta", delta)
        try {
            sock.send(payload.toString())
        } catch (e: Exception) {
            // stream may be closing
        }
    }

    private fun rotateLocalView() {
        localRotateDegrees = (localRotateDegrees + 90) % 360
        binding.statusText.text = getString(R.string.rotate_ok, localRotateDegrees)
        // Re-apply matrix with new rotation
        binding.liveImage.drawable?.let { d ->
            if (d is android.graphics.drawable.BitmapDrawable) {
                updateImageMatrix(d.bitmap)
            }
        }
    }

    private fun rotateRemoteScreen() {
        val target = (localRotateDegrees + 90) % 360 // For remote, we send the target rotation
        lifecycleScope.launch {
            binding.statusText.text = getString(R.string.rotate_to)
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@ScreenControlActivity),
                machineName,
                Prefs.password(this@ScreenControlActivity),
                "screen_rotate",
                JSONObject().put("degrees", target)
            )
            if (result.success) {
                binding.statusText.text = getString(R.string.rotate_ok, target)
            } else {
                binding.statusText.text = getString(R.string.rotate_failed, result.error ?: "error")
                Toast.makeText(this@ScreenControlActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b

    override fun onDestroy() {
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}