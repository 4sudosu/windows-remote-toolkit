package com.runtimebroker.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityShellBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

class ShellActivity : BaseActivity() {

    private lateinit var binding: ActivityShellBinding

    private var machineName = ""
    private var runJob: Job? = null
    private var history = StringBuilder()
    private var commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var cwd = ""
    private var initJob: Job? = null

    companion object {
        /** Common cmd.exe / PowerShell commands offered as TAB-completion suggestions. */
        private val SUGGESTIONS = listOf(
            "dir", "cd ", "cls", "echo ", "type ", "copy ", "move ", "del ", "mkdir ", "rmdir ",
            "ipconfig", "ipconfig /all", "ipconfig /flushdns", "ping ", "tracert ", "nslookup ",
            "tasklist", "taskkill /PID ", "netstat -an", "netstat -ano",
            "systeminfo", "whoami", "hostname", "ver", "driverquery",
            "net user", "net stop ", "net start ", "sc query ", "wmic ",
            "sfc /scannow", "chkdsk ", "shutdown /s /t 0", "shutdown /r /t 0",
            "reg query ", "reg add ", "set", "path", "where ", "findstr ",
            "robocopy ", "xcopy ", "curl ", "wget ", "certutil -hashfile ",
            "powershell ", "Get-Process", "Get-Service", "Get-ChildItem ", "Set-ExecutionPolicy RemoteSigned",
            "Get-NetAdapter", "Test-Connection ", "Stop-Process -Name ", "Start-Process "
        )
        private const val MAX_SUGGESTIONS = 8
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShellBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()

        history.append(getString(R.string.shell_banner))
        history.append('\n').append('\n')
        binding.outputText.text = history.toString()

        // Resolve the remote working directory so the prompt looks like
        // cmd.exe (C:\Users\Name>).
        initJob = lifecycleScope.launch {
            val result = RuntimeBrokerApi.shell(
                Prefs.serverUrl(this@ShellActivity),
                machineName,
                Prefs.password(this@ShellActivity),
                "cd /d C:\\Windows\\System32",
                true
            )
            if (result.success) {
                cwd = result.output?.trim()?.lines()?.lastOrNull { it.isNotBlank() } ?: ""
                if (cwd.endsWith(">")) cwd = cwd.trimEnd('>')
                updatePrompt()
            }
        }

        // Keep keyboard open and focus on command input
        binding.commandInput.post {
            binding.commandInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(binding.commandInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        binding.commandInput.setOnEditorActionListener { _, _, _ ->
            runCommand()
            true
        }

        binding.commandInput.setRawInputType(InputType.TYPE_CLASS_TEXT)

        binding.btnClear.setOnClickListener { clearOutput() }
        binding.btnHistory.setOnClickListener { showHistory() }

        // Terminal-style keys
        binding.btnKeyTab.setOnClickListener { tabComplete() }
        binding.btnKeyUp.setOnClickListener { navigateHistory(-1) }
        binding.btnKeyDown.setOnClickListener { navigateHistory(1) }
        binding.btnKeyCtrlC.setOnClickListener {
            binding.commandInput.setText("")
            updateSuggestions()
        }

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

        binding.commandInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateSuggestions()
        })
    }

    /** Returns the token currently being typed (text after the last space). */
    private fun currentToken(): String {
        val text = binding.commandInput.text.toString()
        val end = binding.commandInput.selectionEnd.coerceIn(0, text.length)
        val start = text.lastIndexOf(' ', (end - 1).coerceAtLeast(0)) + 1
        return text.substring(start, end)
    }

    private fun suggestionsFor(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        val lower = token.lowercase()
        // Prefix matches first, then contains matches.
        val prefix = SUGGESTIONS.filter { it.startsWith(lower) && it != lower }
        val contains = SUGGESTIONS.filter { !it.startsWith(lower) && it.contains(lower) }
        return (prefix + contains).take(MAX_SUGGESTIONS)
    }

    private fun updateSuggestions() {
        val sugg = suggestionsFor(currentToken())
        val row = binding.suggestionRow
        row.removeAllViews()
        if (sugg.isEmpty()) {
            binding.suggestionStrip.visibility = View.GONE
            return
        }
        binding.suggestionStrip.visibility = View.VISIBLE
        sugg.forEach { s ->
            val btn = MaterialButton(this).apply {
                text = s.trim()
                isAllCaps = false
                textSize = 12f
                minWidth = 0
                insetTop = 0
                insetBottom = 0
                setTextColor(getColor(R.color.terminal_prompt))
                setPadding((12 * resources.displayMetrics.density).toInt(), 0,
                    (12 * resources.displayMetrics.density).toInt(), 0)
                setOnClickListener { applySuggestion(s) }
            }
            row.addView(btn)
            (btn.layoutParams as LinearLayout.LayoutParams).marginEnd =
                (6 * resources.displayMetrics.density).toInt()
        }
    }

    /** Replaces the token being typed with the chosen suggestion. */
    private fun applySuggestion(suggestion: String) {
        val text = binding.commandInput.text.toString()
        val end = binding.commandInput.selectionEnd.coerceIn(0, text.length)
        val start = text.lastIndexOf(' ', (end - 1).coerceAtLeast(0)) + 1
        val newText = text.substring(0, start) + suggestion
        binding.commandInput.setText(newText)
        binding.commandInput.setSelection(newText.length)
        updateSuggestions()
    }

    /** TAB completes using the first visible suggestion; shows the strip otherwise. */
    private fun tabComplete() {
        val sugg = suggestionsFor(currentToken())
        when {
            sugg.isNotEmpty() -> applySuggestion(sugg[0])
            binding.suggestionStrip.visibility == View.VISIBLE -> {}
            else -> Toast.makeText(this, R.string.shell_no_suggestion, Toast.LENGTH_SHORT).show()
        }
    }

    /** cmd.exe-style prompt: current remote directory + '>'. */
    private fun updatePrompt() {
        binding.promptText.text = if (cwd.isBlank()) getString(R.string.shell_prompt) else "$cwd>"
    }

    private fun runQuick(command: String) {
        binding.commandInput.setText(command)
        binding.commandInput.setSelection(command.length)
        runCommand()
    }

    private fun runCommand() {
        val command = binding.commandInput.text.toString().trim()
        if (command.isEmpty()) return

        // Local cls — clears this terminal like the real thing.
        if (command.equals("cls", ignoreCase = true)) {
            history = StringBuilder()
            binding.outputText.text = ""
            binding.commandInput.setText("")
            updateSuggestions()
            return
        }

        // Add to history
        if (commandHistory.isEmpty() || commandHistory.last() != command) {
            commandHistory.add(command)
            if (commandHistory.size > 100) commandHistory.removeAt(0)
        }
        historyIndex = commandHistory.size

        // cd changes directory on the remote only for its own process, so we
        // append "& cd" to capture the new working directory and keep the
        // prompt in sync — exactly how a persistent cmd session would feel.
        val isCd = command.lowercase().startsWith("cd")
        val effective = if (isCd && !command.endsWith("&cd", true) && !command.endsWith("& cd", true))
            "$command & cd" else command

        runJob?.cancel()
        runJob = lifecycleScope.launch {
            binding.statusText.text = getString(R.string.running)
            var elapsed = 0
            val ticker = launch {
                while (true) {
                    delay(1000)
                    elapsed++
                    binding.statusText.text = getString(R.string.shell_elapsed, elapsed)
                }
            }
            val result = RuntimeBrokerApi.shell(
                Prefs.serverUrl(this@ShellActivity),
                machineName,
                Prefs.password(this@ShellActivity),
                effective,
                true
            )
            ticker.cancel()
            history.append("${getString(R.string.shell_prompt)} $command\n")
            if (result.success) {
                val out = result.output?.takeIf { it.isNotBlank() } ?: getString(R.string.no_output)
                if (isCd) {
                    // Last non-blank line of "cd X & cd" output is the new dir.
                    cwd = out.trim().lines().lastOrNull { it.isNotBlank() } ?: cwd
                    if (cwd.endsWith(">")) cwd = cwd.trimEnd('>')
                    updatePrompt()
                    history.append(out).append('\n')
                } else {
                    history.append(out).append('\n')
                    result.error?.takeIf { it.isNotBlank() }?.let { history.append(it).append('\n') }
                }
            } else {
                history.append(getString(R.string.capture_failed, result.error ?: "error")).append('\n')
                Toast.makeText(this@ShellActivity, result.error ?: "error", Toast.LENGTH_SHORT).show()
            }
            history.append('\n')
            binding.outputText.text = history.toString()
            binding.statusText.text = getString(R.string.done)
            binding.outputScroll.post { binding.outputScroll.fullScroll(View.FOCUS_DOWN) }
            binding.commandInput.text.clear()
            updateSuggestions()
            // Keep keyboard open and focus on input
            binding.commandInput.post {
                binding.commandInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(binding.commandInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
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
                history.append(getString(R.string.shell_banner)).append("\n\n")
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
