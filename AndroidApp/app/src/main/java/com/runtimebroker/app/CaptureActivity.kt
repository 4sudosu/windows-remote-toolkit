package com.runtimebroker.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
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

    companion object {
        private const val REQ_STORAGE = 1001
        private const val ALBUM_PATH = "DCIM/RuntimeBroker"
    }

    private lateinit var binding: ActivityCaptureBinding
    private lateinit var albumAdapter: TempAlbumAdapter

    private var machineName = ""
    private var hostname = ""
    private var currentBitmap: Bitmap? = null
    private var captureJob: Job? = null
    private var refreshJob: Job? = null
    private var storing = false

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
        // V2: persist the auto-refresh interval (Off/3s/5s/10s) like MainActivity.
        binding.refreshSpinner.setSelection(
            when (Prefs.refreshSecs(this)) {
                3 -> 1
                10 -> 3
                5 -> 2
                else -> 0
            }
        )
        binding.refreshSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Prefs.saveRefreshSecs(
                    this@CaptureActivity,
                    when (position) {
                        1 -> 3
                        2 -> 5
                        3 -> 10
                        else -> 0
                    }
                )
                restartAutoRefresh()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnCapture.setOnClickListener { captureNow() }
        binding.btnSave.setOnClickListener { saveImage() }
        binding.btnCopy.setOnClickListener { copyImage() }
        binding.btnShare.setOnClickListener { shareImage() }

        // ── V2 temp album (Capture and Store → DCIM/RuntimeBroker) ──
        setupAlbum()
        refreshAlbum()
        binding.btnCaptureStore.setOnClickListener { captureAndStore() }
        binding.btnShareSelected.setOnClickListener { shareSelected() }
        binding.btnClearTemp.setOnClickListener { clearTemp() }
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

    // ── V2 temp album (Capture and Store → DCIM/RuntimeBroker) ──────────
    private fun safeMachine(): String =
        machineName.ifBlank { "device" }.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun albumPrefix(): String = "rb_${safeMachine()}_"

    private fun storagePerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        else
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )

    private fun hasStoragePermission(): Boolean =
        storagePerms().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun ensureStoragePermission(): Boolean {
        if (hasStoragePermission()) return true
        ActivityCompat.requestPermissions(this, storagePerms(), REQ_STORAGE)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            if (grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            ) {
                refreshAlbum()
            } else {
                Toast.makeText(this, R.string.album_perm_rationale, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAlbum() {
        albumAdapter = TempAlbumAdapter(emptyList()) { count ->
            binding.btnShareSelected.text = if (count > 0)
                getString(R.string.album_share_count, count)
            else
                getString(R.string.album_share_selected)
        }
        binding.rvTempAlbum.layoutManager = GridLayoutManager(this, 3)
        binding.rvTempAlbum.setHasFixedSize(true)
        binding.rvTempAlbum.isNestedScrollingEnabled = true
        binding.rvTempAlbum.adapter = albumAdapter
    }

    /** All photos of this device, newest first. */
    private fun queryAlbum(): List<AlbumItem> {
        val items = mutableListOf<AlbumItem>()
        try {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
            )
            val sel = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("${albumPrefix()}%")
            contentResolver.query(
                uri, projection, sel, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    items.add(
                        AlbumItem(
                            id,
                            ContentUris.withAppendedId(uri, id),
                            c.getString(nameCol) ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) { /* empty */ }
        return items
    }

    private fun refreshAlbum() {
        val items = if (hasStoragePermission()) queryAlbum() else emptyList()
        albumAdapter.update(items)
        binding.tvAlbumTitle.text = getString(R.string.album_title, items.size)
        binding.tvAlbumEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.rvTempAlbum.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.btnShareSelected.text = getString(R.string.album_share_selected)
    }

    private fun captureAndStore() {
        if (storing || machineName.isBlank()) return
        if (!ensureStoragePermission()) {
            binding.statusText.text = getString(R.string.album_perm_needed)
            return
        }
        storing = true
        binding.statusText.text = getString(R.string.capturing)
        lifecycleScope.launch {
            try {
                val result = RuntimeBrokerApi.capture(
                    Prefs.serverUrl(this@CaptureActivity),
                    machineName,
                    Prefs.password(this@CaptureActivity)
                )
                if (!result.success || result.image.isNullOrBlank()) {
                    binding.statusText.text = getString(
                        R.string.capture_failed,
                        result.error ?: "unknown error"
                    )
                    return@launch
                }
                val bytes = try {
                    Base64.decode(result.image, Base64.DEFAULT)
                } catch (e: Exception) {
                    binding.statusText.text = getString(R.string.capture_failed, "invalid image")
                    return@launch
                }
                val stored = storeToDcim(bytes)
                if (stored == null) {
                    binding.statusText.text = getString(R.string.album_store_failed)
                    return@launch
                }
                refreshAlbum()
                binding.statusText.text = getString(R.string.album_stored)
            } finally {
                storing = false
            }
        }
    }

    /** Saves bytes as DCIM/RuntimeBroker/rb_<device>_<ts>.png, returns its Uri. */
    private fun storeToDcim(bytes: ByteArray): Uri? = try {
        val name = "${albumPrefix()}${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM_PATH)
            }
        }
        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: run { contentResolver.delete(uri, null, null); return null }
        uri
    } catch (e: Exception) {
        null
    }

    private fun shareSelected() {
        val items = albumAdapter.selectedItems()
        if (items.isEmpty()) {
            binding.statusText.text = getString(R.string.album_select_first)
            return
        }
        try {
            // MediaStore content URIs are publicly readable — no FileProvider needed.
            val uris = ArrayList(items.map { it.uri })
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_title)))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearTemp() {
        if (!ensureStoragePermission()) {
            binding.statusText.text = getString(R.string.album_perm_needed)
            return
        }
        try {
            val items = queryAlbum()
            var deleted = 0
            for (it in items) {
                try {
                    if (contentResolver.delete(it.uri, null, null) > 0) deleted++
                } catch (e: Exception) { /* keep going */ }
            }
            albumAdapter.clearSelection()
            refreshAlbum()
            binding.statusText.text = if (deleted > 0)
                getString(R.string.album_cleared, deleted)
            else
                getString(R.string.album_already_empty)
        } catch (e: Exception) {
            Toast.makeText(this, "Clear failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}