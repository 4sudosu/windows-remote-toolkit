package com.runtimebroker.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityCameraBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

class CameraActivity : BaseActivity() {

    private lateinit var binding: ActivityCameraBinding

    private var machineName = ""
    private var job: Job? = null
    private var photoB64: String? = null
    private var lastUri: Uri? = null
    private var lastMime = "image/jpeg"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()

        binding.btnPhoto.setOnClickListener { capturePhoto() }
        binding.btnVideo.setOnClickListener { recordVideo() }
        binding.btnSave.setOnClickListener { savePhoto() }
        binding.btnShare.setOnClickListener { sharePhoto() }
        binding.btnOpen.setOnClickListener { openLast() }
    }

    private fun seconds(): Int =
        binding.secondsInput.text.toString().toIntOrNull()?.coerceIn(1, 120) ?: 10

    private fun capturePhoto() {
        job?.cancel()
        job = lifecycleScope.launch {
            setBusy(true)
            binding.statusText.text = getString(R.string.capturing_photo)
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@CameraActivity),
                machineName,
                Prefs.password(this@CameraActivity),
                "camera_photo"
            )
            setBusy(false)
            if (result.success && !result.output.isNullOrBlank()) {
                photoB64 = result.output
                val bytes = Base64.decode(result.output, Base64.DEFAULT)
                binding.resultImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                binding.btnSave.isEnabled = true
                binding.btnShare.isEnabled = true
                binding.btnOpen.isEnabled = false
                lastMime = "image/jpeg"
                binding.statusText.text = getString(R.string.done)
            } else {
                binding.statusText.text = result.error ?: "error"
                Toast.makeText(this@CameraActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun recordVideo() {
        job?.cancel()
        job = lifecycleScope.launch {
            setBusy(true)
            binding.statusText.text = getString(R.string.recording)
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@CameraActivity),
                machineName,
                Prefs.password(this@CameraActivity),
                "camera_video",
                JSONObject().put("seconds", seconds())
            )
            setBusy(false)
            if (result.success && !result.output.isNullOrBlank()) {
                val name = "wsm-camera-${System.currentTimeMillis()}.mp4"
                val uri = MediaSaver.save(this@CameraActivity, "video/mp4", name, result.output, "camera")
                if (uri != null) {
                    lastUri = uri
                    lastMime = "video/mp4"
                    binding.btnOpen.isEnabled = true
                    binding.statusText.text = getString(R.string.video_saved)
                    Toast.makeText(this@CameraActivity, R.string.video_saved, Toast.LENGTH_SHORT).show()
                } else {
                    binding.statusText.text = getString(R.string.save_failed)
                }
            } else {
                binding.statusText.text = result.error ?: "error"
                Toast.makeText(this@CameraActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnPhoto.isEnabled = !busy
        binding.btnVideo.isEnabled = !busy
    }

    private fun savePhoto() {
        val b64 = photoB64 ?: return
        val name = "wsm-camera-${System.currentTimeMillis()}.jpg"
        val uri = MediaSaver.save(this, "image/jpeg", name, b64, "camera")
        if (uri != null) {
            lastUri = uri
            binding.btnOpen.isEnabled = true
            binding.statusText.text = getString(R.string.saved_media, "camera")
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePhoto() {
        val b64 = photoB64 ?: return
        val name = "wsm-camera-${System.currentTimeMillis()}.jpg"
        val uri = MediaSaver.save(this, "image/jpeg", name, b64, "camera")
        if (uri != null) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_title)))
        } else {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLast() {
        val uri = lastUri ?: return
        if (!MediaSaver.open(this, uri, lastMime)) {
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