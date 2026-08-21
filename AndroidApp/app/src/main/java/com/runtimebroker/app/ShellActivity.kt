package com.runtimebroker.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityShellBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ShellActivity : BaseActivity() {

    private lateinit var binding: ActivityShellBinding

    private var machineName = ""
    private var runJob: Job? = null
    private var history = StringBuilder()
    private var commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShellBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()

        history.append("RuntimeBroker Shell — commands run on the remote device as cmd.exe\n")
        history.append("Type a command below and press Run.\n\n")
        binding.outputText.text = history.toString()

        binding.btnRun.setOnClickListener { runCommand() }
        binding.commandInput.setOnEditorActionListener { _, _, _ ->
            runCommand()
            true
        }
        binding.btnQuickDir.setOnClickListener { runQuick("dir") }
        binding.btnQuickIpconfig.setOnClickListener { runQuick("ipconfig") }
        binding.btnQuickTasklist.setOnClickListener { runQuick("tasklist") }
        binding.btnQuickNetstat.setOnClickListener { runQuick("netstat -an") }

        binding.commandInput.setRawInputType(InputType.TYPE_CLASS_TEXT)

        binding.btnClear.setOnClickListener { clearOutput() }
        binding.btnHistory.setOnClickListener { showHistory() }

        // Handle up/down arrow keys for history navigation
        binding.commandInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        navigateHistory(-1)
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        navigateHistory(1)
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    private fun runQuick(command: String) {
        binding.commandInput.setText(command)
        binding.commandInput.setSelection(command.length)
        runCommand()
    }

    private fun runCommand() {
        val command = binding.commandInput.text.toString().trim()
        if (command.isEmpty()) return
        
        // Add to history
        if (commandHistory.isEmpty() || commandHistory.last() != command) {
            commandHistory.add(command)
            if (commandHistory.size > 100) commandHistory.removeAt(0)
        }
        historyIndex = commandHistory.size

        runJob?.cancel()
        runJob = lifecycleScope.launch {
            binding.btnRun.isEnabled = false
            binding.statusText.text = getString(R.string.running)
            val result = RuntimeBrokerApi.shell(
                Prefs.serverUrl(this@ShellActivity),
                machineName,
                Prefs.password(this@ShellActivity),
                command
            )
            binding.btnRun.isEnabled = true
            history.append("${getString(R.string.shell_prompt)} $command\n")
            if (result.success) {
                val out = result.output?.takeIf { it.isNotBlank() } ?: getString(R.string.no_output)
                history.append(out).append('\n')
                result.error?.takeIf { it.isNotBlank() }?.let { history.append(it).append('\n') }
            } else {
                history.append(getString(R.string.capture_failed, result.error ?: "error")).append('\n')
                Toast.makeText(this@ShellActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
            history.append('\n')
            binding.outputText.text = history.toString()
            binding.statusText.text = getString(R.string.done)
            binding.outputScroll.post { binding.outputScroll.fullScroll(View.FOCUS_DOWN) }
            binding.commandInput.text.clear()
        }
    }

    private fun navigateHistory(direction: Int) {
        if (commandHistory.isEmpty()) return
        historyIndex = (historyIndex + direction).coerceIn(0, commandHistory.size - 1)
        if (historyIndex < commandHistory.size) {
            binding.commandInput.setText(commandHistory[historyIndex])
            binding.commandInput.setSelection(commandHistory[historyIndex].length)
        }
    }

    private fun clearOutput() {
        AlertDialog.Builder(this)
            .setTitle(R.string.shell_clear)
            .setMessage("Clear terminal output?")
            .setPositiveButton(R.string.shell_clear) { _, _ ->
                history = StringBuilder()
                history.append("RuntimeBroker Shell — commands run on the remote device as cmd.exe\n")
                history.append("Type a command below and press Run.\n\n")
                binding.outputText.text = history.toString()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHistory() {
        if (commandHistory.isEmpty()) {
            Toast.makeText(this, R.string.shell_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        
        val listView = ListView(this)
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, commandHistory.reversed())
        listView.adapter = adapter
        
        val popup = PopupWindow(
            listView,
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            (resources.displayMetrics.heightPixels * 0.5).toInt(),
            true
        )
        popup.setBackgroundDrawable(resources.getDrawable(android.R.color.white))
        popup.showAtLocation(binding.root, Gravity.CENTER, 0, 0)
        
        listView.setOnItemClickListener { _, _, position, _ ->
            val cmd = commandHistory.reversed()[position]
            binding.commandInput.setText(cmd)
            binding.commandInput.setSelection(cmd.length)
            popup.dismiss()
        }
    }

    override fun onDestroy() {
        runJob?.cancel()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}