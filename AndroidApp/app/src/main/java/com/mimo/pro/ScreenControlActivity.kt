package com.mimo.pro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.mimo.pro.api.RuntimeBrokerApi
import com.mimo.pro.databinding.ActivityScreenControlBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONObject
import kotlin.math.min

class ScreenControlActivity : BaseActivity() {

    private lateinit var binding: ActivityScreenControlBinding

    private var machineName = ""
    private var ws: WebSocket? = null
    private var connected = false
    private var localRotateDegrees = 0
    private var currentBitmap: Bitmap? = null

    private var bitmapW = 0
    private var bitmapH = 0
    private var remoteX = -1f
    private var remoteY = -1f
    private var downX = 0f
    private var downY = 0f
    private var lastMoveMs = 0L

    private var zoomScale = 1f
    private var panX = 0f
    private var panY = 0f
    private val matrix = Matrix()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()
        supportActionBar?.title = getString(R.string.screen_control_title)

        // The preview is view-only: drag pans while zoomed (zoom via buttons).
        // Cursor control moved to the trackpad below; the arrow follows the
        // agent-reported real cursor position, not our estimate.
        binding.liveImage.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_MOVE -> if (zoomScale > 1f && bitmapW != 0) {
                    panX += event.x - downX
                    panY += event.y - downY
                    downX = event.x
                    downY = event.y
                    updateImageMatrix()
                    showPointer()
                }
            }
            true
        }

        // Virtual trackpad: drag = relative cursor movement, pad full width â‰ˆ screen width
        binding.trackpad.setOnTouchListener { pad, event ->
            if (!connected || bitmapW == 0 || bitmapH == 0) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    if (remoteX < 0 || remoteY < 0) {
                        remoteX = bitmapW / 2f
                        remoteY = bitmapH / 2f
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val scale = bitmapW / pad.width.coerceAtLeast(1).toFloat()
                    val dx = (event.x - downX) * scale
                    val dy = (event.y - downY) * scale
                    downX = event.x
                    downY = event.y
                    remoteX = (remoteX + dx).coerceIn(0f, bitmapW - 1f)
                    remoteY = (remoteY + dy).coerceIn(0f, bitmapH - 1f)
                    val now = System.currentTimeMillis()
                    if (now - lastMoveMs >= 40) {
                        lastMoveMs = now
                        sendMouse("move")
                    }
                    showPointer()
                }
            }
            true
        }

        binding.btnStop.setOnClickListener { finish() }
        binding.btnLeft.setOnClickListener { sendMouse("left") }
        binding.btnRight.setOnClickListener { sendMouse("right") }
        binding.btnWheelUp.setOnClickListener { sendMouse("wheel", delta = 120) }
        binding.btnWheelDown.setOnClickListener { sendMouse("wheel", delta = -120) }
        binding.btnRotateLocal.setOnClickListener { rotateLocalView() }
        binding.btnZoomIn.setOnClickListener { zoom(1.3f) }
        binding.btnZoomOut.setOnClickListener { zoom(1f / 1.3f) }

        // Cursor targets bitmap pixels, so the image mapping must be matrix-driven.
        binding.liveImage.scaleType = android.widget.ImageView.ScaleType.MATRIX

        startStream()
    }

    private fun startStream() {
        val url = Connection.resolveBase(this)
        if (url.isBlank() || machineName.isBlank()) {
            binding.statusText.text = getString(R.string.live_disconnected)
            return
        }
        binding.statusText.text = getString(R.string.live_connecting)
        ws = RuntimeBrokerApi.connectLive(url, machineName, Connection.resolvePassword(this), 800,
            object : com.mimo.pro.api.LiveListener {
                override fun onCursor(cx: Float, cy: Float) {
                    // Agent-reported real cursor position (fractions of the
                    // frame) â€” exact, unlike the trackpad movement estimate.
                    if (cx < 0 || cy < 0 || bitmapW == 0) return
                    remoteX = cx * bitmapW
                    remoteY = cy * bitmapH
                    runOnUiThread { showPointer() }
                }

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
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
                        opts.inSampleSize = sample
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (bmp != null) {
                            bitmapW = bmp.width
                            bitmapH = bmp.height
                            currentBitmap?.let { if (!it.isRecycled) it.recycle() }
                            currentBitmap = bmp
                            updateImageMatrix()
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

    /** Same transform as LiveScreenActivity: bitmap center â†’ rotate â†’ scale â†’ view center. */
    private fun updateImageMatrix() {
        val bmp = currentBitmap ?: return
        val viewWidth = binding.liveImage.width.toFloat()
        val viewHeight = binding.liveImage.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return

        // Rotation by 90/270 swaps which bitmap axis maps to the view width,
        // so the fit scale must be computed with swapped dimensions.
        val rotated = localRotateDegrees == 90 || localRotateDegrees == 270
        val bmpW = (if (rotated) bmp.height else bmp.width).toFloat()
        val bmpH = (if (rotated) bmp.width else bmp.height).toFloat()
        val s = min(viewWidth / bmpW, viewHeight / bmpH) * zoomScale

        // Clamp pan so the zoomed image can't be dragged fully off screen
        val dispW = bmpW * s
        val dispH = bmpH * s
        panX = clampPan(panX, dispW, viewWidth)
        panY = clampPan(panY, dispH, viewHeight)

        matrix.reset()
        // 1. Move bitmap center to origin
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        // 2. Rotate around origin (now at bitmap center)
        if (localRotateDegrees != 0) {
            matrix.postRotate(localRotateDegrees.toFloat())
        }
        // 3. Scale (fit + user zoom), 4. Place at view center with pan
        matrix.postScale(s, s)
        matrix.postTranslate(viewWidth / 2f + panX, viewHeight / 2f + panY)

        binding.liveImage.setImageMatrix(matrix)
        binding.liveImage.setImageBitmap(bmp)
    }

    private fun clampPan(pan: Float, disp: Float, view: Float): Float {
        if (disp <= view) return 0f
        return pan.coerceIn((view - disp) / 2f, (disp - view) / 2f)
    }

    private fun showPointer() {
        val view = binding.liveImage
        if (bitmapW == 0 || bitmapH == 0) return
        val inverseMatrix = Matrix()
        if (!matrix.invert(inverseMatrix)) return
        val pts = floatArrayOf(remoteX, remoteY)
        matrix.mapPoints(pts)
        binding.pointerLayer.x = pts[0] - 12f
        binding.pointerLayer.y = pts[1] - 12f
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
        // Re-apply matrix with the new rotation using the latest frame
        updateImageMatrix()
    }

    private fun zoom(factor: Float) {
        zoomScale = (zoomScale * factor).coerceIn(1f, 4f)
        if (zoomScale <= 1f) { panX = 0f; panY = 0f }
        updateImageMatrix()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateImageMatrix()
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
