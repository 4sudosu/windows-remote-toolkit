package com.mimo.pro

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mimo.pro.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    companion object {
        private const val REQ_TONE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bindGemini()
    }

    // ---- Gemini AI (screenshot default prompt) --------------------------------
    private fun bindGemini() {
        binding.etGeminiDefault.setText(Prefs.geminiPrompt(this))
        binding.btnSaveGemini.setOnClickListener {
            val p = binding.etGeminiDefault.text.toString().trim()
            if (p.isBlank()) {
                Toast.makeText(this, R.string.gemini_prompt_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.saveGeminiPrompt(this, p)
            Toast.makeText(this, R.string.gemini_default_saved, Toast.LENGTH_SHORT).show()
        }
        binding.btnOpenGemini.setOnClickListener { GeminiShare.openGemini(this) }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}