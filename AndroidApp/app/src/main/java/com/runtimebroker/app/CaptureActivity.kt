package com.runtimebroker.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityCaptureBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CaptureActivity : BaseActivity() {

    private lateinit var binding: ActivityCaptureBinding

    private var machineName = ""
    private var hostname = ""
    private var currentBitmap: Bitmap? = null
    private var captureJob: Job? = null
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.captureToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()
        hostname = intent.getStringExtra(MainActivity.EXTRA_HOST).orEmpty()
        binding.captureTitle.text = hostname

        if (machineName.isNotBlank()) {
            Prefs.saveLastDevice(this, machineName, hostname)
        }

        val options = listOf(
            getString(R.string.refresh_off),
            getString(R.string.refresh_3s),
            getString(R.string.refresh_5s),
            getString(R.string.refresh_10s)
        )
        binding.refreshSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            options
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.refreshSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                restartAutoRefresh()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnCapture.setOnClickListener { captureNow() }
        binding.btnSave.setOnClickListener { saveImage() }
        binding.btnCopy.setOnClickListener { copyImage() }
        binding.btnShare.setOnClickListener { shareImage() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        captureJob?.cancel()
        super.onDestroy()
    }

    private fun autoRefreshSeconds(): Int = when (binding.refreshSpinner.selectedItemPosition) {
        1 -> 3
        2 -> 5
        3 -> 10
        else -> 0
    }

    private fun restartAutoRefresh() {
        refreshJob?.cancel()
        val secs = autoRefreshSeconds()
        if (secs <= 0) return
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(secs * 1000L)
                captureNow()
            }
        }
    }

    private fun captureNow() {
        if (machineName.isBlank()) return
        captureJob?.cancel()
        captureJob = lifecycleScope.launch {
            binding.btnCapture.isEnabled = false
            binding.statusText.text = getString(R.string.capturing)
            val result = RuntimeBrokerApi.capture(
                Prefs.serverUrl(this@CaptureActivity),
                machineName,
                Prefs.password(this@CaptureActivity)
            )
            binding.btnCapture.isEnabled = true
            if (result.success && !result.image.isNullOrBlank()) {
                val bytes = Base64.decode(result.image, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                currentBitmap = bmp
                binding.captureImage.setImageBitmap(bmp)
                binding.statusText.text = getString(
                    R.string.capture_ok,
                    result.at?.let { formatTime(it) } ?: "now"
                )
            } else {
                binding.statusText.text = getString(
                    R.string.capture_failed,
                    result.error ?: "unknown error"
                )
            }
        }
    }

    private fun formatTime(iso: String): String {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in formats) {
            try {
                val src = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = src.parse(iso)
                if (parsed != null) {
                    return SimpleDateFormat("HH:mm:ss", Locale.US).format(parsed)
                }
            } catch (e: Exception) {
                // try next pattern
            }
        }
        return iso
    }

    private fun cachedImageUri(): Uri? {
        val bmp = currentBitmap ?: return null
        val dir = File(cacheDir, "screenshots").apply { mkdirs() }
        val file = File(dir, "RuntimeBroker_${hostname}_${System.currentTimeMillis()}.png")
        return try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    private fun copyImage() {
        val uri = cachedImageUri()
        if (uri == null) {
            Toast.makeText(this, R.string.no_screenshot, Toast.LENGTH_SHORT).show()
            return
        }
        val clip = ClipData.newUri(contentResolver, getString(R.string.capture_title), uri)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareImage() {
        val uri = cachedImageUri()
        if (uri == null) {
            Toast.makeText(this, R.string.no_screenshot, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    private fun saveImage() {
        val bmp = currentBitmap
        if (bmp == null) {
            Toast.makeText(this, R.string.no_screenshot, Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "RuntimeBroker_${hostname}_$timestamp.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/RuntimeBroker"
                )
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val out = contentResolver.openOutputStream(uri)
        if (out == null) {
            contentResolver.delete(uri, null, null)
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        out.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }
}