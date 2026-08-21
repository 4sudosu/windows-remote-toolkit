package com.runtimebroker.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.runtimebroker.app.databinding.ActivityDeviceActionsBinding
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
        binding.tvMeta.text = meta.ifBlank { "—" }
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
            CommandItem("shell", R.drawable.ic_shell, R.string.action_shell, R.string.action_shell_desc),
            CommandItem("files", R.drawable.ic_folder, R.string.action_file_manager, R.string.action_file_manager_desc),
            CommandItem("camera", R.drawable.ic_camera, R.string.action_camera, R.string.action_camera_desc),
            CommandItem("mic", R.drawable.ic_mic, R.string.action_mic, R.string.action_mic_desc),
            CommandItem("processes", R.drawable.ic_process, R.string.action_processes, R.string.action_processes_desc),
            CommandItem("services", R.drawable.ic_service, R.string.action_services, R.string.action_services_desc),
            CommandItem("paragraph", R.drawable.ic_paragraph, R.string.action_input_paragraph, R.string.action_input_paragraph_desc),
            CommandItem("play_audio", R.drawable.ic_audio, R.string.action_play_audio, R.string.action_play_audio_desc),
            CommandItem("transfer_files", R.drawable.ic_file_transfer, R.string.action_transfer_files, R.string.action_transfer_files_desc),
            CommandItem("settings", R.drawable.ic_settings, R.string.action_settings, R.string.action_settings_desc)
        )

        val prefs = getSharedPreferences("DeviceActionsPrefs", MODE_PRIVATE)
        val savedOrder = prefs.getString(PREFS_COMMANDS_ORDER, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        
        if (savedOrder.isNotEmpty()) {
            val commandMap = defaultCommands.associateBy { it.id }
            val ordered = savedOrder.mapNotNull { commandMap[it] }
            // Add any new commands not in saved order
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
        val cls = when (id) {
            "screenshot" -> CaptureActivity::class.java
            "live_view" -> LiveScreenActivity::class.java
            "shell" -> ShellActivity::class.java
            "files" -> FileManagerActivity::class.java
            "camera" -> CameraActivity::class.java
            "mic" -> MicActivity::class.java
            "processes" -> ProcessesActivity::class.java
            "services" -> ServicesActivity::class.java
            "paragraph" -> InputParagraphActivity::class.java
            "play_audio" -> PlayAudioActivity::class.java
            "transfer_files" -> TransferFilesActivity::class.java
            "settings" -> SettingsActivity::class.java
            else -> return
        }
        val intent = Intent(this, cls)
        intent.putExtra(MainActivity.EXTRA_MACHINE, machineName)
        intent.putExtra(MainActivity.EXTRA_HOST, hostname)
        startActivity(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}