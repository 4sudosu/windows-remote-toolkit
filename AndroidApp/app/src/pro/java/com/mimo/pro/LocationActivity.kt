package com.mimo.pro

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.mimo.pro.api.RuntimeBrokerApi
import com.mimo.pro.databinding.ActivityLocationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocationActivity : BaseActivity() {

    private lateinit var binding: ActivityLocationBinding
    private var machineName = ""
    private var locationJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()
        supportActionBar?.title = getString(R.string.location_title)

        binding.btnGetLocation.setOnClickListener { requestLocation() }
        binding.btnStartTracking.setOnClickListener { toggleTracking() }
        binding.btnStopTracking.setOnClickListener { stopTracking() }
    }

    private fun requestLocation() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.btnGetLocation.isEnabled = false

        lifecycleScope.launch {
            try {
                val url = Connection.resolveBase(this@LocationActivity)
                val pw = Connection.resolvePassword(this@LocationActivity)
                val result = RuntimeBrokerApi.getLocation(url, machineName, pw)

                runOnUiThread {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnGetLocation.isEnabled = true
                    if (result != null && result.success) {
                        binding.tvLocation.text = formatLocation(result.location)
                        binding.tvAccuracy.text = getString(R.string.location_accuracy, result.accuracy)
                        binding.tvTimestamp.text = getString(R.string.location_timestamp, result.timestamp)
                        binding.locationCard.visibility = android.view.View.VISIBLE
                        Toast.makeText(this@LocationActivity, R.string.location_received, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@LocationActivity, getString(R.string.location_failed, result?.error ?: "Unknown error"), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnGetLocation.isEnabled = true
                    Toast.makeText(this@LocationActivity, getString(R.string.location_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun toggleTracking() {
        if (binding.btnStartTracking.text == getString(R.string.start_tracking)) {
            startTracking()
        } else {
            stopTracking()
        }
    }

    private fun startTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1001)
            return
        }

        binding.btnStartTracking.text = getString(R.string.stop_tracking)
        binding.btnStopTracking.visibility = android.view.View.VISIBLE
        binding.trackingStatus.text = getString(R.string.tracking_active)
        binding.trackingStatus.setTextColor(getColor(R.color.online_green))

        locationJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                if (!isActive) return@launch
                try {
                    val url = Connection.resolveBase(this@LocationActivity)
                    val pw = Connection.resolvePassword(this@LocationActivity)
                    val result = RuntimeBrokerApi.getLocation(url, machineName, pw)

                    if (result != null && result.success) {
                        withContext(Dispatchers.Main) {
                            binding.tvLocation.text = formatLocation(result.location)
                            binding.tvAccuracy.text = getString(R.string.location_accuracy, result.accuracy)
                            binding.tvTimestamp.text = getString(R.string.location_timestamp, result.timestamp)
                            binding.locationCard.visibility = android.view.View.VISIBLE
                        }
                    }
                } catch (e: Exception) {
                    // Ignore tracking errors
                }
                kotlinx.coroutines.delay(10000) // Update every 10 seconds
            }
        }
    }

    private fun stopTracking() {
        locationJob?.cancel()
        locationJob = null
        binding.btnStartTracking.text = getString(R.string.start_tracking)
        binding.btnStopTracking.visibility = android.view.View.GONE
        binding.trackingStatus.text = getString(R.string.tracking_stopped)
        binding.trackingStatus.setTextColor(getColor(R.color.offline_gray))
    }

    private fun formatLocation(loc: String?): String {
        // Parse "lat,lng" format
        val parts = loc?.split(",") ?: return "Unknown"
        if (parts.size == 2) {
            return "Lat: ${parts[0].trim()}, Lng: ${parts[1].trim()}"
        }
        return loc ?: "Unknown"
    }

    override fun onSupportNavigateUp(): Boolean {
        stopTracking()
        finish()
        return true
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }
}