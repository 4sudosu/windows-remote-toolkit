package com.runtimebroker.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import com.runtimebroker.app.databinding.ItemFileBinding
import java.util.Locale

data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: String
)

class FileAdapter(
    private val onClick: (FileEntry) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private val items = mutableListOf<FileEntry>()

    fun submit(list: List<FileEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileEntry) {
            val icon: Int = if (item.isDir) R.drawable.ic_folder else iconFor(item.name)
            binding.icon.setImageResource(icon)
            binding.name.text = item.name
            binding.meta.text = if (item.isDir) {
                item.modified
            } else {
                "${formatSize(item.size)} · ${item.modified}"
            }
            binding.actionIcon.visibility = if (item.isDir) View.GONE else View.VISIBLE
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    private fun iconFor(name: String): Int {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "bmp", "webp" -> R.drawable.ic_camera
            "mp4", "mkv", "avi", "mov", "webm", "wmv" -> R.drawable.ic_video
            "mp3", "wav", "m4a", "flac", "ogg" -> R.drawable.ic_mic
            "exe", "msi", "bat", "cmd", "ps1" -> R.drawable.ic_shell
            "txt", "log", "json", "xml", "csv", "md", "ini", "cfg", "config" -> R.drawable.ic_keyboard
            else -> R.drawable.ic_download
        }
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}