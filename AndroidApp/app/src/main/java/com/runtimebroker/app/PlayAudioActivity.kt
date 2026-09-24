package com.runtimebroker.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityPlayAudioBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

class PlayAudioActivity : BaseActivity() {

    private lateinit var binding: ActivityPlayAudioBinding

    private var machineName = ""
    private var selectedAudioUri: Uri? = null
    private var playbackJob: Job? = null

    private val pickAudio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedAudioUri = uri
                binding.tvSelectedFile.text = getString(R.string.audio_selected, uri.lastPathSegment ?: "Unknown")
                binding.btnPlayAudio.visibility = android.view.View.VISIBLE
                // Stop is always reachable so a playing audio can be killed fast.
                binding.btnStopAudio.visibility = android.view.View.VISIBLE
                binding.btnStopAudio.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayAudioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()
        supportActionBar?.title = getString(R.string.play_audio_title)

        binding.btnSelectAudio.setOnClickListener { selectAudio() }
        binding.btnPlayAudio.setOnClickListener { playAudio() }
        binding.btnStopAudio.setOnClickListener { stopAudio() }
    }

    private fun selectAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        pickAudio.launch(intent)
    }

    private fun playAudio() {
        selectedAudioUri?.let { uri ->
            binding.btnPlayAudio.isEnabled = false
            binding.statusText.text = getString(R.string.running)
            
            playbackJob = lifecycleScope.launch {
                // Read the audio file and send as base64
                val bytes = contentResolver.openInputStream(uri)?.readAllBytes() ?: byteArrayOf()
                val base64Audio = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                
                val result = RuntimeBrokerApi.command(
                    Prefs.serverUrl(this@PlayAudioActivity),
                    machineName,
                    Prefs.password(this@PlayAudioActivity),
                    "play_audio",
                    JSONObject()
                        .put("audio_base64", base64Audio)
                        .put("filename", uri.lastPathSegment ?: "audio.mp3")
                        .put("timeoutSec", 600)
                )
                
                binding.btnPlayAudio.isEnabled = true
                if (result.success) {
                    binding.statusText.text = getString(R.string.audio_playing)
                } else {
                    binding.statusText.text = getString(R.string.audio_play_failed, result.error ?: "error")
                    Toast.makeText(this@PlayAudioActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun stopAudio() {
        playbackJob?.cancel()
        lifecycleScope.launch {
            binding.btnStopAudio.isEnabled = false
            binding.statusText.text = getString(R.string.running)
            
            val result = RuntimeBrokerApi.command(
                Prefs.serverUrl(this@PlayAudioActivity),
                machineName,
                Prefs.password(this@PlayAudioActivity),
                "stop_audio",
                JSONObject()
            )
            
            binding.btnStopAudio.isEnabled = true
            binding.btnPlayAudio.isEnabled = true
            if (result.success) {
                binding.statusText.text = getString(R.string.audio_stopped)
            } else {
                binding.statusText.text = getString(R.string.audio_stop_failed, result.error ?: "error")
                Toast.makeText(this@PlayAudioActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        playbackJob?.cancel()
        super.onDestroy()
    }
}