package com.runtimebroker.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityFilemanagerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray

class FileManagerActivity : BaseActivity() {

    private lateinit var binding: ActivityFilemanagerBinding
    private lateinit var adapter: FileAdapter

    private var machineName = ""
    private var currentPath = "C:\\"
    private var listJob: Job? = null

    private val uploadLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> uploadFile(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilemanagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()

        adapter = FileAdapter { entry -> onFileClick(entry) }
        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = adapter

        binding.btnGo.setOnClickListener { navigate(binding.pathInput.text.toString()) }
        binding.btnUp.setOnClickListener { goUp() }
        binding.btnRefresh.setOnClickListener { loadFiles() }
        binding.btnUpload.setOnClickListener { pickUpload() }

        loadFiles()
    }

    private fun onFileClick(entry: FileEntry) {
        if (entry.isDir) {
            navigate(entry.path)
        } else {
            downloadFile(entry)
        }
    }

    private fun navigate(path: String) {
        val p = path.trim().ifBlank { "C:\\" }
        currentPath = p
        binding.pathInput.setText(p)
        loadFiles()
    }

    private fun goUp() {
        val parent = parentOf(currentPath)
        if (parent == currentPath) {
            Toast.makeText(this, currentPath, Toast.LENGTH_SHORT).show()
            return
        }
        navigate(parent)
    }

    private fun parentOf(path: String): String {
        val trimmed = path.trimEnd('\\', '/')
        val idx = maxOf(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'))
        if (idx <= 0) return "C:\\"
        val parent = trimmed.substring(0, idx)
        return if (parent.length == 2 && parent[1] == ':') parent + "\\" else parent
    }

    private fun loadFiles() {
        listJob?.cancel()
        listJob = lifecycleScope.launch {
            binding.statusText.text = getString(R.string.loading)
            binding.emptyText.visibility = View.GONE
            val result = RuntimeBrokerApi.listFiles(
                Prefs.serverUrl(this@FileManagerActivity),
                machineName,
                Prefs.password(this@FileManagerActivity),
                currentPath
            )
            if (result.success) {
                val data = result.data as? JSONArray
                val entries = data?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        FileEntry(
                            name = o.optString("name"),
                            path = o.optString("path"),
                            isDir = o.optBoolean("isDir"),
                            size = o.optLong("size"),
                            modified = o.optString("modified")
                        )
                    }
                } ?: emptyList()
                adapter.submit(entries)
                binding.statusText.text = result.output ?: currentPath
                binding.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            } else {
                binding.statusText.text = getString(R.string.files_error, result.error ?: "error")
                Toast.makeText(this@FileManagerActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadFile(entry: FileEntry) {
        lifecycleScope.launch {
            binding.statusText.text = getString(R.string.downloading)
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@FileManagerActivity),
                machineName,
                Prefs.password(this@FileManagerActivity),
                "read_file",
                org.json.JSONObject().put("path", entry.path)
            )
            if (result.success && !result.output.isNullOrBlank()) {
                val mime = mimeFor(entry.name)
                val uri = MediaSaver.save(this@FileManagerActivity, mime, entry.name, result.output, "files")
                if (uri != null) {
                    binding.statusText.text = getString(R.string.downloaded)
                    Toast.makeText(this@FileManagerActivity, R.string.downloaded, Toast.LENGTH_SHORT).show()
                } else {
                    binding.statusText.text = getString(R.string.file_error, "Download", "save failed")
                }
            } else {
                binding.statusText.text = getString(R.string.file_error, "Download", result.error ?: "error")
            }
        }
    }

    private fun pickUpload() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        uploadLauncher.launch(intent)
    }

    private fun uploadFile(uri: android.net.Uri) {
        lifecycleScope.launch {
            binding.statusText.text = getString(R.string.uploading)
            val name = queryName(uri) ?: "upload.bin"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                binding.statusText.text = getString(R.string.cannot_upload)
                return@launch
            }
            if (bytes.size > 30 * 1024 * 1024) {
                binding.statusText.text = getString(R.string.file_too_large)
                return@launch
            }
            val b64 = Base64.encodeToString(bytes, Base64.DEFAULT)
            val target = if (currentPath.endsWith("\\")) currentPath + name else "$currentPath\\$name"
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@FileManagerActivity),
                machineName,
                Prefs.password(this@FileManagerActivity),
                "write_file",
                org.json.JSONObject()
                    .put("path", target)
                    .put("base64", b64)
            )
            if (result.success) {
                binding.statusText.text = getString(R.string.uploaded, name)
                loadFiles()
            } else {
                binding.statusText.text = getString(R.string.file_error, "Upload", result.error ?: "error")
            }
        }
    }

    private fun queryName(uri: android.net.Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return null
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "txt", "log", "json", "xml", "csv", "md" -> "text/plain"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    override fun onDestroy() {
        listJob?.cancel()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}