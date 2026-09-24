package com.runtimebroker.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityInstallAppBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

class InstallAppActivity : BaseActivity() {

    private lateinit var binding: ActivityInstallAppBinding

    private var machineName = ""
    private var selectedApkUri: Uri? = null

    private val pickApk = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedApkUri = uri
                binding.tvSelectedApk.text = getString(R.string.apk_selected, uri.lastPathSegment ?: "Unknown")
                binding.btnInstallApp.visibility = android.view.View.VISIBLE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstallAppBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()
        supportActionBar?.title = getString(R.string.install_app_title)

        binding.btnSelectApk.setOnClickListener { selectApk() }
        binding.btnInstallApp.setOnClickListener { installApp() }
    }

    private fun selectApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/vnd.android.package-archive"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        pickApk.launch(intent)
    }

    private fun installApp() {
        selectedApkUri?.let { uri ->
            lifecycleScope.launch {
                binding.btnInstallApp.isEnabled = false
                binding.statusText.text = getString(R.string.running)
                
                val bytes = contentResolver.openInputStream(uri)?.readAllBytes() ?: byteArrayOf()
                val base64Apk = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                
                val result = RuntimeBrokerApi.command(
                    Prefs.serverUrl(this@InstallAppActivity),
                    machineName,
                    Prefs.password(this@InstallAppActivity),
                    "install_app",
                    JSONObject()
                        .put("apk_base64", base64Apk)
                        .put("filename", uri.lastPathSegment ?: "app.apk")
                )
                
                binding.btnInstallApp.isEnabled = true
                if (result.success) {
                    binding.statusText.text = getString(R.string.app_installed)
                    Toast.makeText(this@InstallAppActivity, R.string.app_installed, Toast.LENGTH_LONG).show()
                } else {
                    binding.statusText.text = getString(R.string.install_failed, result.error ?: "error")
                    Toast.makeText(this@InstallAppActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}