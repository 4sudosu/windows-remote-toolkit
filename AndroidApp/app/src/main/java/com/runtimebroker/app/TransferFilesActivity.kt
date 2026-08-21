package com.runtimebroker.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runtimebroker.app.api.RuntimeBrokerApi
import com.runtimebroker.app.databinding.ActivityTransferFilesBinding
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

class TransferFilesActivity : BaseActivity() {

    private lateinit var binding: ActivityTransferFilesBinding
    private lateinit var adapter: TransferAdapter

    private var machineName = ""
    private var selectedUris = mutableListOf<Uri>()
    private var transferJob: Job? = null
    private var isTransferring = false

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val clipData = data?.clipData
            val uris = if (clipData != null && clipData.itemCount > 0) {
                (0 until clipData.itemCount).map { clipData.getItemAt(it).uri!! }
            } else {
                data?.data?.let { listOf(it) } ?: emptyList()
            }
            selectedUris.addAll(uris)
            updateFileList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransferFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        machineName = intent.getStringExtra(MainActivity.EXTRA_MACHINE).orEmpty()
        supportActionBar?.title = getString(R.string.transfer_files_title)

        adapter = TransferAdapter()
        binding.transferList.layoutManager = LinearLayoutManager(this)
        binding.transferList.adapter = adapter

        binding.btnSelectFiles.setOnClickListener { selectFiles() }
        binding.btnTransferFiles.setOnClickListener { transferFiles() }
        binding.btnClearSelection.setOnClickListener { clearSelection() }
    }

    private fun selectFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        pickFiles.launch(intent)
    }

    private fun updateFileList() {
        binding.tvSelectedFiles.text = getString(R.string.files_selected, selectedUris.size)
        val hasFiles = selectedUris.isNotEmpty()
        binding.btnTransferFiles.visibility = if (hasFiles) View.VISIBLE else View.GONE
        binding.btnClearSelection.visibility = if (hasFiles) View.VISIBLE else View.GONE
        adapter.submitList(selectedUris.map { TransferFile(uri = it) })
    }

    private fun clearSelection() {
        selectedUris.clear()
        updateFileList()
    }

    private fun transferFiles() {
        if (selectedUris.isEmpty()) {
            Toast.makeText(this, R.string.no_files_selected, Toast.LENGTH_SHORT).show()
            return
        }
        if (isTransferring) return

        isTransferring = true
        transferJob = lifecycleScope.launch {
            binding.btnTransferFiles.isEnabled = false
            binding.btnClearSelection.isEnabled = false
            binding.btnSelectFiles.isEnabled = false

            var completed = 0
            for ((index, uri) in selectedUris.withIndex()) {
                if (!isTransferring) break
                
                adapter.updateItemStatus(index, TransferAdapter.Status.TRANSFERRING)
                binding.statusText.text = getString(R.string.transferring, completed + 1, selectedUris.size)

                try {
                    val bytes = contentResolver.openInputStream(uri)?.readAllBytes() ?: byteArrayOf()
                    val base64File = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                    // Generous timeout: ~1 min per MB, minimum 3 minutes.
                    val timeoutSec = (bytes.size / (1024 * 1024) + 3) * 60

                    val result = RuntimeBrokerApi.command(
                        Prefs.serverUrl(this@TransferFilesActivity),
                        machineName,
                        Prefs.password(this@TransferFilesActivity),
                        "transfer_file",
                        JSONObject()
                            .put("file_base64", base64File)
                            .put("filename", uri.lastPathSegment ?: "file")
                            .put("timeoutSec", timeoutSec)
                    )
                    
                    completed++
                    binding.statusText.text = getString(R.string.transferring, completed, selectedUris.size)
                    
                    if (result.success) {
                        adapter.updateItemStatus(index, TransferAdapter.Status.COMPLETED)
                    } else {
                        adapter.updateItemStatus(index, TransferAdapter.Status.FAILED, result.error ?: "error")
                        Toast.makeText(this@TransferFilesActivity, getString(R.string.transfer_failed, result.error ?: "error"), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    adapter.updateItemStatus(index, TransferAdapter.Status.FAILED, e.message ?: "error")
                    Toast.makeText(this@TransferFilesActivity, getString(R.string.transfer_failed, e.message ?: "error"), Toast.LENGTH_SHORT).show()
                }
            }

            isTransferring = false
            binding.btnTransferFiles.isEnabled = true
            binding.btnClearSelection.isEnabled = true
            binding.btnSelectFiles.isEnabled = true
            
            if (completed == selectedUris.size) {
                binding.statusText.text = getString(R.string.transfer_complete)
                Toast.makeText(this@TransferFilesActivity, R.string.transfer_complete, Toast.LENGTH_LONG).show()
            } else if (!isTransferring) {
                binding.statusText.text = getString(R.string.transfer_stopped)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (isTransferring) {
            Toast.makeText(this, "Transfer in progress. Please wait or cancel first.", Toast.LENGTH_SHORT).show()
            return true
        }
        finish()
        return true
    }

    override fun onDestroy() {
        transferJob?.cancel()
        super.onDestroy()
    }

    data class TransferFile(
        val uri: Uri,
        var status: TransferAdapter.Status = TransferAdapter.Status.PENDING,
        var errorMsg: String? = null,
        var progress: Int = 0
    )

    class TransferAdapter : ListAdapter<TransferFile, TransferAdapter.VH>(DiffCallback()) {
        enum class Status { PENDING, TRANSFERRING, COMPLETED, FAILED }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = com.runtimebroker.app.databinding.ItemTransferFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(getItem(position))
        }

        fun updateItemStatus(position: Int, status: Status, errorMsg: String? = null) {
            val currentList = this.currentList.toMutableList()
            if (position < currentList.size) {
                val item = currentList[position]
                val updated = TransferFile(item.uri, status, errorMsg, item.progress)
                currentList[position] = updated
                submitList(currentList)
            }
        }

        inner class VH(private val binding: com.runtimebroker.app.databinding.ItemTransferFileBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: TransferFile) {
                binding.tvFileName.text = item.uri.lastPathSegment ?: "Unknown"
                
                when (item.status) {
                    Status.PENDING -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvProgressText.text = binding.tvProgressText.context.getString(R.string.ready_to_transfer)
                        binding.tvProgressText.setTextColor(binding.tvProgressText.context.getColor(R.color.offline_gray))
                        binding.btnCancelTransfer.visibility = View.GONE
                    }
                    Status.TRANSFERRING -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.progressBar.isIndeterminate = true
                        binding.tvProgressText.text = "Transferring..."
                        binding.tvProgressText.setTextColor(binding.tvProgressText.context.getColor(R.color.online_green))
                        binding.btnCancelTransfer.visibility = View.VISIBLE
                    }
                    Status.COMPLETED -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.progressBar.isIndeterminate = false
                        binding.progressBar.progress = 100
                        binding.tvProgressText.text = "Completed"
                        binding.tvProgressText.setTextColor(binding.tvProgressText.context.getColor(R.color.online_green))
                        binding.btnCancelTransfer.visibility = View.GONE
                    }
                    Status.FAILED -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.progressBar.isIndeterminate = false
                        binding.progressBar.progress = 0
                        binding.tvProgressText.text = "Failed: ${item.errorMsg ?: "Unknown error"}"
                        binding.tvProgressText.setTextColor(binding.tvProgressText.context.getColor(R.color.error_red))
                        binding.btnCancelTransfer.visibility = View.GONE
                    }
                }
                
                binding.btnCancelTransfer.setOnClickListener {
                    // Could implement cancel logic here
                    Toast.makeText(binding.root.context, "Cannot cancel in-progress transfer", Toast.LENGTH_SHORT).show()
                }
            }
        }

        class DiffCallback : DiffUtil.ItemCallback<TransferFile>() {
            override fun areItemsTheSame(oldItem: TransferFile, newItem: TransferFile) = oldItem.uri == newItem.uri
            override fun areContentsTheSame(oldItem: TransferFile, newItem: TransferFile) = oldItem == newItem
        }
    }
}